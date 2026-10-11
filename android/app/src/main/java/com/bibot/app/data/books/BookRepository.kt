package com.bibot.app.data.books

import android.content.Context
import com.bibot.app.core.text.BlockTextReader
import com.bibot.app.core.text.PdfTextCleaner
import com.bibot.app.core.text.TextCleaner
import com.bibot.app.core.tts.VietnameseChunker
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kho sách 3 tầng — tối ưu demo + official:
 *  Demo: AssetsBookSource (đọc assets/demo_books/ nếu có — mặc định rỗng, đã gỡ khỏi APK)
 *  Official: DriveBookSource (Google Drive folder của bạn) + PublicUrlSource (link http)
 * Tất cả cache về FilePaths.audioBooks() / pdfInbox() (chuẩn Android, không cần quyền)
 */

data class BookMeta(
    val id: String, // drive fileId hoặc assets path hash
    val title: String,
    val lang: String, // vi/en
    val source: String, // demo/drive/url
    val mimeType: String, // application/pdf, text/markdown
    val sizeBytes: Long = 0,
    val remoteUrl: String? = null, // Drive downloadUrl hoặc http
    val localFile: File? = null, // null = chưa tải
    val collection: String = "" // kho nguồn: "story"/"cotich"/"" (local, link) — phân biệt module Kể chuyện/Cổ tích
) {
    val isDownloaded: Boolean get() = localFile?.exists() == true
    val displaySize: String get() = if (sizeBytes > 0) "${sizeBytes / 1024} KB" else ""
}

interface BookSource {
    suspend fun list(): List<BookMeta>
    suspend fun download(meta: BookMeta, onProgress: (Int) -> Unit = {}): File
    fun isAvailable(context: Context): Boolean = true
}

// Demo: đọc từ assets/demo_books/ (seed on first launch)
class AssetsBookSource(private val context: Context) : BookSource {
    override suspend fun list(): List<BookMeta> = withContext(Dispatchers.IO) {
        val out = mutableListOf<BookMeta>()
        val am = context.assets
        try {
            am.list("demo_books/en")?.forEach { name ->
                if (name.endsWith(".pdf", true) || name.endsWith(".txt")) {
                    out += BookMeta(id = "demo-en-$name", title = name, lang = "en", source = "demo", mimeType = "application/pdf", remoteUrl = "assets/demo_books/en/$name")
                }
            }
            am.list("demo_books/vi")?.forEach { name ->
                if (name.endsWith(".md", true)) {
                    out += BookMeta(id = "demo-vi-$name", title = name, lang = "vi", source = "demo", mimeType = "text/markdown", remoteUrl = "assets/demo_books/vi/$name")
                }
            }
        } catch (_: Exception) {}
        out
    }

    override suspend fun download(meta: BookMeta, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        // Copy assets → filesDir/bibot/books/
        val destDir = FilePaths.audioBooks(context)
        val dest = File(destDir, meta.title)
        if (dest.exists()) return@withContext dest
        try {
            context.assets.open(meta.remoteUrl!!.removePrefix("assets/")).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
            onProgress(100)
        } catch (_: Exception) {
            // Tạo file stub nếu assets thiếu
            dest.writeText("# ${meta.title}\n\nDemo stub — thay bằng file thật trong assets/demo_books/\n")
        }
        dest
    }

    suspend fun seedDemoIfNeeded() = withContext(Dispatchers.IO) {
        val marker = File(FilePaths.stateDir(context), ".demo_seeded")
        if (marker.exists()) return@withContext
        val metas = list()
        if (metas.isEmpty()) { marker.createNewFile(); return@withContext }
        metas.forEach { meta ->
            try { download(meta) } catch (_: Exception) {}
        }
        // Seed 3 en pdf placeholder nếu chưa có pdf thật
        val enDir = File(FilePaths.audioBooks(context), "en").apply { mkdirs() }
        if ((enDir.listFiles()?.size ?: 0) == 0) {
            File(enDir, "README_EN_PDFS.txt").writeText("Place 3 EN PDFs from Gutenberg here or via Drive sync.\n")
        }
        marker.createNewFile()
    }
}

// Public URL (user dán link http/pdf) — tải generic
class PublicUrlSource(private val context: Context) : BookSource {
    override suspend fun list(): List<BookMeta> = emptyList() // không list, chỉ download khi user dán url
    override suspend fun download(meta: BookMeta, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val url = meta.remoteUrl ?: throw IllegalArgumentException("remoteUrl null")
        val dest = File(FilePaths.pdfInbox(context), meta.title.takeIf { it.isNotBlank() } ?: url.substringAfterLast("/"))
        java.net.URL(url).openStream().use { input ->
            // Simple progress (no content-length)
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        onProgress(100)
        dest
    }
    suspend fun downloadFromUrl(url: String, title: String? = null, onProgress: (Int) -> Unit = {}): File {
        val name = title ?: url.substringAfterLast("/").substringBefore("?").ifBlank { "book_${System.currentTimeMillis()}.pdf" }
        return download(BookMeta(id = "url-$url", title = name, lang = "en", source = "url", mimeType = "application/pdf", remoteUrl = url), onProgress)
    }
}

// Repository aggregate
class BookRepository(private val context: Context) {
    private val assetsSource = AssetsBookSource(context)
    private val driveSource: DriveBookSource by lazy { DriveBookSource(context) }
    // Kho công khai VI/EN (list ĐỘNG từ folder Drive, không tài khoản/key) — cả 2 flavor
    private val publicDriveSource: PublicDriveSource by lazy { PublicDriveSource(context) }
    private val urlSource = PublicUrlSource(context)

