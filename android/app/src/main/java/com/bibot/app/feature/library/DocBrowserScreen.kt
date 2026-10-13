package com.bibot.app.feature.library

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bibot.app.R
import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.db.BookProgress
import com.bibot.app.data.db.LibraryMeta
import com.bibot.app.data.library.DocFileItem
import com.bibot.app.data.library.LibFilter
import com.bibot.app.data.library.LibGroup
import com.bibot.app.data.library.LibGroupKind
import com.bibot.app.data.library.LibSel
import com.bibot.app.data.library.LibraryManager
import com.bibot.app.data.library.LibraryQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * B1 — Thư viện kiểu ReadEra.
 * - Drawer 12 mục: Đang đọc / Sách & tài liệu / Yêu thích / Đọc sau / Đã đọc /
 *   Tuyển tập / Tác giả / Series / Định dạng / Thư mục / Tải về / Thùng rác.
 * - Row: bìa (PDF render / EPUB cover / ô chữ) + tựa/tác giả/format-size +
 *   progress bar + 4 action (Tải/Yêu thích/Đọc sau/⋮) — quản lý không cần mở sách.
 * - FAB + (SAF picker giữ nguyên) + search debounce + empty state hướng dẫn +
 *   full list (bỏ take(30) cắt ngầm) + auto-scan khi đã có quyền.
 * - PDF nằm chung grid (lọc Định dạng > PDF), hết mồ côi.
 * - Thùng rác mềm: chỉ ẩn ở library (reader tabs không đổi — ghi nhận B1.1).
 *
 * TÁCH TAB (quyết user): gỡ khỏi nav (trùng kệ đọc + không mở được sách);
 * logic quản lý đã wire vào core `data.library` ([LibraryManager] +
 * [LibraryQuery]) để Shell manager dùng chung — màn này giữ làm UI tham chiếu,
 * mọi lọc/thao tác đi qua core (không logic riêng).
 */

@Composable
fun libFolderLabel(source: String): String = when (source) {
    "pdrive", "drive" -> stringResource(R.string.lib_src_pdrive)
    "local", "demo" -> stringResource(R.string.lib_src_local)
    "url" -> stringResource(R.string.lib_src_url)
    else -> stringResource(R.string.lib_src_other)
}

