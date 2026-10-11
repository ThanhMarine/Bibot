package com.bibot.app.data.books

import android.content.Context
import android.content.Intent
import com.bibot.app.data.store.BibotPreferences
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File as JFile

/**
 * Bản demo (apk_demo): Drive VÔ HIỆU HOÁ — cùng API với bản commercial để main compile được cả 2 flavor.
 * Mọi hàm Drive trả về rỗng/false/ném lỗi thân thiện; không pull google-drive/google-api-client (commercialImplementation).
 */
class DriveBookSource(private val context: Context) {

    fun isAvailable(context: Context): Boolean = false // demo luôn tắt Drive

    fun getSignInIntent(folderId: String? = null): Intent =
        throw UnsupportedOperationException("Bản Demo không hỗ trợ Drive — dùng bản thương mại.")

    suspend fun list(folderId: String): List<BookMeta> = emptyList()

    suspend fun list(): List<BookMeta> = emptyList()

    suspend fun listWithFolderCheck(): List<BookMeta> = emptyList()

    suspend fun download(meta: BookMeta, onProgress: (Int) -> Unit): JFile = withContext(Dispatchers.IO) {
        throw UnsupportedOperationException("Bản Demo không hỗ trợ tải từ Drive.")
    }

    suspend fun getChanges(startPageToken: String? = null): Pair<String?, List<BookMeta>> = Pair(null, emptyList())

    // Giữ API parity với commercial (DataStore, không phải SharedPreferences cũ)
    private val prefs by lazy { BibotPreferences(context) }

    suspend fun getSavedPageToken(): String? = null
    suspend fun savePageToken(token: String) { /* no-op demo */ }

    suspend fun saveFolderId(folderId: String) { prefs.setDriveFolderId(folderId) }
    suspend fun getFolderId(): String? = prefs.driveFolderIdFlow.first()

    companion object {
        fun isDriveEnabled(): Boolean = false
    }
}
