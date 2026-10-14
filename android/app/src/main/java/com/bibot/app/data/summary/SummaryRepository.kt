package com.bibot.app.data.summary

import android.content.Context
import com.bibot.app.data.db.BibotDatabase
import com.bibot.app.data.db.ChapterSummary
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.data.ads.AdsPreferences
import com.bibot.app.data.store.BotPromptManager
import com.bibot.app.core.llm.LlmBridge
import com.bibot.app.core.llm.LlmStreamListener
import com.bibot.app.data.sync.FirestoreSync
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * B5: tóm tắt AI theo chương + cấp sách (map-reduce), cache content-hash, chia sẻ wiki.
 *
 * Thứ tự đọc (B5.7): cache local (Room) → Firestore public `wiki_summaries`
 * → miss mới gọi LLM → ghi local + upload (nếu user bật chia sẻ).
 * Key chia sẻ = content-hash chương + model + promptVer — prompt mới không đè bản cũ.
 * KHÔNG BAO GIỜ upload nguyên văn sách, chỉ tóm tắt phái sinh ngắn.
 *
 * Không Compose: UI/Worker gọi các suspend fun này (B5.5 chạy nền WorkManager).
 */
object SummaryRepository {
    /** Cap input/chương + số chương/lần (B5.3 — chặn đốt token sách dày). */
    const val MAX_BOOK_CHAPTERS = 20
    private const val LLM_TIMEOUT_MS = 120_000L
    /** Fallback persona của LlmBridge khi mọi nguồn lỗi — coi như LLM thất bại. */
    private const val FALLBACK_PREFIX = "Em là Bi. Hiện tại em chưa kết nối"

    /** SHA-256 hex của text chuẩn hoá (gộp whitespace) — identity nội dung chương. */
    fun contentHash(text: String): String = try {
        val norm = text.replace(Regex("\\s+"), " ").trim()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(norm.toByteArray(Charsets.UTF_8))
        buildString(bytes.size * 2) { bytes.forEach { b -> append("%02x".format(b)) } }
    } catch (_: Exception) { text.length.toString() + "." + text.hashCode() }

    /** Tên model đưa vào docId (docId cấm `/`; OpenRouter có `a/b:c`). */
    fun sanitizeModel(model: String): String =
        model.trim().replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').take(48).ifBlank { "default" }

    fun docId(contentHash: String, model: String, promptVer: Int): String =
        "${contentHash.take(64)}_${sanitizeModel(model)}_v$promptVer"

    sealed interface Outcome {
        data class Ready(val text: String, val fromShared: Boolean, val fresh: Boolean) : Outcome
        data object NeedKey : Outcome // mất key/offline: không gọi LLM (giữ vị trí đọc)
        data object NoCredit : Outcome // non-VIP hết lượt
        data class Failed(val msg: String) : Outcome
    }

    data class LlmKeys(
        val geminiKey: String = "",
        val geminiModel: String = "gemini-3.5-flash-lite",
        val openRouterKey: String = ""
    )