@Composable
fun libCollectionLabel(collection: String, lang: String): String = when (collection) {
    "story" -> stringResource(R.string.lib_col_story)
    "cotich" -> stringResource(R.string.lib_col_cotich)
    "en" -> stringResource(R.string.lib_col_en)
    "thamkhao" -> stringResource(R.string.lib_col_thamkhao)
    else -> if (lang == "en") stringResource(R.string.lib_col_en)
    else stringResource(R.string.lib_col_local)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocBrowserScreen(
    modifier: Modifier = Modifier,
    // B6.1: back về Home (chrome app ẩn trong mode đọc nên thư viện tự giữ nút back).
    onBackHome: () -> Unit = {}
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
    var metas by remember { mutableStateOf<Map<String, LibraryMeta>>(emptyMap()) }
    var progressMap by remember { mutableStateOf<Map<String, BookProgress>>(emptyMap()) }
    var isLoadingStorage by remember { mutableStateOf(false) }
    var isLoadingBookstore by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Chọn thư mục để duyệt — SAF không cần permission.") }
    var sel by remember { mutableStateOf<LibSel>(LibSel.F(LibFilter.ALL)) }
    var expandedGroups by remember { mutableStateOf(setOf<LibGroupKind>()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Core quản lý (data.library) — UI chỉ giữ state hiển thị + status text.
    // Mọi lọc/thao tác đi qua lib (Shell manager dùng chung module này).
    val lib = remember { LibraryManager(context) }

    suspend fun refreshLibrary(silentScan: Boolean = false) {
        isLoadingBookstore = true
        try {
            val snap = lib.refreshSnapshot(selectedTreeUri?.lastPathSegment)
            bookstoreFiles = snap.books
            metas = snap.metas
            progressMap = snap.progress
            status = lib.statusDocCount(bookstoreFiles.size, storageFiles.size)
        } catch (e: Exception) {
            status = context.getString(R.string.doc_refresh_fail, e.message ?: "")
        }
        isLoadingBookstore = false
    }

    suspend fun scanTree(treeUri: Uri) {
        isLoadingStorage = true
        try {
            storageFiles = lib.scanTree(treeUri)
            status = context.getString(R.string.doc_scanned_tree, storageFiles.size, treeUri.lastPathSegment ?: "")
        } catch (e: Exception) { status = context.getString(R.string.doc_scan_fail, e.message ?: "") }
        isLoadingStorage = false
    }

    suspend fun scanStorage(silent: Boolean = false) {
        isLoadingStorage = true
        try {
            storageFiles = lib.scanStorage()
            status = if (silent) context.getString(R.string.lib_auto_scanned, storageFiles.size)
            else context.getString(R.string.doc_scanned_storage, storageFiles.size)
        } catch (e: Exception) { status = context.getString(R.string.doc_scan_fail, e.message ?: "") }
        isLoadingStorage = false
    }

    suspend fun addToBookstore(uri: Uri): String {
        val destName = lib.importFile(uri)
        refreshLibrary()
        return context.getString(R.string.doc_added, destName)
    }

    // B1.2: xoá mềm vào thùng rác (ẩn ở library, giữ file); xoá hẳn mới đụng file.
    suspend fun trashBook(bookMeta: BookMeta): String {
        lib.trashBook(bookMeta.id)
        metas = lib.metas()
        return context.getString(R.string.lib_moved_trash)
    }

    suspend fun restoreBook(bookMeta: BookMeta): String {
        lib.restoreBook(bookMeta.id)
        metas = lib.metas()
        return context.getString(R.string.lib_restored)
    }

    suspend fun deleteForever(bookMeta: BookMeta): String {
        lib.deleteForever(bookMeta)
        refreshLibrary()
        return context.getString(R.string.lib_deleted_forever)
    }

    fun isInBookstore(docFile: DocFileItem): Boolean {
        return bookstoreFiles.any { it.title == docFile.name || it.localFile?.name == docFile.name }
    }

    // Collect bookstore books from BookRepository StateFlow + auto-scan khi đã có quyền
    LaunchedEffect(Unit) {
        lib.seedDemo()
        refreshLibrary()
        try {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (granted && storageFiles.isEmpty()) scanStorage(silent = true)
        } catch (_: Exception) {}
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
            scope.launch { scanTree(uri) }
        }
    }

    // SAF: Pick file(s) to add to bookstore (FAB + dùng)
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    status = context.getString(R.string.doc_adding)
                    val destName = lib.importFile(uri)
                    refreshLibrary()
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

    // ---- B1.1: lọc theo kệ/drawer + search (trash ẩn mọi nơi trừ Thùng rác) ----
    // Đi qua core LibraryQuery (Shell manager dùng chung).
    fun isTrash(b: BookMeta) = LibraryQuery.isTrash(metas, b)
    val nonTrash = bookstoreFiles.filter { !isTrash(it) }
    val unknownLabel = stringResource(R.string.lib_unknown)
    val visibleBooks = LibraryQuery.selectBooks(
        bookstoreFiles, metas, progressMap, sel, debouncedQuery, unknownLabel
    )

    // Nhóm động cho drawer (đếm trên nonTrash)
    val authorGroups = nonTrash.map { metas[it.id]?.author?.ifBlank { null } ?: unknownLabel }
        .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.take(15)
    val seriesGroups = nonTrash.map { metas[it.id]?.series?.ifBlank { null } ?: unknownLabel }
        .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.take(15)
    val formatGroups = nonTrash.map { LibraryQuery.libFormatOf(it) }
        .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
    val folderGroups = nonTrash.map { LibraryQuery.folderKeyOf(it.source) }
        .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
    val collectionGroups = listOf("story", "cotich", "en", "thamkhao", "local").mapNotNull { k ->
        val g = LibGroup(LibGroupKind.COLLECTION, k, "")
        val n = nonTrash.count {
            LibraryQuery.matchGroup(it, metas[it.id]?.author, metas[it.id]?.series, g, unknownLabel)
        }
        if (n > 0) g to n else null
    }

    fun drawerCloseAndGo(next: LibSel) {
        sel = next
        menuFor = null
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.lib_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(8.dp))
                fun badge(n: Int): @Composable (() -> Unit)? =
                    if (n > 0) ({ Text("$n", style = MaterialTheme.typography.labelSmall) }) else null
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.lib_sec_reading)) },
                    selected = sel == LibSel.F(LibFilter.READING),
                    onClick = { drawerCloseAndGo(LibSel.F(LibFilter.READING)) },
                    badge = badge(nonTrash.count { b ->
                        progressMap[b.id]?.let { it.position > 0 && (it.total <= 0 || it.position < it.total) } == true
                    }),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.lib_sec_all)) },
                    selected = sel == LibSel.F(LibFilter.ALL),
                    onClick = { drawerCloseAndGo(LibSel.F(LibFilter.ALL)) },
                    badge = badge(nonTrash.size),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.lib_sec_fav)) },
                    selected = sel == LibSel.F(LibFilter.FAV),
                    onClick = { drawerCloseAndGo(LibSel.F(LibFilter.FAV)) },
                    badge = badge(nonTrash.count { metas[it.id]?.shelfFav == true }),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.lib_sec_toread)) },
                    selected = sel == LibSel.F(LibFilter.TOREAD),
                    onClick = { drawerCloseAndGo(LibSel.F(LibFilter.TOREAD)) },
                    badge = badge(nonTrash.count { metas[it.id]?.shelfToRead == true }),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.lib_sec_read)) },
                    selected = sel == LibSel.F(LibFilter.READ),
                    onClick = { drawerCloseAndGo(LibSel.F(LibFilter.READ)) },
                    badge = badge(nonTrash.count { b ->
                        metas[b.id]?.shelfRead == true ||
                            progressMap[b.id]?.let { it.total > 0 && it.position >= it.total } == true
                    }),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                // Nhóm mở rộng: Tuyển tập / Tác giả / Series / Định dạng / Thư mục
                @Composable
                fun groupHeader(kind: LibGroupKind, label: String, count: Int) {
                    NavigationDrawerItem(
                        label = { Text(label) },
                        selected = false,
                        onClick = {
                            expandedGroups = if (kind in expandedGroups) expandedGroups - kind
                            else expandedGroups + kind
                        },
                        badge = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (count > 0) Text("$count", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    if (kind in expandedGroups) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null, modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
                @Composable
                fun groupItem(g: LibGroup, n: Int) {
                    NavigationDrawerItem(
                        label = { Text(g.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        selected = sel == LibSel.G(g),
                        onClick = { drawerCloseAndGo(LibSel.G(g)) },
                        badge = badge(n),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                            .padding(start = 16.dp)
                    )
                }
                groupHeader(LibGroupKind.COLLECTION, stringResource(R.string.lib_sec_collection), collectionGroups.size)
                if (LibGroupKind.COLLECTION in expandedGroups) {
                    collectionGroups.forEach { (g, n) ->
                        val label = when (g.key) {
                            "story" -> stringResource(R.string.lib_col_story)
                            "cotich" -> stringResource(R.string.lib_col_cotich)
                            "en" -> stringResource(R.string.lib_col_en)
                            "thamkhao" -> stringResource(R.string.lib_col_thamkhao)
                            else -> stringResource(R.string.lib_col_local)
                        }
                        groupItem(g.copy(label = label), n)
                    }
                }
                groupHeader(LibGroupKind.AUTHOR, stringResource(R.string.lib_sec_author), authorGroups.size)
                if (LibGroupKind.AUTHOR in expandedGroups) {
                    authorGroups.forEach { (name, n) ->
                        groupItem(LibGroup(LibGroupKind.AUTHOR, name, name), n)
                    }
                }
                groupHeader(LibGroupKind.SERIES, stringResource(R.string.lib_sec_series), seriesGroups.size)
                if (LibGroupKind.SERIES in expandedGroups) {
                    seriesGroups.forEach { (name, n) ->
                        groupItem(LibGroup(LibGroupKind.SERIES, name, name), n)
                    }
                }
                groupHeader(LibGroupKind.FORMAT, stringResource(R.string.lib_sec_format), formatGroups.size)
                if (LibGroupKind.FORMAT in expandedGroups) {
                    // B1.4: PDF (và mọi định dạng) nằm chung grid, lọc 1 chạm.
                    formatGroups.forEach { (fmt, n) ->
                        groupItem(LibGroup(LibGroupKind.FORMAT, fmt, fmt), n)
                    }
                }
                groupHeader(LibGroupKind.FOLDER, stringResource(R.string.lib_sec_folder), folderGroups.size)
                if (LibGroupKind.FOLDER in expandedGroups) {
                    folderGroups.forEach { (key, n) ->
                        val label = when (key) {
                            "pdrive" -> stringResource(R.string.lib_src_pdrive)
                            "local" -> stringResource(R.string.lib_src_local)
                            "url" -> stringResource(R.string.lib_src_url)
                            else -> stringResource(R.string.lib_src_other)
                        }
                        groupItem(LibGroup(LibGroupKind.FOLDER, key, label), n)
                    }
                }
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.lib_sec_downloaded)) },
                    selected = sel == LibSel.F(LibFilter.DOWNLOADED),
                    onClick = { drawerCloseAndGo(LibSel.F(LibFilter.DOWNLOADED)) },
                    badge = badge(nonTrash.count { it.isDownloaded }),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.lib_sec_trash)) },
                    selected = sel == LibSel.F(LibFilter.TRASH),
                    onClick = { drawerCloseAndGo(LibSel.F(LibFilter.TRASH)) },
                    badge = badge(bookstoreFiles.count { LibraryQuery.isTrash(metas, it) }),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    ) {
        Scaffold(
            floatingActionButton = {
                // B1.3: FAB + mở SAF (giữ picker cũ).
                FloatingActionButton(onClick = { filePicker.launch("*/*") }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.lib_add_desc))
                }
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        IconButton(onClick = onBackHome, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.reader_back))
                        }
                        IconButton(onClick = { scope.launch { drawerState.open() } }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.lib_menu_desc))
                        }
                        Text(stringResource(R.string.lib_title), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(R.string.lib_count, visibleBooks.size),
                            style = MaterialTheme.typography.labelMedium
                        )
                        IconButton(onClick = { scope.launch { refreshLibrary() } }, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh))
                        }
                    }
                    if (isLoadingBookstore) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = searchQuery, onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.lib_search)) },
                        placeholder = { Text(stringResource(R.string.lib_search_hint)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                if (sel == LibSel.F(LibFilter.TRASH)) {
                    item {
                        Text(
                            stringResource(R.string.lib_trash_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (visibleBooks.isEmpty() && !isLoadingBookstore) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (debouncedQuery.isBlank()) stringResource(R.string.lib_empty)
                                    else stringResource(R.string.lib_no_search, debouncedQuery),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                // Empty state có hướng dẫn (hết màn trắng — B1.3).
                                TextButton(onClick = { filePicker.launch("*/*") }) {
                                    Text(stringResource(R.string.doc_pick_file), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // B1.2: row bìa + meta + progress + 4 action — full list (bỏ take(30)).
                items(visibleBooks, key = { it.id }) { book ->
                    val inTrash = isTrash(book)
                    val meta = metas[book.id]
                    val prog = progressMap[book.id]
                    val pct = if (prog != null && prog.total > 0)
                        (prog.position.toFloat() / prog.total).coerceIn(0f, 1f) else null
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LibraryCover(book = book)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        book.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        (meta?.author?.ifBlank { null } ?: "—") + " • " +
                                            LibraryQuery.libFormatOf(book) + " • " + book.displaySize,
                                        style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        (if (book.isDownloaded) stringResource(R.string.book_is_downloaded)
                                        else stringResource(R.string.book_not_downloaded)) +
                                            " • " + book.source + " • " + book.lang.uppercase(),
                                        style = MaterialTheme.typography.labelSmall, fontSize = 10.sp
                                    )
                                }
                            }
                            if (pct != null) {
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { pct },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            if (inTrash) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilledTonalButton(onClick = {
                                        scope.launch(Dispatchers.IO) { status = restoreBook(book) }
                                    }) {
                                        Icon(Icons.Default.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.lib_act_restore), fontSize = 12.sp)
                                    }
                                    TextButton(onClick = {
                                        scope.launch(Dispatchers.IO) { status = deleteForever(book) }
                                    }) {
                                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.lib_act_delforever), fontSize = 12.sp)
                                    }
                                }
                            } else {
                                val fav = meta?.shelfFav == true
                                val toRead = meta?.shelfToRead == true
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (!book.isDownloaded) {
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        lib.downloadAndTrack(book)
                                                        refreshLibrary()
                                                        status = context.getString(R.string.book_download_done, book.title)
                                                    } catch (e: Exception) {
                                                        status = context.getString(R.string.book_download_fail, e.message ?: "")
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.lib_act_download))
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                lib.setFav(book.id, !fav)
                                                metas = lib.metas()
                                            }
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = stringResource(
                                                if (fav) R.string.lib_act_unfav else R.string.lib_act_fav
                                            ),
                                            tint = if (fav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                lib.setToRead(book.id, !toRead)
                                                metas = lib.metas()
                                            }
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            if (toRead) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd,
                                            contentDescription = stringResource(
                                                if (toRead) R.string.lib_act_untoread else R.string.lib_act_toread
                                            ),
                                            tint = if (toRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Box {
                                        IconButton(
                                            onClick = { menuFor = if (menuFor == book.id) null else book.id },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.lib_act_menu))
                                        }
                                        DropdownMenu(
                                            expanded = menuFor == book.id,
                                            onDismissRequest = { menuFor = null }
                                        ) {
                                            val isRead = meta?.shelfRead == true ||
                                                (prog?.let { it.total > 0 && it.position >= it.total } == true)
                                            DropdownMenuItem(
                                                text = { Text(stringResource(if (isRead) R.string.lib_act_unmarkread else R.string.lib_act_markread)) },
                                                leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                                                onClick = {
                                                    menuFor = null
                                                    scope.launch {
                                                        lib.setRead(book.id, !isRead)
                                                        metas = lib.metas()
                                                        status = context.getString(
                                                            if (!isRead) R.string.lib_marked_read else R.string.lib_marked_unread
                                                        )
                                                    }
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.lib_act_trash)) },
                                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                                onClick = {
                                                    menuFor = null
                                                    scope.launch(Dispatchers.IO) { status = trashBook(book) }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                }

                // STORAGE section (giữ trình duyệt file cũ, gọn dưới thư viện)
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { treePicker.launch(null) }) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.doc_folder_saf), fontSize = 12.sp)
                        }
                        TextButton(onClick = { requestLegacyPermission() }) {
                            Text("Permission", fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        if (isLoadingStorage) LinearProgressIndicator(modifier = Modifier.weight(0.3f))
                    }
                    Text("📁 " + (if (selectedTreeUri != null) "SAF: ${selectedTreeUri?.lastPathSegment}" else stringResource(R.string.doc_no_folder)), style = MaterialTheme.typography.labelSmall)
                }

                if (storageFiles.isEmpty() && !isLoadingStorage && selectedTreeUri == null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.doc_browse_hint), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                val storageDirs = storageFiles.filter { it.isDirectory }.take(20)
                val storageFilesList = storageFiles.filter { !it.isDirectory }.take(50)
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
                                TextButton(onClick = { scope.launch { scanTree(doc.documentFile?.uri ?: Uri.parse(doc.path)) } }) {
                                    Text(stringResource(R.string.doc_open), fontSize = 12.sp) }
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

                if (filteredStorageEmpty(storageFiles, isLoadingStorage, debouncedQuery)) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(R.string.doc_no_search, searchQuery), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📖 Thêm file → kệ Sách & tài liệu tự cập nhật khi refresh.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