    private val _books = MutableStateFlow<List<BookMeta>>(emptyList())
    val books: StateFlow<List<BookMeta>> = _books

    /** Cảnh báo lần đồng bộ gần nhất (vd folder EN chưa chia sẻ công khai) — UI hiện trạng thái. */
    private val _lastSyncWarning = MutableStateFlow<String?>(null)
    val lastSyncWarning: StateFlow<String?> = _lastSyncWarning

    /**
     * Nạp lại toàn bộ danh sách — CHỈ gọi khi user thao tác (Đồng bộ / mở kho / tải xong).
     * Danh mục folder lấy ĐỘNG (scrape embeddedfolderview) — không có catalog cứng trong APK.
     * @return true = tải được danh mục folder; false = lỗi mạng (chỉ còn sách local)
     */
    suspend fun refreshAll(driveFolderId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val all = mutableListOf<BookMeta>()
        // 1) Assets demo luôn có (thường rỗng — đã gỡ khỏi APK)
        all += assetsSource.list()
        // 2) Kho công khai VI + EN (list động, không key) — offline thì ném lỗi;
        //    folder riêng tư (401) → bỏ qua + ghi cảnh báo riêng (không nuốt folder còn lại)
        var folderError: Exception? = null
        val privateFolders = mutableListOf<String>()
        try {
            all += publicDriveSource.listAll(privateFolders)
        } catch (e: Exception) {
            folderError = e
        }
        _lastSyncWarning.value = if (privateFolders.isEmpty()) null else {
            val names = privateFolders.joinToString(" + ") {
                when (it) {
                    EnBooksCatalog.FOLDER_ID -> "EN"
                    CotichBooksCatalog.FOLDER_ID -> "Cổ tích"
                    else -> "VI"
                }
            }
            "⚠ Kho sách Drive $names đang riêng tư (HTTP 401) — vào Drive: Chia sẻ → \"Bất kỳ ai có liên kết\""
        }
        // 3) Drive OAuth nếu đã login và có folderId — tắt hẳn ở flavor demo (BuildConfig.ENABLE_DRIVE)
        if (driveFolderId != null && com.bibot.app.BuildConfig.ENABLE_DRIVE && driveSource.isAvailable(context)) {
            try { all += driveSource.list(folderId = driveFolderId) } catch (_: Exception) {}
        }
        // 4) Local files đã tải trước đó (pdfInbox + books) — luôn gộp để không mất sách đã tải
        all += scanLocalBooks()
        // Mark downloaded
        val withLocal = all.map { meta ->
            val local = File(FilePaths.pdfInbox(context), meta.title).takeIf { it.exists() }
                ?: File(FilePaths.audioBooks(context), meta.title).takeIf { it.exists() }
                ?: File(FilePaths.audioBooks(context), "en/${meta.title}").takeIf { it.exists() }
            meta.copy(localFile = local, sizeBytes = local?.length() ?: meta.sizeBytes)
        }
        // Dedupe theo TÊN (id khác nhau: pdrive-<fileId> vs local-<filename> cho cùng 1 file)
        _books.value = withLocal.distinctBy { it.title.lowercase() }
        folderError == null
    }

    private fun scanLocalBooks(): List<BookMeta> {
        val out = mutableListOf<BookMeta>()
        FilePaths.pdfInbox(context).listFiles()?.forEach { f ->
            if (f.isFile) {
                val isEpub = f.extension.equals("epub", ignoreCase = true)
                val mime = if (isEpub) "application/epub+zip" else "application/pdf"
                out += BookMeta("local-${f.name}", f.name, "vi", "local", mime, sizeBytes = f.length(), localFile = f)
            }
        }
        FilePaths.audioBooks(context).walkTopDown().filter {
            it.isFile && (it.extension == "md" || it.extension == "pdf" || it.extension.equals("epub", ignoreCase = true))
        }.forEach { f ->
            val isMd = f.extension == "md"
            val isEpub = f.extension.equals("epub", ignoreCase = true)
            // EPUB đoán ngôn ngữ từ dc:language/text mẫu (EPUB VI trong audioBooks
            // vẫn về đúng module Kể chuyện thay vì kẹt ở tab EN)
            val lang = if (isMd) "vi" else if (isEpub) com.bibot.app.core.text.EpubReader.sniffLang(f) else "en"
            val mime = if (isMd) "text/markdown" else if (isEpub) "application/epub+zip" else "application/pdf"
            out += BookMeta("local-${f.name}", f.name, lang, "local", mime, sizeBytes = f.length(), localFile = f)
        }
        return out
    }