    /** Thu stream LLM thành 1 chuỗi; null = thất bại (kể cả fallback persona). */
    suspend fun collectLlm(system: String, user: String, keys: LlmKeys, maxTokens: Int): String? =
        withContext(Dispatchers.IO) {
            if (keys.geminiKey.isBlank() && keys.openRouterKey.isBlank() &&
                com.bibot.app.BuildConfig.BIBOT_LLM_PROXY_URL.isBlank()
            ) return@withContext null
            val sb = StringBuilder()
            var failed = false
            val done = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                val latch = kotlinx.coroutines.CompletableDeferred<Unit>()
                try {
                    LlmBridge.generateStreamRest(
                        prompt = user,
                        geminiKey = keys.geminiKey.ifBlank { null },
                        systemPrompt = system,
                        geminiModel = keys.geminiModel,
                        openRouterKey = keys.openRouterKey.ifBlank { null },
                        maxOutputTokens = maxTokens,
                        listener = object : LlmStreamListener {
                            override fun onToken(token: String) { sb.append(token) }
                            override fun onComplete() { latch.complete(Unit) }
                            override fun onError(errorMessage: String) {
                                failed = true
                                latch.complete(Unit)
                            }
                        }
                    )
                    latch.await()
                    true
                } catch (_: Exception) {
                    failed = true
                    false
                }
            } ?: false
            if (!done || failed) return@withContext null
            val out = sb.toString().trim()
            if (out.isBlank() || out.startsWith(FALLBACK_PREFIX)) return@withContext null
            out
        }

    /**
     * Tóm tắt 1 chương. [text] là nội dung chương (đã clean, caller cắt cap).
     * Trừ 1 lượt rewarded khi gọi LLM thành công (VIP bypass).
     */
    suspend fun summarizeChapter(
        context: Context,
        bookId: String,
        chapterIdx: Int,
        title: String,
        text: String,
        lang: String = "vi",
        keys: LlmKeys,
        vipActive: Boolean,
        shareEnabled: Boolean
    ): Outcome = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Outcome.Failed("empty")
        val db = BibotDatabase.get(context)
        val model = sanitizeModel(keys.geminiModel)
        val ver = BotPromptManager.SUMMARY_PROMPT_VER
        val hash = contentHash(text)
        val id = docId(hash, model, ver)

        // 1. Cache local (mở lại hiện ngay, 0 token).
        db.summaries().get(id)?.let { return@withContext Outcome.Ready(it.text, it.fromShared, false) }
        // 1b. Cùng sách/chương nhưng model/ver khác → không dùng (key đã tách), tiếp.
        // 2. Wiki công cộng (máy khác đã trả token).
        try {
            FirestoreSync.getWikiSummary(context, id)?.let { w ->
                if (w.text.isNotBlank()) {
                    db.summaries().upsert(
                        ChapterSummary(id, bookId, chapterIdx, title, model, ver, w.text, fromShared = true, shared = true)
                    )
                    FirestoreSync.bumpWikiHits(context, id)
                    return@withContext Outcome.Ready(w.text, true, false)
                }
            }
        } catch (_: Exception) {}

        // 3. Gating (B5.4): VIP full; non-VIP trừ lượt đã đổi từ ad.
        val ads = AdsPreferences(context)
        if (!vipActive) {
            if (ads.getRewardedSummary() <= 0) return@withContext Outcome.NoCredit
        }
        if (keys.geminiKey.isBlank() && keys.openRouterKey.isBlank() &&
            com.bibot.app.BuildConfig.BIBOT_LLM_PROXY_URL.isBlank()
        ) return@withContext Outcome.NeedKey

        // 4. Gọi LLM (B5.2: reuse LlmBridge Gemini→Proxy→OpenRouter).
        val out = collectLlm(
            BotPromptManager.SUMMARY_SYS_V1,
            BotPromptManager.summaryChapterUser(title, text),
            keys, BotPromptManager.SUMMARY_MAX_OUTPUT_TOKENS
        ) ?: return@withContext Outcome.Failed("llm")
        db.summaries().upsert(
            ChapterSummary(id, bookId, chapterIdx, title, model, ver, out, fromShared = false, shared = false)
        )
        if (!vipActive) ads.setRewardedSummary(ads.getRewardedSummary() - 1)
        // 5. Upload chia sẻ (mặc định bật, tắt được; chỉ tóm tắt phái sinh).
        if (shareEnabled) {
            val ok = try {
                FirestoreSync.putWikiSummary(
                    context, id,
                    FirestoreSync.WikiSummary(out, model, ver, title.take(200), lang)
                )
            } catch (_: Exception) { false }
            if (ok) db.summaries().upsert(
                ChapterSummary(id, bookId, chapterIdx, title, model, ver, out, fromShared = false, shared = true)
            )
        }
        Outcome.Ready(out, false, true)
    }

    /**
     * Tóm tắt cấp sách (map-reduce): tóm tắt từng chương (dùng lại cache/wiki) rồi
     * dồn 1 call combine. Lưu chapterIdx=-1. Non-VIP cần đủ lượt cho mọi call.
     */
    suspend fun summarizeBook(
        context: Context,
        bookId: String,
        bookTitle: String,
        chapters: List<Triple<Int, String, String>>,
        lang: String = "vi",
        keys: LlmKeys,
        vipActive: Boolean,
        shareEnabled: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Outcome = withContext(Dispatchers.IO) {
        val use = chapters.take(MAX_BOOK_CHAPTERS)
        if (use.isEmpty()) return@withContext Outcome.Failed("empty")
        val ads = AdsPreferences(context)
        // Non-VIP: cần đủ lượt cho N chương + 1 combine (chặn đốt dở).
        if (!vipActive) {
            if (ads.getRewardedSummary() < use.size + 1) return@withContext Outcome.NoCredit
        }
        if (keys.geminiKey.isBlank() && keys.openRouterKey.isBlank() &&
            com.bibot.app.BuildConfig.BIBOT_LLM_PROXY_URL.isBlank()
        ) return@withContext Outcome.NeedKey
        val parts = mutableListOf<String>()
        use.forEachIndexed { i, (idx, title, text) ->
            when (val r = summarizeChapter(context, bookId, idx, title, text, lang, keys, vipActive, shareEnabled)) {
                is Outcome.Ready -> parts += "## $title\n${r.text}"
                else -> return@withContext r // NoCredit/NeedKey/Failed lan lên
            }
            onProgress(i + 1, use.size + 1)
        }
        val model = sanitizeModel(keys.geminiModel)
        val ver = BotPromptManager.SUMMARY_PROMPT_VER
        val id = docId(contentHash(parts.joinToString("\n")), model, ver) + "_book"
        BibotDatabase.get(context).summaries().get(id)?.let {
            return@withContext Outcome.Ready(it.text, it.fromShared, false)
        }
        val out = collectLlm(
            BotPromptManager.SUMMARY_SYS_V1,
            BotPromptManager.summaryBookUser(bookTitle, parts),
            keys, BotPromptManager.SUMMARY_MAX_OUTPUT_TOKENS
        ) ?: return@withContext Outcome.Failed("llm")
        val db = BibotDatabase.get(context)
        db.summaries().upsert(
            ChapterSummary(id, bookId, -1, bookTitle, model, ver, out, fromShared = false, shared = false)
        )
        if (!vipActive) ads.setRewardedSummary(ads.getRewardedSummary() - 1)
        if (shareEnabled) {
            val ok = try {
                FirestoreSync.putWikiSummary(context, id, FirestoreSync.WikiSummary(out, model, ver, bookTitle.take(200), lang))
            } catch (_: Exception) { false }
            if (ok) db.summaries().upsert(
                ChapterSummary(id, bookId, -1, bookTitle, model, ver, out, fromShared = false, shared = true)
            )
        }
        onProgress(use.size + 1, use.size + 1)
        Outcome.Ready(out, false, true)
    }
}
