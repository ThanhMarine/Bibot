package com.bibot.app.data.books

import android.content.Context
import com.bibot.app.core.text.BlockTextReader
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.File as JFile

/**
 * Kho sách Drive CÔNG KHAI (không cần tài khoản, không cần API key).
 *
 * Danh sách file lấy ĐỘNG từ trang embeddedfolderview của folder — CHƯA từng
 * hardcode trong APK. Chỉ gọi khi user thao tác (Đồng bộ / mở kho / tải link),
 * nên offline hay folder đổi nội dung thì list luôn phản ánh đúng hiện trạng.
 *
 * - Folder 401/403 (riêng tư) → FolderPrivateException → UI cảnh báo "chưa chia sẻ công khai".
 * - Folder 404 (xoá) → coi như rỗng, không lỗi.
 * - Lỗi mạng (offline) → ném ra ngoài để UI báo "không tải được danh mục".
 * - Tải file qua `drive.google.com/uc?export=download&id=...` buffer 32KB,
 *   có xử lý confirm-token 1 vòng phòng Google hỏi lại.
 * HttpURLConnection thuần (không dep, không google-drive lib) — chạy cả 2 flavor.
 * Khác DriveBookSource (OAuth folder riêng, commercial-only).
 */
class PublicDriveSource(private val context: Context) {

    /** Keyless nên luôn sẵn sàng (chỉ cần mạng). */
    fun isAvailable(): Boolean = true

    /** Lấy folder ID mặc định (VI). */
    fun defaultFolderId(): String = VietBooksCatalog.FOLDER_ID

    /**
     * List ĐỘNG 1 folder public.
     * @throws java.io.IOException/UnknownHostException… khi lỗi mạng (offline).
     * @throws FolderPrivateException khi folder riêng tư (401/403).
     */
    suspend fun list(folderId: String = defaultFolderId()): List<BookMeta> = withContext(Dispatchers.IO) {
        val html = fetchFolderHtml(folderId)
        if (html.isEmpty()) return@withContext emptyList()
        // lang + collection theo folder: story (VI) / cotich (Cổ tích) / thamkhao / en (mặc định)
        val lang: String
        val collection: String
        when (folderId) {
            VietBooksCatalog.FOLDER_ID -> { lang = "vi"; collection = "story" }
            CotichBooksCatalog.FOLDER_ID -> { lang = "vi"; collection = "cotich" }
            ThamkhaoBooksCatalog.FOLDER_ID -> { lang = "vi"; collection = "thamkhao" }
            else -> { lang = "en"; collection = "en" }
        }
        parseEntries(html).map { (fileId, title) ->
            BookMeta(
                id = "pdrive-$fileId",
                title = title,
                lang = lang,
                source = "pdrive",
                mimeType = mimeFor(title),
                sizeBytes = 0,
                remoteUrl = fileId,
                collection = collection
            )
        }
    }

    /**
     * List cả 4 folder VI + EN + Cổ tích + Tham khảo. Lỗi mạng vẫn ném ra để UI biết.
     * Folder riêng tư (401/403) → bỏ qua folder đó, ghi id vào [privateFolders]
     * để UI cảnh báo "chưa chia sẻ công khai" (không nuốt mất folder còn lại).
     * Folder chưa cấu hình (FOLDER_ID trống) → bỏ qua.
     */
    suspend fun listAll(privateFolders: MutableList<String> = mutableListOf()): List<BookMeta> {
        val out = mutableListOf<BookMeta>()
        for (id in listOf(VietBooksCatalog.FOLDER_ID, EnBooksCatalog.FOLDER_ID, CotichBooksCatalog.FOLDER_ID, ThamkhaoBooksCatalog.FOLDER_ID).filter { it.isNotBlank() }) {
            try {
                out += list(id)
            } catch (e: FolderPrivateException) {
                privateFolders.add(id)
            }
        }
        return out
    }

    // ===== HTML listing =====

