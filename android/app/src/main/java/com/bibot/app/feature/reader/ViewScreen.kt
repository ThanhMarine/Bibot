package com.bibot.app.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bibot.app.R
import com.bibot.app.core.text.BlockTextReader
import com.bibot.app.core.text.EpubReader
import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.books.BookRepository
import com.bibot.app.data.offline.OfflineTtsHelper
import com.bibot.app.feature.doc.LibraryCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Hiển thị tối đa mỗi chương (tránh Text khổng lồ với PDF dồn 1 chương);
// còn tiếp → mở ReaderScreen đọc full.
private const val VIEW_MAX_CHARS = 12_000
// Đọc thử TTS tối đa (OfflineTtsHelper enqueue 1 utterance).
private const val VIEW_TTS_MAX_CHARS = 5_000
private const val VIEW_PAGE_CHARS = 1_500

/**
 * Mode 2 "Xem" — viewer sách THẬT (đính chính mock tĩnh cũ):
 *  - Kệ sách từ [BookRepository] (đã tải ưu tiên, chưa tải thì tải).
 *  - Chương = [BlockTextReader.readChapters] (EPUB spine-order / PDF / MD —
 *    đã qua pipeline clean + `ReadingRules.applyIfReady`).
 *  - Bìa = [LibraryCovers] (PDF render trang 0 / EPUB cover thật),
 *    tác giả = OPF `dc:creator`.
 *  - PAGE: PDF dùng [PdfPageView] thật; EPUB/MD phân trang text.
 *  - Nút loa/play/pause đọc thật bằng [OfflineTtsHelper] (xem trước,
 *    không phải pipeline TTS full của ReaderScreen).
 */