    suspend fun download(meta: BookMeta, onProgress: (Int) -> Unit = {}): File = when (meta.source) {
        "demo" -> assetsSource.download(meta, onProgress)
        "drive" -> driveSource.download(meta, onProgress)
        "pdrive" -> publicDriveSource.download(meta, onProgress)
        "url" -> urlSource.download(meta, onProgress)
        else -> assetsSource.download(meta, onProgress)
    }

    /**
     * Tải từ URL user dán: link Drive file (`/file/d/<ID>`, `?id=<ID>`) đi thẳng
     * PublicDriveSource (lấy tên thật từ header khi tải); link folder → báo lỗi rõ;
     * link http thường → PublicUrlSource.
     */
    suspend fun downloadFromUrl(url: String, onProgress: (Int) -> Unit = {}): File {
        val u = url.trim()
        if (PublicDriveSource.isFolderUrl(u)) throw IllegalArgumentException(
            "Đây là link folder — hãy dùng mục Folder Drive trong Cài đặt, hoặc lấy link từng file (…/file/d/…)"
        )
        val driveId = PublicDriveSource.extractFileId(u)
        val file = if (driveId != null) {
            val seg = u.substringAfterLast('/').substringBefore('?')
            val title = if (seg.isNotBlank() && seg != "view" && seg != "edit" && seg != "open") seg else driveId
            download(
                BookMeta(
                    id = "pdrive-$driveId",
                    title = title,
                    // Module "Sách tiếng Anh" dán link → mặc định sách EN (giọng đọc en-US);
                    // folder VI không nhận item này (filter theo lang + source)
                    lang = "en",
                    source = "pdrive",
                    mimeType = "application/octet-stream",
                    remoteUrl = driveId
                ),
                onProgress
            )
        } else {
            urlSource.downloadFromUrl(u, onProgress = onProgress)
        }
        refreshAll()
        return file
    }

    suspend fun seedDemo() = assetsSource.seedDemoIfNeeded()

    suspend fun delete(meta: BookMeta) = withContext(Dispatchers.IO) {
        // Delete local file
        meta.localFile?.delete()
        // Also check other common locations
        val dirs = listOf(
            FilePaths.pdfInbox(context),
            FilePaths.audioBooks(context),
            File(FilePaths.audioBooks(context), "en")
        )
        dirs.forEach { dir ->
            File(dir, meta.title).delete()
            File(dir, "en/${meta.title}").delete()
        }
    }

    fun localBooks(): List<File> = scanLocalBooks().mapNotNull { it.localFile }

    /**
     * 2.4 Lazy Block Loading — đọc file theo block 32KB, không giữ cả file trong RAM.
     * Port Pi book_reader.py:60 iter_book_sentences yield + text_cleaner.py preprocess.
     * Mọi file md/txt/pdf/epub đều qua TextCleaner/PdfTextCleaner trước khi chunk (2.5).
     * PDF: stream block 64KB + overlap 4KB (không readBytes toàn file → hết OOM/đơ).
     * EPUB: stream từng spine item (không giữ cả sách).
     * Dùng flowOn(IO) để không block Main — chống đơ khi mở pdf/md 5 MB (2.4).
     */
    fun iterSentences(meta: BookMeta, blockSizeKb: Int = 32): Flow<String> = flow {
        val file = meta.localFile ?: download(meta)
        if (!file.exists()) return@flow
        // EPUB: text từng spine item → clean → chunk
        if (file.extension.equals("epub", true)) {
            for (blk in com.bibot.app.core.text.EpubReader.iterText(file)) {
                val cleaned = TextCleaner.cleanForTts(PdfTextCleaner.cleanPdfText(blk))
                for (s in VietnameseChunker.split(cleaned)) if (s.isNotBlank()) emit(s)
            }
            return@flow
        }
        // PDF: trích `(text) Tj` streaming theo block → clean → chunk
        if (file.extension.equals("pdf", true)) {
            for (blk in BlockTextReader.iterPdfText(file)) {
                val cleaned = TextCleaner.cleanForTts(PdfTextCleaner.cleanPdfText(blk))
                for (s in VietnameseChunker.split(cleaned)) if (s.isNotBlank()) emit(s)
            }
            return@flow
        }
        // MD/TXT: stream khối đã clean (BlockTextReader giữ carry giữa 2 khối)
        for (blk in BlockTextReader.iterCleanedBlocks(file)) {
            for (s in VietnameseChunker.split(blk)) if (s.isNotBlank()) emit(s)
        }
    }.flowOn(Dispatchers.IO)
}
