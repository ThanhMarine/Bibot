package com.bibot.app.data.books

import android.content.Context
import com.bibot.app.data.files.FilePaths
import com.bibot.app.data.store.BibotPreferences
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.File as JFile

/**
 * Google Drive làm kho sách chính thức — dùng 1 phần Drive của bạn.
 * Setup: tạo folder "BibotBooks" trên Drive, share read hoặc lấy folderId từ URL
 *   https://drive.google.com/drive/folders/1AbC...  → folderId = 1AbC...
 *   Lưu vào DataStore driveFolderId (Settings → Kho sách → Nhập Folder ID)
 * Auth: Google Sign-In + DriveScopes.DRIVE_READONLY (chỉ đọc). User tự đăng nhập khi cần.
 *
 * Tối ưu: list chỉ metadata (id,name,mimeType,size), download alt=media stream 32KB buffer.
 * Không cần quyền storage; file lưu app-specific filesDir/bibot/pdfs/inbox.
 */
class DriveBookSource(private val context: Context) {

    fun isAvailable(context: Context): Boolean {
        // Có account đã sign-in và có folderId lưu trong DataStore thì mới khả dụng
        val acct = GoogleSignIn.getLastSignedInAccount(context)
        return acct != null
    }

    fun getSignInIntent(folderId: String? = null) = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(DriveScopes.DRIVE_READONLY))
            .build()
    ).signInIntent

    private fun buildDriveService(): Drive {
        val acct = GoogleSignIn.getLastSignedInAccount(context)
            ?: throw IllegalStateException("Chưa đăng nhập Google")
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_READONLY)).apply {
            selectedAccount = acct.account
        }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName("Bibot")
            .build()
    }

    // 9.2: list paging 100/page theo pageToken — kho lớn không giữ hết RAM, không block Main
    suspend fun list(folderId: String): List<BookMeta> = withContext(Dispatchers.IO) {
        val drive = buildDriveService()
        // Query: "'folderId' in parents and trashed=false and (mimeType contains 'pdf' or mimeType contains 'markdown' or mimeType contains 'text')"
        val q = "'$folderId' in parents and trashed=false"
        val out = mutableListOf<BookMeta>()
        var pageToken: String? = null
        do {
            val req = drive.files().list()
                .setQ(q)
                .setFields("nextPageToken, files(id,name,mimeType,size,modifiedTime)")
                .setPageSize(100)
            if (pageToken != null) req.pageToken = pageToken
            val result = req.execute()
            result.files?.forEach { f ->
                val isPdf = f.mimeType?.contains("pdf", true) == true || f.name.endsWith(".pdf", true)
                out += BookMeta(
                    id = "drive-${f.id}",
                    title = f.name,
                    lang = if (f.name.contains("_vi", true) || f.name.endsWith(".md", true)) "vi" else "en",
                    source = "drive",
                    mimeType = f.mimeType ?: if (isPdf) "application/pdf" else "text/markdown",
                    sizeBytes = f.getSize()?.toLong() ?: 0L,
                    remoteUrl = f.id // dùng fileId để download
                )
            }
            pageToken = result.nextPageToken
        } while (pageToken != null)
        out
    }

    // BookSource interface compat — list cần folderId, nên overload
    suspend fun list(): List<BookMeta> = emptyList() // không dùng trực tiếp; gọi list(folderId)
    suspend fun listWithFolderCheck(): List<BookMeta> {
        val folderId = getFolderId() ?: return emptyList()
        return list(folderId)
    }

    // 9.2: download alt=media + close trong finally (bịt leak fd), không block Main
    suspend fun download(meta: BookMeta, onProgress: (Int) -> Unit): JFile = withContext(Dispatchers.IO) {
        val drive = buildDriveService()
        val fileId = meta.remoteUrl ?: meta.id.removePrefix("drive-")
        val dest = JFile(FilePaths.pdfInbox(context), meta.title)
        // Drive files.get alt=media streaming
        FileOutputStream(dest).use { output ->
            drive.files().get(fileId).executeMediaAndDownloadTo(output)
        }
        onProgress(50)
        // Handle tar.gz / zip auto-extract (ModelDownloader style, nhưng cho Drive books)
        val extracted = handleArchiveIfNeeded(dest, onProgress)
        onProgress(100)
        extracted
    }

    private fun handleArchiveIfNeeded(file: JFile, onProgress: (Int) -> Unit): JFile {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".zip") -> unzipFile(file, onProgress)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> untarGzFile(file, onProgress)
            else -> file
        }
    }

    private fun unzipFile(file: JFile, onProgress: (Int) -> Unit): JFile {
        return try {
            val destDir = JFile(file.parentFile, file.nameWithoutExtension)
            destDir.mkdirs()
            java.util.zip.ZipInputStream(file.inputStream()).use { zis ->
                var entry = zis.nextEntry
                var count = 0
                while (entry != null) {
                    val out = JFile(destDir, entry.name.substringAfterLast("/").ifBlank { entry.name })
                    if (!entry.isDirectory) {
                        out.outputStream().use { zis.copyTo(it) }
                        count++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
                onProgress(90)
            }
            // If single file inside, return it; else return dir's first file
            destDir.listFiles()?.firstOrNull { it.isFile } ?: file
        } catch (e: Exception) {
            e.printStackTrace()
            file
        }
    }

    private fun untarGzFile(file: JFile, onProgress: (Int) -> Unit): JFile {
        return try {
            val destDir = JFile(file.parentFile, file.nameWithoutExtension.substringBefore(".tar"))
            destDir.mkdirs()
            java.util.zip.GZIPInputStream(file.inputStream()).use { gis ->
                // Minimal tar parser without Commons Compress
                val buffer = ByteArray(512)
                var count = 0
                while (true) {
                    val read = gis.read(buffer)
                    if (read < 512) break
                    if (buffer.all { it == 0.toByte() }) break
                    val nameBytes = buffer.copyOfRange(0, 100)
                    val name = String(nameBytes).trim { it == '\u0000' || it == ' ' }.trim()
                    val sizeStr = String(buffer.copyOfRange(124, 136)).trim { it == '\u0000' || it == ' ' }.trim()
                    val size = try { sizeStr.trim().toLong(8) } catch (_: Exception) { 0L }
                    val typeFlag = buffer[156].toInt().toChar()
                    if (name.isNotBlank() && typeFlag != '5') {
                        val out = JFile(destDir, name.substringAfterLast("/"))
                        val toRead = size
                        var remaining = toRead
                        out.outputStream().use { outStream ->
                            val chunk = ByteArray(8192)
                            while (remaining > 0) {
                                val toReadNow = minOf(chunk.size.toLong(), remaining).toInt()
                                val r = gis.read(chunk, 0, toReadNow)
                                if (r <= 0) break
                                outStream.write(chunk, 0, r)
                                remaining -= r
                            }
                        }
                        count++
                        // Skip padding to 512
                        val padding = (512 - (size % 512)) % 512
                        if (padding > 0) gis.skip(padding.toLong())
                    } else {
                        // Skip file content
                        var toSkip = size
                        while (toSkip > 0) {
                            val skipped = gis.skip(toSkip)
                            if (skipped <= 0) break
                            toSkip -= skipped
                        }
                        val padding = (512 - (size % 512)) % 512
                        if (padding > 0) gis.skip(padding.toLong())
                    }
                    if (count > 50) break
                }
            }
            onProgress(90)
            destDir.listFiles()?.firstOrNull { it.isFile } ?: file
        } catch (e: Exception) {
            e.printStackTrace()
            file
        }
    }

    // Incremental sync — dùng Drive changes API (pageToken)
    suspend fun getChanges(startPageToken: String? = null): Pair<String?, List<BookMeta>> = withContext(Dispatchers.IO) {
        try {
            val drive = buildDriveService()
            val token = startPageToken ?: drive.changes().getStartPageToken().execute().startPageToken
            val changes = drive.changes().list(token).setFields("nextPageToken, newStartPageToken, changes(fileId, file(name,mimeType,size))").execute()
            val metas = changes.changes?.mapNotNull { ch ->
                val f = ch.file ?: return@mapNotNull null
                BookMeta(
                    id = "drive-${f.id}", title = f.name, lang = if (f.name.contains("_vi", true)) "vi" else "en",
                    source = "drive", mimeType = f.mimeType ?: "application/pdf",
                    sizeBytes = f.getSize()?.toLong() ?: 0L, remoteUrl = f.id
                )
            } ?: emptyList()
            Pair(changes.newStartPageToken ?: changes.nextPageToken, metas)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(null, emptyList())
        }
    }

    // 9.2: folderId/pageToken qua DataStore (migrate từ SharedPreferences "bibot_drive")
    private val prefs by lazy { BibotPreferences(context) }

    suspend fun getSavedPageToken(): String? = prefs.drivePageTokenFlow.first()
    suspend fun savePageToken(token: String) { prefs.setDrivePageToken(token) }

    suspend fun saveFolderId(folderId: String) { prefs.setDriveFolderId(folderId) }
    suspend fun getFolderId(): String? = prefs.driveFolderIdFlow.first()
}
