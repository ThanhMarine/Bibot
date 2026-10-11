package com.bibot.app.feature.doc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.bibot.app.R
import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.books.BookRepository
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocBrowserScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    // 9.4: search debounced 300ms — không filter mỗi keystroke trên Main
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) { delay(300); debouncedQuery = searchQuery }
    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }
    var storageFiles by remember { mutableStateOf<List<DocFileItem>>(emptyList()) }
    var bookstoreFiles by remember { mutableStateOf<List<BookMeta>>(emptyList()) }
    var isLoadingStorage by remember { mutableStateOf(false) }
    var isLoadingBookstore by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Chọn thư mục để duyệt — SAF không cần permission.") }
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }

    val docRepo = remember { BookRepository(context) }

    suspend fun refreshBookstore() = withContext(Dispatchers.IO) {
        isLoadingBookstore = true
        try {
            docRepo.refreshAll(selectedTreeUri?.lastPathSegment)
            bookstoreFiles = docRepo.books.value
            status = context.getString(R.string.doc_status, bookstoreFiles.size, storageFiles.size)
        } catch (e: Exception) {
            status = context.getString(R.string.doc_refresh_fail, e.message ?: "")
        }
        isLoadingBookstore = false
    }

    fun getExtension(name: String): String = name.substringAfterLast(".", "")
    fun getMimeType(name: String): String {
        val ext = getExtension(name)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    fun scanDir(dir: File, acc: MutableList<DocFileItem>) {
        try {
            dir.listFiles()?.take(50)?.forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                acc += DocFileItem(name = f.name, path = f.absolutePath,
                    isDirectory = f.isDirectory, mimeType = if (f.isDirectory) "*/*" else getMimeType(f.name),
                    sizeBytes = if (f.isDirectory) 0L else f.length(), documentFile = null)
                if (f.isDirectory && acc.size < 200) scanDir(f, acc)
            }
        } catch (_: Exception) {}
    }

    suspend fun scanTree(treeUri: Uri, ctx: Context) = withContext(Dispatchers.IO) {
        isLoadingStorage = true
        try {
            val tree = DocumentFile.fromTreeUri(ctx, treeUri)
            val files = mutableListOf<DocFileItem>()
            tree?.listFiles()?.forEach { doc ->
                val name = doc.name ?: return@forEach
                val mimeType = doc.type ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(getExtension(name)) ?: "*/*"
                val size = if (doc.isDirectory) 0L else doc.length()
                files += DocFileItem(name = name, path = doc.uri.toString(),
                    isDirectory = doc.isDirectory, mimeType = mimeType, sizeBytes = size, documentFile = doc)
            }
            val sorted = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            storageFiles = sorted
            status = context.getString(R.string.doc_scanned_tree, sorted.size, treeUri.lastPathSegment ?: "")
        } catch (e: Exception) { status = context.getString(R.string.doc_scan_fail, e.message ?: "") }
        isLoadingStorage = false
    }

    suspend fun scanStorage() = withContext(Dispatchers.IO) {
        isLoadingStorage = true
        try {
            val files = mutableListOf<DocFileItem>()
            FilePaths.audioBooks(context).walkTopDown().filter { it.isFile }.forEach { f ->
                files += DocFileItem(name = f.name, path = f.absolutePath,
                    isDirectory = false, mimeType = getMimeType(f.name), sizeBytes = f.length(), documentFile = null)
            }
            val extDir = ContextCompat.getExternalFilesDirs(context, null).firstOrNull()
            extDir?.let { base -> scanDir(base, files) }
            storageFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            status = context.getString(R.string.doc_scanned_storage, files.size)
        } catch (e: Exception) { status = context.getString(R.string.doc_scan_fail, e.message ?: "") }
        isLoadingStorage = false
    }

    suspend fun addToBookstore(uri: Uri): String = withContext(Dispatchers.IO) {
        val destName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
        val dest = File(FilePaths.audioBooks(context), destName)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        refreshBookstore()
        context.getString(R.string.doc_added, destName)
    }

    suspend fun removeFromBookstore(bookMeta: BookMeta): String = withContext(Dispatchers.IO) {
        val file = bookMeta.localFile ?: File(FilePaths.audioBooks(context), bookMeta.title)
        if (file.exists()) file.delete()
        refreshBookstore()
        context.getString(R.string.doc_removed, bookMeta.title)
    }

    fun isInBookstore(docFile: DocFileItem): Boolean {
        return bookstoreFiles.any { it.title == docFile.name || it.localFile?.name == docFile.name }
    }

    // Collect bookstore books from BookRepository StateFlow
    LaunchedEffect(Unit) {
        docRepo.seedDemo()
        refreshBookstore()
    }

    // SAF: Choose a directory tree
    val treePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            selectedTreeUri = uri
            status = context.getString(R.string.doc_granted, uri.lastPathSegment ?: "root")
            scope.launch { scanTree(uri, context) }
        }
    }

    // SAF: Pick file(s) to add to bookstore
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    status = context.getString(R.string.doc_adding)
                    val destName = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
                    val dest = File(FilePaths.audioBooks(context), destName)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out) }
                    }
                    refreshBookstore()
                    status = context.getString(R.string.doc_added_to_books, destName)
                } catch (e: Exception) {
                    status = context.getString(R.string.doc_add_fail, e.message ?: "")
                }
            }
        }
    }

    // Permission for legacy storage
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) { status = context.getString(R.string.doc_perm_granted_scan); scope.launch { scanStorage() } }
        else { status = context.getString(R.string.doc_need_perm) }
    }

    fun requestLegacyPermission() {
        permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val filteredStorage = if (debouncedQuery.isBlank()) storageFiles
    else storageFiles.filter { it.name.contains(debouncedQuery, ignoreCase = true) || it.mimeType.contains(debouncedQuery, ignoreCase = true) }
    val storageDirs = filteredStorage.filter { it.isDirectory }.take(20)
    val storageFilesList = filteredStorage.filter { !it.isDirectory }.take(50)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("📂 Document Browser", style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.doc_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
        }

        // Action bar
        item {
            OutlinedTextField(
                value = searchQuery, onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.doc_search_storage)) },
                placeholder = { Text(stringResource(R.string.doc_search_hint)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                Button(onClick = { treePicker.launch(null) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.doc_folder_saf), fontSize = 12.sp)
                }
                OutlinedButton(onClick = { filePicker.launch("*/*") }) { Text(stringResource(R.string.doc_pick_file), fontSize = 12.sp) }
                OutlinedButton(onClick = { requestLegacyPermission() }) { Text("Permission", fontSize = 12.sp) }
                OutlinedButton(onClick = { scope.launch { refreshBookstore() } }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.common_refresh), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("📁 " + (if (selectedTreeUri != null) "SAF: ${selectedTreeUri?.lastPathSegment}" else stringResource(R.string.doc_no_folder)), style = MaterialTheme.typography.labelSmall)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Divider()
        }

        // BOOKSTORE section
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.doc_books_title, bookstoreFiles.size), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (isLoadingBookstore) LinearProgressIndicator(modifier = Modifier.weight(0.3f))
                TextButton(onClick = { scope.launch { refreshBookstore() } }) {
                    Text(stringResource(R.string.common_refresh), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (bookstoreFiles.isEmpty() && !isLoadingBookstore) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.doc_books_empty), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        items(bookstoreFiles.take(30), key = { it.id }) { book ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (book.lang == "vi") "📖" else "📚", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text("${book.source} • ${book.lang} • ${book.displaySize}", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                        Text(if (book.isDownloaded) stringResource(R.string.doc_book_dl) else stringResource(R.string.doc_book_not), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp)
                    }
                    TextButton(onClick = { scope.launch { status = removeFromBookstore(book) } }) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(stringResource(R.string.doc_delete), fontSize = 11.sp)
                    }
                }
            }
        }

        if (bookstoreFiles.size > 30) {
            item { Text(stringResource(R.string.doc_more, bookstoreFiles.size - 30), style = MaterialTheme.typography.labelSmall) }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Divider()
        }

        // STORAGE section
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.doc_storage_title, storageFiles.size), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (isLoadingStorage) LinearProgressIndicator(modifier = Modifier.weight(0.3f))
            }
            Spacer(Modifier.height(4.dp))
        }

        if (storageDirs.isNotEmpty()) {
            item { Text(stringResource(R.string.doc_dirs, storageDirs.size), style = MaterialTheme.typography.labelMedium) }
            items(storageDirs, key = { it.path }) { doc ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(doc.name, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("${doc.sizeBytes / 1024} KB", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                        }
                        TextButton(onClick = { scope.launch { scanTree(doc.documentFile?.uri ?: Uri.parse(doc.path), context) } }) { Text(stringResource(R.string.doc_open), fontSize = 12.sp) }
                    }
                }
            }
        }

        if (storageFilesList.isNotEmpty()) {
            item { Text(stringResource(R.string.doc_files, storageFilesList.size), style = MaterialTheme.typography.labelMedium) }
            items(storageFilesList, key = { it.path }) { doc ->
                val inBookstore = isInBookstore(doc)
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (inBookstore) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(doc.name, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("${doc.sizeBytes / 1024} KB • ${doc.mimeType}", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1)
                            if (inBookstore) Text(stringResource(R.string.doc_in_books), style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            if (!inBookstore) {
                                TextButton(onClick = { scope.launch { status = addToBookstore(Uri.parse(doc.path)) } }) { Text(stringResource(R.string.doc_add_to_books), fontSize = 11.sp) }
                            } else {
                                Text("✅", fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        if (filteredStorage.isEmpty() && !isLoadingStorage && storageFiles.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.doc_no_search, searchQuery), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (storageFiles.isEmpty() && !isLoadingStorage && selectedTreeUri == null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.doc_browse_hint), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text("Hoặc cấp READ_EXTERNAL_STORAGE permission.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            Divider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📖 Thêm file → Sách → 📚/📖 tự động cập nhật khi refresh.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

data class DocFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val mimeType: String,
    val sizeBytes: Long,
    val documentFile: DocumentFile?
)

fun getFileName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
        } else null
    }
}