@Composable
fun ViewScreen(
    onBack: () -> Unit,
    book: BookMeta? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BookRepository(context) }
    val offlineTts = remember { OfflineTtsHelper(context) }

    LaunchedEffect(Unit) {
        offlineTts.ensureInit {}
        try { repo.refreshAll() } catch (_: Exception) {}
    }
    DisposableEffect(Unit) { onDispose { try { offlineTts.stop() } catch (_: Exception) {} } }

    var books by remember { mutableStateOf<List<BookMeta>>(emptyList()) }
    var repoStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        repo.books.collect { books = it }
    }

    var selected by remember(book?.id) { mutableStateOf<BookMeta?>(book) }
    var chapters by remember { mutableStateOf<List<BlockTextReader.ChapterBlock>>(emptyList()) }
    var author by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var bookFile by remember { mutableStateOf<File?>(null) }
    var scanned by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    var viewMode by remember { mutableStateOf(ReaderView.TEXT) }
    var selIdx by remember { mutableIntStateOf(0) }
    var textPage by remember { mutableIntStateOf(0) }
    var speaking by remember { mutableStateOf(false) }

    fun loadBook(b: BookMeta) {
        selected = b
        chapters = emptyList()
        author = ""
        cover = null
        bookFile = null
        scanned = false
        selIdx = 0
        textPage = 0
        viewMode = ReaderView.TEXT
        speaking = false
        try { offlineTts.stop() } catch (_: Exception) {}
        loading = true
        status = null
        scope.launch {
            try {
                val file: File = if (b.isDownloaded && b.localFile != null) b.localFile
                else withContext(Dispatchers.IO) { repo.download(b) }
                val isEpub = file.extension.equals("epub", ignoreCase = true)
                val isPdf = file.extension.equals("pdf", ignoreCase = true)
                val isMobi = com.bibot.app.core.text.MobiReader.isMobiExt(file.name)
                val meta = withContext(Dispatchers.IO) {
                    Triple(
                        if (isEpub) EpubReader.readMeta(file).author
                        else if (isMobi) com.bibot.app.core.text.MobiReader.readMeta(file).author
                        else "",
                        BlockTextReader.readChapters(
                            file, b.title,
                            BlockTextReader.Heading.MARKDOWN_OR_STORY
                        ),
                        if (isPdf) BlockTextReader.isScannedPdf(file)
                        else if (isEpub) EpubReader.isScannedEpub(file) else false
                    )
                }
                val bmp = withContext(Dispatchers.IO) { LibraryCovers.coverFor(b.copy(localFile = file)) }
                if (selected?.id != b.id) return@launch
                author = meta.first
                chapters = meta.second
                scanned = meta.third
                bookFile = file
                cover = bmp
                if (isPdf) viewMode = ReaderView.PAGE
                if (meta.second.size == 1 && (meta.second.firstOrNull()?.content.isNullOrBlank())) {
                    status = if (meta.third) context.getString(
                        if (isPdf) R.string.reader_pdf_scanned else R.string.reader_epub_scanned
                    ) else context.getString(R.string.reader_error, "empty")
                }
            } catch (e: Exception) {
                if (selected?.id == b.id) status = context.getString(R.string.reader_error, e.message ?: "")
            } finally {
                if (selected?.id == b.id) loading = false
            }
        }
    }

    LaunchedEffect(book?.id) {
        if (book != null && selected?.id != book.id) loadBook(book)
        else if (book != null && selected != null && chapters.isEmpty() && !loading) loadBook(book)
    }

    fun goBack() {
        try { offlineTts.stop() } catch (_: Exception) {}
        speaking = false
        // Vào từ nav (book == null): back = về kệ Xem; vào với sách sẵn: back = về Home.
        if (book == null && selected != null) {
            selected = null
            chapters = emptyList()
        } else onBack()
    }

    fun speak(text: String, lang: String) {
        val t = text.take(VIEW_TTS_MAX_CHARS)
        if (t.isBlank()) return
        try { offlineTts.stop() } catch (_: Exception) {}
        speaking = true
        scope.launch {
            try {
                offlineTts.speakOffline(t, lang = lang)
            } catch (_: Exception) {
            } finally {
                speaking = false
            }
        }
    }

    // ---- Kệ sách (chưa chọn sách) ----
    if (selected == null) {
        val viewable = books.filter {
            it.title.endsWith(".epub", ignoreCase = true) ||
                it.title.endsWith(".pdf", ignoreCase = true) ||
                it.title.endsWith(".md", ignoreCase = true) ||
                it.title.endsWith(".txt", ignoreCase = true) ||
                com.bibot.app.core.text.MobiReader.isMobiExt(it.title) ||
                it.mimeType.contains("epub") || it.mimeType.contains("pdf") ||
                it.mimeType.contains("mobipocket") ||
                it.mimeType.contains("markdown") || it.mimeType.contains("plain")
        }
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.reader_back))
                }
                Text(
                    stringResource(R.string.nav_view),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                TextButton(onClick = {
                    scope.launch {
                        try { repo.refreshAll() } catch (_: Exception) {}
                        repoStatus = repo.lastSyncWarning.value
                    }
                }) { Text(stringResource(R.string.book_refresh)) }
            }
            Text(
                stringResource(R.string.home_book_count, viewable.size),
                style = MaterialTheme.typography.labelMedium
            )
            repoStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(viewable, key = { _, b -> b.id }) { _, b ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { loadBook(b) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("📖", fontSize = MaterialTheme.typography.titleLarge.fontSize)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text(
                                    b.lang + " • " + b.source + " • " +
                                        (if (b.isDownloaded) stringResource(R.string.book_is_downloaded) else stringResource(R.string.book_not_downloaded)),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                if (viewable.isEmpty()) {
                    item { Text(stringResource(R.string.home_story_empty), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        return
    }

    // ---- Detail sách thật ----
    val b = selected!!
    val lang = b.lang.ifBlank { "vi" }
    val sel = chapters.getOrNull(selIdx)
    // Marker tạm dừng của pipeline TTS (splitPause) — viewer chỉ hiển thị "…".
    val fullText = sel?.content.orEmpty().replace("PAUSE2SEC", "…")
    val shownText = if (fullText.length > VIEW_MAX_CHARS) fullText.take(VIEW_MAX_CHARS) + "…" else fullText
    val pages = if (shownText.isEmpty()) emptyList() else shownText.chunked(VIEW_PAGE_CHARS)
    val page = pages.getOrNull(textPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))).orEmpty()

    LazyColumn(
        Modifier.fillMaxSize().padding(vertical = 16.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { goBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.reader_back))
                }
                Text(
                    b.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                IconButton(onClick = {
                    viewMode = if (viewMode == ReaderView.TEXT) ReaderView.PAGE else ReaderView.TEXT
                    textPage = 0
                }) {
                    Icon(
                        if (viewMode == ReaderView.TEXT) Icons.Default.MenuBook else Icons.Default.PictureAsPdf,
                        contentDescription = "Toggle view mode",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            val bmp = cover
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = b.title,
                    modifier = Modifier.fillMaxWidth(0.6f).height(180.dp)
                )
            } else {
                Box(
                    Modifier.fillMaxWidth(0.8f).height(180.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        b.title.take(30),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (author.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (loading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth(0.8f))
            }
            status?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(0.9f))
            }
        }

        if (viewMode == ReaderView.PAGE && bookFile != null &&
            bookFile!!.extension.equals("pdf", ignoreCase = true)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                PdfPageView(
                    file = bookFile!!,
                    onVisiblePage = {},
                    onPageTap = {},
                    onPageLongPress = {},
                    modifier = Modifier.fillMaxWidth().height(420.dp)
                )
            }
        } else if (viewMode == ReaderView.PAGE && pages.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    page,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { textPage = (textPage - 1).coerceAtLeast(0) },
                        enabled = textPage > 0
                    ) { Text("‹") }
                    Text(
                        "${textPage + 1} / ${pages.size}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    OutlinedButton(
                        onClick = { textPage = (textPage + 1).coerceAtMost(pages.size - 1) },
                        enabled = textPage < pages.size - 1
                    ) { Text("›") }
                }
            }
        } else {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Mục lục (${chapters.size})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            itemsIndexed(chapters, key = { i, c -> "$i:${c.title}" }) { i, c ->
                Row(
                    Modifier.fillMaxWidth().clickable { selIdx = i; textPage = 0 }.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        c.title.ifBlank { "Chương ${i + 1}" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (i == selIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (i == selIdx) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (i == selIdx) {
                        IconButton(onClick = { speak(c.content, lang) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.reader_play_read), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item {
                if (sel != null && shownText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        shownText,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )
                    if (fullText.length > VIEW_MAX_CHARS) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "… (còn tiếp — mở Sách/Kể chuyện để đọc full)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (!loading && !scanned) {
                    Spacer(Modifier.height(8.dp))
                    Text("—", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(0.8f).height(56.dp).padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(48.dp).height(48.dp)) {
                    IconButton(onClick = {
                        val first = chapters.firstOrNull()?.content.orEmpty()
                        if (first.isNotBlank()) { selIdx = 0; textPage = 0; speak(first, lang) }
                    }) {
                        Icon(Icons.Default.VolumeUp, contentDescription = "Đọc từ đầu", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Box(Modifier.width(56.dp).height(56.dp)) {
                    IconButton(onClick = { if (fullText.isNotBlank()) speak(fullText, lang) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.reader_play_read), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Box(Modifier.width(48.dp).height(48.dp)) {
                    IconButton(onClick = {
                        try { offlineTts.stop() } catch (_: Exception) {}
                        speaking = false
                    }) {
                        Icon(
                            Icons.Default.Pause,
                            contentDescription = stringResource(R.string.reader_pause),
                            tint = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            if (speaking) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
            }
            if (author.isNotBlank()) {
                Text(
                    "© $author",
                    modifier = Modifier.fillMaxWidth(0.8f).padding(top = 8.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (scanned) {
                Text(
                    stringResource(
                        if ((bookFile?.extension ?: "").equals("pdf", ignoreCase = true)) R.string.reader_pdf_scanned
                        else R.string.reader_epub_scanned
                    ),
                    modifier = Modifier.fillMaxWidth(0.9f).padding(bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