/** Ô bìa 56×80: bitmap thật (PDF/EPUB đã tải) hoặc placeholder chữ + emoji format. */
@Composable
private fun LibraryCover(book: BookMeta) {
    var bmp by remember(book.id) { mutableStateOf<Bitmap?>(null) }
    var tried by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id, book.localFile?.length()) {
        if (!tried && book.isDownloaded) {
            tried = true
            bmp = LibraryCovers.coverFor(book)
        }
    }
    val img = bmp
    if (img != null) {
        Image(
            bitmap = img.asImageBitmap(),
            contentDescription = book.title,
            modifier = Modifier.size(56.dp, 80.dp)
        )
    } else {
        val emoji = when (LibraryQuery.libFormatOf(book)) {
            "PDF" -> "📕"
            "EPUB" -> "📗"
            "MD", "TXT" -> "📝"
            else -> "📄"
        }
        Box(
            modifier = Modifier.size(56.dp, 80.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(emoji, fontSize = 20.sp)
                Text(
                    book.title.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun filteredStorageEmpty(
    storageFiles: List<DocFileItem>,
    isLoadingStorage: Boolean,
    debouncedQuery: String
): Boolean {
    if (isLoadingStorage || storageFiles.isEmpty() || debouncedQuery.isBlank()) return false
    return storageFiles.none {
        it.name.contains(debouncedQuery, ignoreCase = true) ||
            it.mimeType.contains(debouncedQuery, ignoreCase = true)
    }
}
// (DocFileItem + getFileName đã dời vào data.library.LibraryManager — core dùng chung.)
