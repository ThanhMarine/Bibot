package com.bibot.app.data.sync

import android.content.Context
import com.bibot.app.BuildConfig
import com.bibot.app.data.db.BibotDatabase
import com.bibot.app.data.db.ChatLog
import com.bibot.app.data.db.MemoryCore
import com.bibot.app.data.db.MemoryFact
import com.bibot.app.data.store.BibotPreferences
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * 10.3 Firestore sync — Memory 3 tầng 2 máy (offline-first).
 *
 * Không dùng google-services plugin để build không vỡ khi chưa có google-services.json:
 * init FirebaseApp thủ công từ 3 BuildConfig (bạn điền sau khi tạo Firebase project):
 *   FIREBASE_PROJECT_ID / FIREBASE_APP_ID ("1:xxx:android:yyy") / FIREBASE_API_KEY.
 * Chưa điền → mọi hàm sync trả Result.failure "chưa cấu hình", app chạy offline bình thường.
 *
 * Merge 2 chiều theo mốc thời gian (chỉ upsert phần mới, không re-sync 500 row):
 * - push: core/facts toàn bộ (nhỏ) + log mới hơn MEMORY_LAST_PUSH_TS (tối đa 200/lần)
 * - pull: core/facts upsert + log mới hơn max(timestamp) local
 * - realtime: chỉ collection `core` (nhỏ, đổi ít) qua snapshotListener + Executor riêng
 *   (Worker không có Looper nên không dùng listener mặc định).
 */
data class SyncStats(val pushed: Int, val pulled: Int)

object FirestoreSync {
    const val APP_NAME = "bibot"

    fun isConfigured(): Boolean =
        BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank()

