package com.bibot.app.data.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.bibot.app.R
import com.bibot.app.core.text.EpubReader
import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.books.BookRepository
import com.bibot.app.data.db.BibotDatabase
import com.bibot.app.data.db.BookProgress
import com.bibot.app.data.db.LibraryMeta
import com.bibot.app.data.files.FilePaths
import com.bibot.app.feature.library.LibraryCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 1 file trong storage browser (SAF tree hoặc thư mục local). */
data class DocFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val sizeBytes: Long,
    val documentFile: DocumentFile?
)

/** Snapshot kệ sau refresh (UI tự ráp status string). */
data class LibrarySnapshot(
    val books: List<BookMeta>,
    val metas: Map<String, LibraryMeta>,
    val progress: Map<String, BookProgress>
)

/**
 * Module quản lý thư viện (tách từ DocBrowserScreen — tab Documents đã gỡ khỏi
 * nav, giữ lại làm core cho Shell manager sau này). Không Compose: Shell/CLI
 * gọi trực tiếp các suspend fun này.
 */
class LibraryManager(private val context: Context) {
    private val repo = BookRepository(context)
    private val db = BibotDatabase.get(context)

    val books: StateFlow<List<BookMeta>> = repo.books

    suspend fun seedDemo() = repo.seedDemo()

    suspend fun refreshAll(driveFolderId: String? = null): Boolean =
        repo.refreshAll(driveFolderId)

    suspend fun download(meta: BookMeta) = repo.download(meta)

    /** Tải + đánh dấu kệ (đồng hồ) — đúng luồng nút Download cũ. */
    suspend fun downloadAndTrack(meta: BookMeta) = withContext(Dispatchers.IO) {
        repo.download(meta)
        db.library().insertIfMissing(LibraryMeta(bookId = meta.id))
        db.library().stamp(meta.id, System.currentTimeMillis())
    }

    suspend fun deleteLocal(meta: BookMeta) = repo.delete(meta)

    suspend fun metas(): Map<String, LibraryMeta> = withContext(Dispatchers.IO) {
        db.library().all().associateBy { it.bookId }
    }

    suspend fun progressMap(): Map<String, BookProgress> = withContext(Dispatchers.IO) {
        db.book().all().associateBy { it.bookId }
    }

    /** Refresh full kệ + backfill tác giả EPUB (cap 30/lần, rẻ: chỉ OPF). */
    suspend fun refreshSnapshot(driveFolderId: String? = null): LibrarySnapshot =
        withContext(Dispatchers.IO) {
            repo.refreshAll(driveFolderId)
            val books = repo.books.value
            backfillAuthors(books)
            LibrarySnapshot(
                books,
                db.library().all().associateBy { it.bookId },
                db.book().all().associateBy { it.bookId }
            )
        }

    suspend fun backfillAuthors(books: List<BookMeta>): Int = withContext(Dispatchers.IO) {
        var n = 0
        try {
            val metas = db.library().all().associateBy { it.bookId }
            for (b in books) {
                if (n >= 30) break
                val f = b.localFile?.takeIf { it.exists() } ?: continue
                if (!f.extension.equals("epub", ignoreCase = true)) continue
                if (!metas[b.id]?.author.isNullOrBlank()) continue
                val m = EpubReader.readMeta(f)
                if (m.author.isNotBlank() || m.series.isNotBlank()) {
                    db.library().insertIfMissing(LibraryMeta(bookId = b.id))
                    db.library().setMeta(b.id, m.author, m.series)
                    n++
                }
            }
        } catch (_: Exception) {}
        n
    }

    suspend fun setFav(id: String, v: Boolean) = withContext(Dispatchers.IO) {
        db.library().insertIfMissing(LibraryMeta(bookId = id))
        db.library().setFav(id, v)
    }

    suspend fun setToRead(id: String, v: Boolean) = withContext(Dispatchers.IO) {
        db.library().insertIfMissing(LibraryMeta(bookId = id))
        db.library().setToRead(id, v)
    }

    suspend fun setRead(id: String, v: Boolean) = withContext(Dispatchers.IO) {
        db.library().insertIfMissing(LibraryMeta(bookId = id))
        db.library().setRead(id, v)
    }

    /** Xoá mềm vào thùng rác (ẩn ở library, giữ file). */
    suspend fun trashBook(id: String) = withContext(Dispatchers.IO) {
        db.library().insertIfMissing(LibraryMeta(bookId = id))
        db.library().setTrashed(id, true)
    }

    suspend fun restoreBook(id: String) = withContext(Dispatchers.IO) {
        db.library().setTrashed(id, false)
    }

    /** Xoá hẳn (file + meta). */
    suspend fun deleteForever(meta: BookMeta) = withContext(Dispatchers.IO) {
        try { repo.delete(meta) } catch (_: Exception) {}
        try { db.library().delete(meta.id) } catch (_: Exception) {}
    }

    /** Bìa sách (PDF render / EPUB cover / null → UI vẽ placeholder). */
    suspend fun coverFor(book: BookMeta): Bitmap? = LibraryCovers.coverFor(book)

    fun getExtension(name: String): String = name.substringAfterLast(".", "")

    fun getMimeType(name: String): String {
        val ext = getExtension(name)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    fun getFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }

    fun scanDir(dir: File, acc: MutableList<DocFileItem>) {
        try {
            dir.listFiles()?.take(50)?.forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                acc += DocFileItem(
                    name = f.name, path = f.absolutePath,
                    isDirectory = f.isDirectory, mimeType = if (f.isDirectory) "*/*" else getMimeType(f.name),
                    sizeBytes = if (f.isDirectory) 0L else f.length(), documentFile = null
                )
                if (f.isDirectory && acc.size < 200) scanDir(f, acc)
            }
        } catch (_: Exception) {}
    }

    suspend fun scanTree(treeUri: Uri): List<DocFileItem> = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
        val files = mutableListOf<DocFileItem>()
        tree?.listFiles()?.forEach { doc ->
            val name = doc.name ?: return@forEach
            val mimeType = doc.type ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(getExtension(name)) ?: "*/*"
            val size = if (doc.isDirectory) 0L else doc.length()
            files += DocFileItem(
                name = name, path = doc.uri.toString(),
                isDirectory = doc.isDirectory, mimeType = mimeType, sizeBytes = size, documentFile = doc
            )
        }
        files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    suspend fun scanStorage(): List<DocFileItem> = withContext(Dispatchers.IO) {
        val files = mutableListOf<DocFileItem>()
        FilePaths.audioBooks(context).walkTopDown().filter { it.isFile }.forEach { f ->
            files += DocFileItem(
                name = f.name, path = f.absolutePath,
                isDirectory = false, mimeType = getMimeType(f.name), sizeBytes = f.length(), documentFile = null
            )
        }
        val extDir = ContextCompat.getExternalFilesDirs(context, null).firstOrNull()
        extDir?.let { base -> scanDir(base, files) }
        files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** Copy file từ SAF Uri vào kệ → tên file đích (throw khi lỗi, caller báo). */
    suspend fun importFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val destName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
        val dest = File(FilePaths.audioBooks(context), destName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        destName
    }

    fun statusDocCount(books: Int, storage: Int): String =
        context.getString(R.string.doc_status, books, storage)
}