    private fun fetchFolderHtml(folderId: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://drive.google.com/embeddedfolderview?id=$folderId").openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Bibot/1.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code == 401 || code == 403) throw FolderPrivateException(folderId) // riêng tư → cảnh báo UI
            if (code in 400..499) return "" // không tồn tại (404) / lỗi khác → rỗng, không lỗi
            if (code !in 200..299) throw IllegalStateException("Folder HTTP $code")
            // Giới hạn 1MB (~hàng nghìn file) — tránh spike RAM với folder lớn
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { BlockTextReader.readHead(it, 1_000_000) }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Trích (fileId, title) từ HTML embeddedfolderview.
     * Chỉ lấy entry có link `/file/d/<id>/view` → bỏ qua sub-folder.
     */
    private fun parseEntries(html: String): List<Pair<String, String>> {
        val regex = Regex(
            """<a href="https://drive\.google\.com/file/d/([a-zA-Z0-9_-]+)/view[^"]*".*?<div class="flip-entry-title">(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL
        )
        return regex.findAll(html)
            .map { it.groupValues[1] to unescapeHtml(it.groupValues[2].trim()) }
            .filter { it.second.isNotBlank() }
            .toList()
    }

    private fun unescapeHtml(s: String): String {
        var out = Regex("""&#x([0-9a-fA-F]+);""").replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value
        }
        out = Regex("""&#(\d+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value
        }
        out = out.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&nbsp;", " ")
        return out.replace("&amp;", "&") // &amp; sau cùng tránh decode 2 lần
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "md" -> "text/markdown"
        "txt" -> "text/plain"
        "epub" -> "application/epub+zip"
        "mobi", "azw", "azw3" -> "application/x-mobipocket-ebook"
        else -> "application/octet-stream"
    }

    // ===== Download =====

    suspend fun download(meta: BookMeta, onProgress: (Int) -> Unit = {}): JFile = withContext(Dispatchers.IO) {
        val fileId = meta.remoteUrl ?: meta.id.removePrefix("pdrive-")
        if (fileId.isBlank()) throw IllegalStateException("Thiếu fileId Drive")
        val dest = JFile(FilePaths.pdfInbox(context), meta.title)
        val finalDest = downloadUc(fileId, dest, onProgress, confirm = null)
        onProgress(100)
        finalDest
    }

    /**
     * Tải 1 file Drive theo fileId (dùng cho user dán link
     * `https://drive.google.com/file/d/<ID>/view`). Tên file lấy từ
     * Content-Disposition/Content-Type nếu tên tạm chưa có đuôi.
     */
    suspend fun downloadFileId(fileId: String, onProgress: (Int) -> Unit = {}): JFile = withContext(Dispatchers.IO) {
        val dest = JFile(FilePaths.pdfInbox(context), fileId)
        val finalDest = downloadUc(fileId, dest, onProgress, confirm = null)
        onProgress(100)
        finalDest
    }

    /**
     * Bổ sung tên/đuôi file khi tên tạm chưa có đuôi (dán link Drive không rõ tên):
     * ưu tiên Content-Disposition, fallback theo Content-Type.
     */
    private fun resolveDest(conn: HttpURLConnection, dest: JFile): JFile {
        if (dest.extension.isNotBlank()) return dest
        val disp = conn.getHeaderField("Content-Disposition") ?: ""
        val fromHeader = Regex("""filename\*=UTF-8''([^;]+)""").find(disp)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: Regex("""filename="?([^";]+)"?""").find(disp)?.groupValues?.get(1)
        val ct = (conn.contentType ?: "").lowercase()
        val fallback = dest.name + when {
            ct.contains("pdf") -> ".pdf"
            ct.contains("epub") -> ".epub"
            ct.contains("markdown") -> ".md"
            ct.contains("html") -> ".html"
            else -> ".txt"
        }
        val chosen = (fromHeader?.trim() ?: fallback)
            .substringAfterLast('/').substringAfterLast('\\')
        return JFile(dest.parentFile, chosen.ifBlank { fallback })
    }

    private fun downloadUc(fileId: String, dest: JFile, onProgress: (Int) -> Unit, confirm: String?): JFile {
        var conn: HttpURLConnection? = null
        try {
            val sb = StringBuilder("https://drive.google.com/uc?export=download&id=").append(fileId)
            if (confirm != null) sb.append("&confirm=").append(confirm)
            conn = URL(sb.toString()).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Bibot/1.0")
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Drive HTTP ${conn.responseCode}")
            }
            val contentType = (conn.contentType ?: "").lowercase()
            // Google trả trang confirm (text/html) thay vì file → trích token, thử lại 1 lần
            if (contentType.contains("text/html")) {
                if (confirm != null) throw IllegalStateException("Drive yêu cầu xác nhận tải (file lớn?)")
                val html = try {
                    // Đọc tối đa 200KB (không readText() cả trang — tránh spike RAM khi Google trả HTML lớn)
                    conn.inputStream.bufferedReader().use { BlockTextReader.readHead(it, 200_000) }
                } catch (_: Exception) { "" }
                val token = Regex("confirm=([a-zA-Z0-9_-]+)").find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("\"downloadUrl\":\"([^\"]+)\"").find(html)?.groupValues?.getOrNull(1)
                try { conn.disconnect() } catch (_: Exception) {}
                conn = null
                if (token != null && !token.startsWith("http")) {
                    return downloadUc(fileId, dest, onProgress, confirm = token)
                }
                throw IllegalStateException("Drive yêu cầu xác nhận tải (file lớn?)")
            }
            val finalDest = resolveDest(conn, dest)
            val total = conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(finalDest).use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            return finalDest
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    companion object {
        /**
         * Trích fileId từ link Drive user dán vào:
         * `/file/d/<ID>/view`, `open?id=<ID>`, `uc?id=<ID>`.
         * Link folder (`/drive/folders/<ID>`) trả về null → caller xử lý riêng.
         */
        fun extractFileId(url: String): String? {
            Regex("""drive\.google\.com/file/d/([a-zA-Z0-9_-]{20,})""").find(url)?.let { return it.groupValues[1] }
            Regex("""[?&]id=([a-zA-Z0-9_-]{20,})""").find(url)?.let { return it.groupValues[1] }
            return null
        }

        /** Nhận diện link folder Drive (không tải trực tiếp được). */
        fun isFolderUrl(url: String): Boolean =
            url.contains("drive.google.com/drive/folders/") || url.contains("drive.google.com/drive/u/")
    }
}

/** Folder Drive riêng tư (401/403) — cần người dùng đặt Chia sẻ: "Bất kỳ ai có liên kết". */
class FolderPrivateException(val folderId: String) :
    IllegalStateException("Folder Drive riêng tư ($folderId) — hãy đặt Chia sẻ: Bất kỳ ai có liên kết")