    private fun app(context: Context): FirebaseApp {
        FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }?.let { return it }
        if (!isConfigured()) throw IllegalStateException("Chưa cấu hình Firebase (FIREBASE_PROJECT_ID/APP_ID/API_KEY trống)")
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .build()
        return FirebaseApp.initializeApp(context.applicationContext, options, APP_NAME)
    }

    private suspend fun uid(context: Context): String = withContext(Dispatchers.IO) {
        val auth = FirebaseAuth.getInstance(app(context))
        auth.currentUser?.uid ?: Tasks.await(auth.signInAnonymously()).user?.uid
        ?: throw IllegalStateException("Đăng nhập ẩn danh thất bại")
    }

    private fun store(context: Context): FirebaseFirestore =
        FirebaseFirestore.getInstance(app(context))

    /** Sync 1 vòng đầy đủ (Worker + nút "Đồng bộ ngay" tương lai gọi hàm này). */
    suspend fun syncNow(context: Context): Result<SyncStats> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext Result.failure(Exception("Chưa cấu hình Firebase — bỏ qua sync"))
            val uid = uid(context)
            val pushed = pushAll(context, uid)
            val pulled = pullAll(context, uid)
            Result.success(SyncStats(pushed, pulled))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun pushAll(context: Context, uid: String): Int {
        val db = BibotDatabase.get(context)
        val prefs = BibotPreferences(context)
        val fs = store(context)
        var count = 0
        val batch = fs.batch()

        for (c in db.memoryCore().all()) {
            batch.set(fs.collection("bibot").document(uid).collection("core").document(c.key),
                mapOf("value" to c.value, "createdAt" to c.createdAt))
            count++
        }
        for (f in db.memoryFact().recent(100)) {
            batch.set(fs.collection("bibot").document(uid).collection("facts").document(f.id.toString()),
                mapOf("text" to f.text, "count" to f.count, "createdAt" to f.createdAt, "updatedAt" to f.updatedAt))
            count++
        }
        val lastPush = try { prefs.getMemoryLastPushTs() } catch (_: Exception) { 0L }
        val newLogs = try { db.chatLog().since(lastPush) } catch (_: Exception) { emptyList() }
        for (l in newLogs) {
            batch.set(fs.collection("bibot").document(uid).collection("log").document(l.timestamp.toString()),
                mapOf("role" to l.role, "content" to l.content, "timestamp" to l.timestamp))
            count++
        }
        if (count > 0) Tasks.await(batch.commit())
        try { prefs.setMemoryLastPushTs(System.currentTimeMillis()) } catch (_: Exception) {}
        return count
    }

    private suspend fun pullAll(context: Context, uid: String): Int {
        val db = BibotDatabase.get(context)
        val fs = store(context)
        var count = 0
        val base = fs.collection("bibot").document(uid)

        val coreSnap = Tasks.await(base.collection("core").get())
        for (d in coreSnap.documents) {
            val v = d.getString("value") ?: continue
            db.memoryCore().upsert(MemoryCore(d.id, v, d.getLong("createdAt") ?: System.currentTimeMillis()))
            count++
        }
        val factSnap = Tasks.await(base.collection("facts").get())
        for (d in factSnap.documents) {
            val id = d.id.toLongOrNull() ?: continue
            val text = d.getString("text") ?: continue
            db.memoryFact().upsert(MemoryFact(id, text,
                (d.getLong("count") ?: 1L).toInt(),
                d.getLong("createdAt") ?: System.currentTimeMillis(),
                d.getLong("updatedAt") ?: System.currentTimeMillis()))
            count++
        }
        val localMax = try { db.chatLog().maxTimestamp() ?: 0L } catch (_: Exception) { 0L }
        val logSnap = Tasks.await(base.collection("log")
            .whereGreaterThan("timestamp", localMax)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .limit(200).get())
        for (d in logSnap.documents) {
            val content = d.getString("content") ?: continue
            db.chatLog().insert(ChatLog(
                role = d.getString("role") ?: "user",
                content = content.take(2000),
                timestamp = d.getLong("timestamp") ?: System.currentTimeMillis()))
            count++
        }
        return count
    }

    // B5.7: wiki tóm tắt công cộng — collection PUBLIC `wiki_summaries/{hash}_{model}_{promptVer}`
    // (khác `bibot/{uid}/...` per-user). Mỗi chương chỉ người đầu tiên trả token,
    // N người sau đọc free. Rules phía server (đặt tay ở Firebase console):
    // public read, auth write, doc ≤ vài KB. App KHÔNG BAO GIỜ upload nguyên văn sách,
    // chỉ tóm tắt phái sinh ngắn + có nút tắt trong Settings.
    const val WIKI_SUMMARIES = "wiki_summaries"

    // B5.8: Wiki book_reader tự động — collection PUBLIC `wiki_books/{bookId}`
    // Mỗi sách: metadata + TOC + chapter summaries (chỉ summaries, không quotes).
    // Khi đủ N% chương có summary → gắn nhãn "wiki hoàn chỉnh".
    // Thiếu → hiện tiến độ + nút "đóng góp" (sinh tóm tắt chương thiếu).
    // Ghi nhãn "AI tóm tắt, có thể sai" + nguồn model/ver; prompt mới
    // (promptVer khác) không đè bản cũ; nút báo xấu → ẩn chờ duyệt.
    // Tuân thủ gỡ nội dung khi khiếu nại bản quyền.
    const val WIKI_BOOKS = "wiki_books"

    data class WikiSummary(
        val text: String,
        val model: String = "",
        val promptVer: Int = 1,
        val chapterTitle: String = "",
        val lang: String = "vi",
        val hits: Long = 0,
        val updatedAt: Long = 0L
    )

    // B5.8: Wiki book_reader — document level wiki
    data class WikiBook(
        val bookId: String,
        val title: String,
        val lang: String = "vi",
        val source: String = "",
        val chapterSummaries: Map<String, WikiChapterSummary>,
        val totalChapters: Int,
        val summarizedChapters: Int,
        val model: String = "",
        val promptVer: Int = 1,
        val status: String = "in_progress", // "in_progress", "complete"
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L
    )

    data class WikiChapterSummary(
        val chapterIdx: Int,
        val title: String,
        val summary: String,
        val model: String = "",
        val promptVer: Int = 1,
        val fromShared: Boolean = false,
        val shared: Boolean = false
    )

    /** Đọc tóm tắt chia sẻ (0 token). Null = miss/lỗi/chưa cấu hình. */
    suspend fun getWikiSummary(context: Context, docId: String): WikiSummary? = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext null
            uid(context) // cần auth (rule: public read nhưng client vẫn cần app hợp lệ)
            val d = Tasks.await(store(context).collection(WIKI_SUMMARIES).document(docId).get())
            if (!d.exists()) return@withContext null
            WikiSummary(
                text = d.getString("text")?.take(8000) ?: return@withContext null,
                model = d.getString("model") ?: "",
                promptVer = (d.getLong("promptVer") ?: 1L).toInt(),
                chapterTitle = d.getString("chapterTitle") ?: "",
                lang = d.getString("lang") ?: "vi",
                hits = d.getLong("hits") ?: 0L,
                updatedAt = d.getLong("updatedAt") ?: 0L
            )
        } catch (_: Exception) { null }
    }

    /** Ghi tóm tắt lên wiki (first-writer-wins: có rồi thì thôi, không đè). */
    suspend fun putWikiSummary(context: Context, docId: String, s: WikiSummary): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext false
            uid(context) // rule: auth write
            val ref = store(context).collection(WIKI_SUMMARIES).document(docId)
            if (Tasks.await(ref.get()).exists()) return@withContext true // người khác viết trước
            Tasks.await(ref.set(mapOf(
                "text" to s.text.take(8000),
                "model" to s.model,
                "promptVer" to s.promptVer,
                "chapterTitle" to s.chapterTitle.take(200),
                "lang" to s.lang,
                "hits" to 1L,
                "updatedAt" to System.currentTimeMillis()
            )))
            true
        } catch (_: Exception) { false }
    }

    /** Đếm lượt đọc ké (best-effort, không chặn luồng chính). */
    suspend fun bumpWikiHits(context: Context, docId: String) = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext
            uid(context)
            val ref = store(context).collection(WIKI_SUMMARIES).document(docId)
            Tasks.await(ref.update("hits", com.google.firebase.firestore.FieldValue.increment(1)))
        } catch (_: Exception) {}
    }

    // B5.8: Wiki book_reader — read book-level wiki
    /** Đọc wiki cấp sách. Null = miss/lỗi/chưa cấu hình. */
    suspend fun getWikiBook(context: Context, bookId: String): WikiBook? = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext null
            uid(context)
            val d = Tasks.await(store(context).collection(WIKI_BOOKS).document(bookId).get())
            if (!d.exists()) return@withContext null

            val chapterSummaries = mutableMapOf<String, WikiChapterSummary>()
            val chaptersMap = d.get("chapterSummaries") as? Map<String, Any> ?: emptyMap()
            chaptersMap.forEach { (key, value) ->
                val map = value as? Map<String, Any> ?: return@forEach
                chapterSummaries[key] = WikiChapterSummary(
                    chapterIdx = (map["chapterIdx"] as? Number ?: 0).toInt(),
                    title = (map["title"] as? String) ?: "",
                    summary = (map["summary"] as? String) ?: "",
                    model = (map["model"] as? String) ?: "",
                    promptVer = ((map["promptVer"] as? Long) ?: 1L).toInt(),
                    fromShared = (map["fromShared"] as? Boolean) ?: false,
                    shared = (map["shared"] as? Boolean) ?: false
                )
            }

            WikiBook(
                bookId = d.getString("bookId") ?: bookId,
                title = d.getString("title") ?: "",
                lang = d.getString("lang") ?: "vi",
                source = d.getString("source") ?: "",
                chapterSummaries = chapterSummaries,
                totalChapters = (d.getLong("totalChapters") ?: 0L).toInt(),
                summarizedChapters = (d.getLong("summarizedChapters") ?: 0L).toInt(),
                model = d.getString("model") ?: "",
                promptVer = (d.getLong("promptVer") ?: 1L).toInt(),
                status = d.getString("status") ?: "in_progress",
                createdAt = d.getLong("createdAt") ?: 0L,
                updatedAt = d.getLong("updatedAt") ?: 0L
            )
        } catch (_: Exception) { null }
    }

    /** Ghi/cập nhật wiki cấp sách. */
    suspend fun putWikiBook(context: Context, book: WikiBook): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext false
            uid(context)
            val ref = store(context).collection(WIKI_BOOKS).document(book.bookId)
            val existing = Tasks.await(ref.get())
            val chapterSummariesMap = mutableMapOf<String, Map<String, Any>>()
            book.chapterSummaries.forEach { (key, value) ->
                chapterSummariesMap[key] = mapOf(
                    "chapterIdx" to value.chapterIdx,
                    "title" to value.title,
                    "summary" to value.summary,
                    "model" to value.model,
                    "promptVer" to value.promptVer,
                    "fromShared" to value.fromShared,
                    "shared" to value.shared
                )
            }
            val data = mapOf(
                "bookId" to book.bookId,
                "title" to book.title,
                "lang" to book.lang,
                "source" to book.source,
                "chapterSummaries" to chapterSummariesMap,
                "totalChapters" to book.totalChapters,
                "summarizedChapters" to book.summarizedChapters,
                "model" to book.model,
                "promptVer" to book.promptVer,
                "lang" to book.lang,
                "source" to book.source,
                "status" to book.status,
                "createdAt" to if (existing.exists()) existing.getLong("createdAt") ?: System.currentTimeMillis() else System.currentTimeMillis(),
                "updatedAt" to System.currentTimeMillis()
            )
            Tasks.await(ref.set(data))
            true
        } catch (_: Exception) { false }
    }

    /** Thêm/cập nhật tóm tắt 1 chương vào wiki sách. */
    suspend fun putWikiChapterSummary(context: Context, bookId: String, chapterSummary: WikiChapterSummary): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext false
            uid(context)
            val ref = store(context).collection(WIKI_BOOKS).document(bookId)
            val existing = Tasks.await(ref.get())
            val chapterSummariesMap = mutableMapOf<String, Map<String, Any>>()
            if (existing.exists()) {
                val existingMap = (existing.get("chapterSummaries") as? Map<String, Any>) ?: emptyMap()
                existingMap.forEach { (key, value) ->
                    val map = value as? Map<*, *> ?: return@forEach
                    chapterSummariesMap[key as String] = map as Map<String, Any>
                }
            }
            // Thêm/cập nhật chapter summary
            chapterSummariesMap[chapterSummary.chapterIdx.toString()] = mapOf(
                "chapterIdx" to chapterSummary.chapterIdx,
                "title" to chapterSummary.title,
                "summary" to chapterSummary.summary,
                "model" to chapterSummary.model,
                "promptVer" to chapterSummary.promptVer,
                "fromShared" to chapterSummary.fromShared,
                "shared" to chapterSummary.shared
            )
            // Tính lại summarizedChapters
            val summarizedCount = chapterSummariesMap.values.count { (it["summary"] as? String)?.isNotBlank() == true }
            val totalChapters = if (existing.exists()) (existing.getLong("totalChapters") ?: 0L).toInt() else 0
            val status = if (summarizedCount >= totalChapters && totalChapters > 0) "complete" else "in_progress"
            val data = mapOf(
                "chapterSummaries" to chapterSummariesMap,
                "summarizedChapters" to summarizedCount,
                "status" to status,
                "updatedAt" to System.currentTimeMillis()
            )
            Tasks.await(ref.set(data, com.google.firebase.firestore.SetOptions.merge()))
            true
        } catch (_: Exception) { false }
    }

    /** Báo cáo tóm tắt xấu (ẩn chờ duyệt). */
    suspend fun reportWikiChapterSummary(context: Context, bookId: String, chapterIdx: Int, reason: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext false
            uid(context)
            val ref = store(context).collection(WIKI_BOOKS).document(bookId)
            val existing = Tasks.await(ref.get())
            if (!existing.exists()) return@withContext false
            val reportsRef = ref.collection("reports").document("${chapterIdx}_${System.currentTimeMillis()}")
            Tasks.await(reportsRef.set(mapOf(
                "chapterIdx" to chapterIdx,
                "reason" to reason,
                "reportedAt" to System.currentTimeMillis(),
                "status" to "pending"
            )))
            true
        } catch (_: Exception) { false }
    /** Đếm lượt đọc ké (best-effort, không chặn luồng chính). */
    suspend fun bumpWikiHits(context: Context, docId: String) = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext
            uid(context)
            val ref = store(context).collection(WIKI_SUMMARIES).document(docId)
            Tasks.await(ref.update("hits", com.google.firebase.firestore.FieldValue.increment(1)))
        } catch (_: Exception) {}
    }

/**
     * Realtime collection `core` — collect trong scope màn hình mở (không auto-start ở BibotApp
     * để đỡ tốn pin; Worker định kỳ đã bao phủ sync nền).
     */
fun observeCore(context: Context): kotlinx.coroutines.flow.Flow<Map<String, String>> {
    return kotlinx.coroutines.flow.flowOf(emptyMap<String, String>())
}
}
}
