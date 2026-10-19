package com.bibot.app.feature.reader

/**
 * R0 - Man doc duy nhat cho moi kho sach (F0.1).
 * Tong quat hoa tu BookScreen (giu nguyen luong producer Channel(2) +
 * PrepSession), khac biet theo ReaderCollection (filter kho,
 * lang/voice, co ads, chuoi UI). Tham khao / PDF local dung chung.
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibot.app.R
import com.bibot.app.core.text.BlockTextReader
import com.bibot.app.core.text.BookPrepStore
import com.bibot.app.core.tts.CloudTtsBridge
import com.bibot.app.core.tts.awaitAudioDone
import com.bibot.app.core.tts.awaitAudioStart
import com.bibot.app.core.tts.delayUntilDone
import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.books.BookRepository
import com.bibot.app.data.db.BibotDatabase
import com.bibot.app.data.files.FilePaths
import com.bibot.app.data.store.BotPromptManager
import com.bibot.app.data.summary.SummaryRepository
import com.bibot.app.data.summary.SummaryWorker
import com.bibot.app.data.library.ReadingPosition
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.ui.player.PlayerViewModel
import com.bibot.app.ui.player.FloatingControlScaffold
import com.bibot.app.ui.player.ReaderFloatingControlBar
import com.bibot.app.ui.player.contentBottomInset
import com.bibot.app.feature.export.ExportViewModel
import com.bibot.app.feature.export.ExportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val READER_PAUSE_BETWEEN_MS = 450L
// Rev/Next nhay doan ngan (± cau) thay vi doi chuong; chuyen chuong bang cham tab Hero bar
private const val READER_SHORT_JUMP_SENTENCES = 10

// 8.3 PCM flip-flop — producer synthesize trên IO, consumer đọc trên Main (tối đa 2 chunk đệm).
// Luong doc PCM flip-flop (producer IO / consumer Main, dem 2 chunk) - da ngu theo collection.
private sealed interface ReaderTtsItem {
    val chapIdx: Int
    val sentIdx: Int
    val sentTotal: Int
    val title: String
    val pauseAfterMs: Long
    val jumpSeq: Int
    data class Pcm(
        override val chapIdx: Int,
        override val sentIdx: Int,
        override val sentTotal: Int,
        override val title: String,
        val bytes: ByteArray,
        val textLen: Int,
        override val pauseAfterMs: Long = 0L,
        val mime: String = "audio/mp3",
        override val jumpSeq: Int = 0
    ) : ReaderTtsItem
    data class Direct(
        override val chapIdx: Int,
        override val sentIdx: Int,
        override val sentTotal: Int,
        override val title: String,
        val text: String,
        override val pauseAfterMs: Long = 0L,
        override val jumpSeq: Int = 0
    ) : ReaderTtsItem
}

// P1: chương chỉ giữ TITLE — content đọc per-chương từ `.prep` (seek) qua PrepSession
data class Chapter(val title: String)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    playerVm: PlayerViewModel = viewModel(),
    collection: ReaderCollection = ReaderCollection.EN,
    // Kệ sách (chưa mở sách): Back → về Home (không còn nút Home ở bottom bar).
    onBackHome: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BookRepository(context) }
    val prefs = remember { BibotPreferences(context) }
    val prefsState by prefs.prefsFlow.collectAsState(initial = com.bibot.app.data.store.BibotPrefs())
    // A3.2: không còn OfflineTtsHelper riêng — đọc offline đi qua playerVm (1 controller).
    val db = remember { BibotDatabase.get(context) }

    // Landscape detection (dung chung moi kho)
    val config = LocalConfiguration.current
    val isLandscape = config.screenWidthDp > config.screenHeightDp

    var books by remember { mutableStateOf<List<BookMeta>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedBook by remember { mutableStateOf<BookMeta?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var currentChapter by remember { mutableIntStateOf(0) }
    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSentence by remember { mutableIntStateOf(0) }
    var totalSentencesAll by remember { mutableIntStateOf(0) }
    var isReading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // P1: phiên đọc sách — title list không content, content/câu đọc per-chương (cache đĩa)
    var prepSession by remember { mutableStateOf<BookPrepStore.PrepSession?>(null) }
    DisposableEffect(Unit) { onDispose { prepSession?.close() } }
    val jumpChapterReq = remember { mutableIntStateOf(-1) }
    val jumpSentenceReq = remember { mutableIntStateOf(0) }
    val jumpSeq = remember { mutableIntStateOf(0) }
    // Highlight chi bat khi audio that su start (sua loi chunk dau to som trong luc cho synth)
    var audioStarted by remember { mutableStateOf(false) }
    // A1.4: engine thực tế của lượt đọc (Piper/Cloud/Offline) — snapshot lúc bắt
    // đầu đọc để báo degraded (thiếu model/key), không re-plan giữa chừng.
    var degradedEngine by remember { mutableStateOf(false) }
    // Quyen VIP (ma VIP): sach pha QC + mo Cloud (can key), hieu luc tu lan bam Doc ke tiep
    val adsPrefs = remember { com.bibot.app.data.ads.AdsPreferences(context) }
    val adsState by adsPrefs.adsStateFlow.collectAsState(initial = com.bibot.app.data.ads.AdsState())
    val vipActive = com.bibot.app.data.ads.isVipActive(adsState)
    // A3.1: ExportViewModel for audiobook export
    val exportVm = viewModel<ExportViewModel>()
    var exportBookId by remember { mutableStateOf<String?>(null) }
    // B3+E1.1+F2: chrome + 2 chế độ VIEW + Touch_point (mặc định BROWSE: chỉ render).
    var menuVisible by remember { mutableStateOf(true) }
    var viewMode by remember { mutableStateOf(ReaderView.TEXT) }
    var isPdfBook by remember { mutableStateOf(false) }
    var isEpubBook by remember { mutableStateOf(false) }
    // Scope B: titles spine EPUB (map session-chương → spine rich, KHỚP 1-1 chapters).
    var epubRichTitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var viewLockedPage by remember { mutableStateOf(false) } // scan: chỉ PAGE
    var readModeState by remember { mutableStateOf(ReadMode.BROWSE) }
    var selAnchor by remember { mutableStateOf<ReadAnchor?>(null) } // marker điểm neo
    var anchorConfirm by remember { mutableStateOf<ReadAnchor?>(null) } // hộp xác nhận
    var pendingJump by remember { mutableStateOf<JumpConfirm?>(null) } // B3.3
    var tocOpen by remember { mutableStateOf(false) }
    // B5: tóm tắt AI theo chương + cấp sách (dialog 4 trạng thái, nền WorkManager).
    var summaryOpen by remember { mutableStateOf(false) }
    var summaryIsBook by remember { mutableStateOf(false) }
    var summaryIdx by remember { mutableIntStateOf(0) }
    var summaryTitle by remember { mutableStateOf("") }
    var summaryState by remember { mutableStateOf<SummaryUiState>(SummaryUiState.Loading()) }
    var summarizedIdx by remember { mutableStateOf(setOf<Int>()) }
    var summaryBusyAd by remember { mutableStateOf(false) }
    var summaryJob by remember { mutableStateOf<Job?>(null) }
    // Tìm kiếm trong chương (nút Search): B4.2 mở rộng ra toàn sách sau.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf(listOf<Int>()) }
    var searchPos by remember { mutableIntStateOf(0) }
    var searchChap by remember { mutableIntStateOf(-1) }
    // B6.2/B6.3: popup ⚙ + hint điều hướng chạm (1 lần mỗi máy).
    var settingsOpen by remember { mutableStateOf(false) }
    var hintOpen by remember { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(0) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var brightness by remember { mutableStateOf<Float?>(null) } // null = hệ thống
    var bookFile by remember { mutableStateOf<File?>(null) }
    var fileHashState by remember { mutableStateOf("") } // SHA-1 (E1.3)
    var scanNoTts by remember { mutableStateOf(false) } // scan: disable loa
    var savedPos by remember { mutableIntStateOf(0) } // legacy position (giữ hiển thị cũ)
    var savedResume by remember { mutableStateOf(ReadingPosition()) } // A2.4: vị trí thật
    var savedAnchor by remember { mutableStateOf<ReadAnchor?>(null) }
    // A (tách display/TTS): chương đầu có mốc bắt đầu đọc (-1 = sách không mốc → đọc từ đầu).
    var firstMarkerChapIdx by remember { mutableIntStateOf(-1) }
    // R0: chuoi UI theo collection (EN/sach vs Ke chuyen/Co tich)
    val strLoading = if (collection.useStoryStrings) R.string.reader_loading_story else R.string.reader_loading_book
    val strAtStart = if (collection.useStoryStrings) R.string.reader_at_start_story else R.string.reader_at_start_book
    val strPlay = if (collection.useStoryStrings) R.string.reader_play_tell else R.string.reader_play_read
    val strDone = if (collection.useStoryStrings) R.string.reader_done_telling else R.string.reader_done_reading
    val strStatus = if (collection.useStoryStrings) R.string.reader_status_telling else R.string.reader_status_reading
    // Tiêu đề kệ sách theo kho (hàng Back về Home).
    val strListTitle = when (collection) {
        ReaderCollection.EN -> R.string.nav_book
        ReaderCollection.COTICH -> R.string.nav_cotich
        else -> R.string.nav_story
    }

    // A2.3: RenderIfDirty key — chỉ relayout khi cài đặt hiển thị thay đổi (Snap config)
    // Tránh jank khi đổi font sách dày: chỉ recompose khi fontSize/interline/paragraphMargin thực sự thay đổi
    val renderKey = androidx.compose.runtime.derivedStateOf {
        prefsState.readerFontSizeSp to prefsState.readerInterline to prefsState.readerParagraphMargin
    }.value

    LaunchedEffect(Unit) {
        playerVm.ensureOfflineTts {}
        repo.refreshAll()
        isLoading = false
        repo.lastSyncWarning.value?.let { status = it }
        repo.books.collect { books = it }
    }

    fun loadBook(book: BookMeta) {
        selectedBook = book
        // B1.1: chạm kệ "Đang đọc" (fire-and-forget, không chặn mở sách).
        scope.launch(Dispatchers.IO) {
            try { db.library().touchOpen(book.id) } catch (_: Exception) {}
        }
        currentChapter = 0
        currentSentence = 0
        isReading = false
        chapters = emptyList()
        sentences = emptyList()
        firstMarkerChapIdx = -1
        prepSession?.close()
        prepSession = null
        // B3+F2: về BROWSE khi mở sách (chỉ render, chưa chunking/TTS).
        readModeState = ReadMode.BROWSE
        selAnchor = null
        anchorConfirm = null
        pendingJump = null
        tocOpen = false
        pageCount = 0
        pageIndex = 0
        brightness = null
        bookFile = null
        fileHashState = ""
        scanNoTts = false
        isPdfBook = false
        isEpubBook = false
        epubRichTitles = emptyList()
        viewLockedPage = false
        menuVisible = true
        savedPos = 0
        savedResume = ReadingPosition()
        savedAnchor = null
        status = context.getString(strLoading)
        scope.launch {
            try {
                val file: File = if (book.isDownloaded && book.localFile != null) book.localFile
                else {
                    status = context.getString(R.string.reader_downloading)
                    with(Dispatchers.IO) { repo.download(book) }
                }
                // P1: đọc progress TRƯỚC — biết chương resume để mở đúng câu
                status = context.getString(R.string.reader_checking_progress)
                val progress = db.book().get(book.id)
                savedPos = progress?.position ?: 0
                savedResume = if (progress != null) ReadingPosition.fromProgress(progress) else ReadingPosition()

                // P1: hit `.prep` → bỏ hẳn resolveHeading + readChapters/clean (2 pass toàn file).
                // Miss → build streaming (sink, không giữ sách RAM) → ghi đĩa.
                val prepDir = FilePaths.prepDir(context)
                val cacheKey = book.id.ifBlank { book.title }
                val heading = BlockTextReader.Heading.MARKDOWN_OR_STORY
                val opened = withContext(Dispatchers.IO) {
                    BookPrepStore.open(prepDir, cacheKey, file, heading)
                }
                val session: BookPrepStore.PrepSession
                if (opened != null) {
                    val sent = withContext(Dispatchers.IO) { BookPrepStore.openSentences(opened) }
                    session = BookPrepStore.PrepSession.of(opened, sent)
                } else {
                    status = context.getString(R.string.reader_cleaning)
                    val built = withContext(Dispatchers.IO) {
                        BookPrepStore.build(prepDir, cacheKey, file, heading, collection.fallbackTitle ?: book.title)
                    }
                    session = if (built != null) BookPrepStore.PrepSession.of(built, null)
                    else {
                        // Fallback in-memory khi cache đĩa lỗi (giữ hành vi bản cũ, không mất sách).
                        // A: content nguyên bản (display-faithful); TTS-smart ở speak-time.
                        val chaps = withContext(Dispatchers.IO) {
                            BlockTextReader.readOriginalChapters(file, collection.fallbackTitle ?: book.title, heading)
                        }
                        BookPrepStore.PrepSession.inMemory(chaps.map { it.title }, chaps.map { it.content })
                    }
                }
                prepSession = session
                chapters = session.titles.map { Chapter(it) }
                // A: mốc bắt đầu đọc cho TTS (lướt phần đầu/mục lục) — display vẫn giữ đủ.
                firstMarkerChapIdx = chapters.indexOfFirst {
                    com.bibot.app.core.text.BlockTextReader.isReadingStartMarker(it.title)
                }
                totalSentencesAll = session.sentenceTotal ?: session.estimateTotal

                // Resume A2.4: đọc từ cột mới (đúng với chương >100 câu),
                // fallback legacy/anchor cho dữ liệu cũ.
                val resumePos = if (progress != null) ReadingPosition.fromProgress(progress) else ReadingPosition()
                val startChap = if (progress != null && chapters.isNotEmpty())
                    resumePos.chap.coerceIn(0, chapters.lastIndex) else 0
                currentChapter = startChap
                sentences = session.sentences(startChap)
                currentSentence = if (progress != null)
                    resumePos.sent.coerceIn(0, (sentences.size - 1).coerceAtLeast(0)) else 0

                // B3+E1.1+F2: VIEW mặc định + fileHash + scan-lock + neo đã lưu.
                // PDF → PAGE (ảnh); EPUB scan → PAGE rich-ảnh; còn lại TEXT.
                // Scope B: EPUB toggle TEXT (karaoke) ↔ PAGE rich (ảnh + format gốc).
                bookFile = file
                val isPdf = file.extension.equals("pdf", ignoreCase = true)
                val isEpub = file.extension.equals("epub", ignoreCase = true)
                isPdfBook = isPdf
                isEpubBook = isEpub
                val scanned = withContext(Dispatchers.IO) {
                    if (isPdf) BlockTextReader.isScannedPdf(file)
                    else if (isEpub) com.bibot.app.core.text.EpubReader.isScannedEpub(file)
                    else false
                }
                scanNoTts = scanned
                viewLockedPage = (isPdf || isEpub) && scanned
                viewMode = if (isPdf || (isEpub && scanned)) ReaderView.PAGE else ReaderView.TEXT
                if (isEpub) {
                    // Titles spine (KHỚP 1-1 chapters) để map session-chương → spine rich.
                    epubRichTitles = withContext(Dispatchers.IO) {
                        try {
                            com.bibot.app.core.text.EpubReader.richTitles(
                                file, collection.fallbackTitle ?: book.title
                            )
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
                menuVisible = true
                scope.launch(Dispatchers.IO) { fileHashState = sha1File(file) }
                savedAnchor = if (progress != null && progress.anchorPage >= 0) {
                    if (viewMode == ReaderView.PAGE) {
                        ReadAnchor(ReaderView.PAGE, progress.anchorPage.coerceAtLeast(0), 0)
                    } else {
                        ReadAnchor(
                            ReaderView.TEXT,
                            progress.anchorPage.coerceIn(0, (chapters.size - 1).coerceAtLeast(0)),
                            progress.anchorOffset.coerceAtLeast(0)
                        )
                    }
                } else null

                if (progress != null && (resumePos.isStarted || progress.position > 0)) {
                    scope.launch {
                        val currentPos = progress.position
                        val totalNow = if (totalSentencesAll > 0) totalSentencesAll else progress.total
                        val where = resumePos.resumeLabel()
                        // Thông báo resume đúng NGÔN NGỮ sách — trước đây sách EN bị đọc bằng câu tiếng Việt không dấu
                        val readLang = collection.resolveLang(book)
                        val announce = if (readLang == "en") {
                            "You were listening to ${book.title} at $where, sentence $currentPos of $totalNow."
                        } else {
                            "Bạn đã nghe ${book.title} tới $where, câu $currentPos trên $totalNow."
                        }
                        val useCloud = prefsState.ttsPriority != "offline"
                        // P0-1: key Cloud TTS riêng (Gemini key ≠ TTS key)
                        val geminiKey = prefsState.cloudTtsApiKey.takeIf { it.isNotBlank() }
                        val voice = if (readLang == "en") CloudTtsBridge.DEFAULT_EN_VOICE else CloudTtsBridge.DEFAULT_VI_VOICE
                        val announceSpeed = if (readLang == "vi") prefsState.storySpeed else prefsState.voiceSpeed
                        if (useCloud && !geminiKey.isNullOrBlank()) {
                            val bytes = CloudTtsBridge.synthesize(announce, geminiKey, voice = voice, speakingRate = announceSpeed)
                            if (bytes != null) {
                                playerVm.playBytes(bytes)
                                delay((announce.length * 60L).coerceAtLeast(700L))
                            }
                        } else {
                            playerVm.speakOffline(announce, announceSpeed, lang = readLang)
                        }
                    }
                }

                // P1: build sentence cache NỀN (song song 3-4 luồng, ghi streaming) — chưa có mới build
                if (session.sentenceTotal == null) {
                    scope.launch {
                        val real = BookPrepStore.buildSentences(prepDir, cacheKey, file, heading)
                        if (real != null && real > 0 && selectedBook?.id == book.id) {
                            session.sentenceTotal = real
                            totalSentencesAll = real
                            status = context.getString(R.string.reader_loaded, chapters.size, real) + if (collection.useStoryStrings) " \u2022 " + (if (collection.resolveLang(book) == "vi") prefsState.storySpeed else prefsState.voiceSpeed).toString() + "x" else ""
                        }
                    }
                }
                status = context.getString(R.string.reader_loaded, chapters.size, totalSentencesAll) + if (collection.useStoryStrings) " \u2022 " + (if (collection.resolveLang(book) == "vi") prefsState.storySpeed else prefsState.voiceSpeed).toString() + "x" else ""
                // Sách scan/ảnh không có text thật để trích — báo rõ thay vì hiển thị
                // rác/trang trắng: PDF font Type3 bitmap (vd grimm10) / EPUB toàn ảnh.
                if (totalSentencesAll == 0) {
                    val scannedMsg = withContext(Dispatchers.IO) {
                        when {
                            file.extension.equals("pdf", ignoreCase = true) &&
                                BlockTextReader.isScannedPdf(file) ->
                                context.getString(R.string.reader_pdf_scanned)
                            file.extension.equals("epub", ignoreCase = true) &&
                                com.bibot.app.core.text.EpubReader.isScannedEpub(file) ->
                                context.getString(R.string.reader_epub_scanned)
                            else -> null
                        }
                    }
                    if (scannedMsg != null && selectedBook?.id == book.id) status = scannedMsg
                }
            } catch (e: Exception) {
                status = context.getString(R.string.reader_error, e.message ?: "")
            }
        }
    }

    fun switchChapter(idx: Int, sentence: Int = 0) {
        if (idx !in chapters.indices) return
        val session = prepSession ?: return
        val wantLast = sentence < 0
        currentChapter = idx
        currentSentence = if (wantLast) 0 else sentence.coerceAtLeast(0)
        scope.launch {
            val list = session.sentences(idx)
            sentences = list
            currentSentence = if (wantLast) (list.size - 1).coerceAtLeast(0)
            else sentence.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        }
    }

    // B5: tải tập chương đã có tóm tắt (badge tiến độ wiki ở TOC).
    fun refreshSummarized(bookId: String) {
        scope.launch(Dispatchers.IO) {
            try {
                summarizedIdx = db.summaries().getByBook(bookId)
                    .map { it.chapterIdx }.filter { it >= 0 }.toSet()
            } catch (_: Exception) {}
        }
    }

    // B5: tóm tắt 1 chương (local → wiki → LLM). Mất key/offline → Gate giữ vị trí đọc.
    fun fetchChapterSummary(idx: Int) {
        val book = selectedBook ?: return
        val session = prepSession ?: return
        if (idx !in chapters.indices) return
        summaryJob?.cancel()
        summaryIsBook = false
        summaryIdx = idx
        summaryTitle = chapters[idx].title
        summaryOpen = true
        summaryState = SummaryUiState.Loading()
        summaryJob = scope.launch(Dispatchers.IO) {
            try {
                val readLang = collection.resolveLang(book)
                val text = try {
                    session.content(idx).take(BotPromptManager.SUMMARY_MAX_CHARS)
                } catch (_: Exception) { "" }
                if (text.isBlank()) { summaryState = SummaryUiState.Error("empty"); return@launch }
                val keys = SummaryRepository.LlmKeys(
                    prefsState.geminiApiKey, prefsState.geminiModel, prefsState.openRouterKey
                )
                when (val r = SummaryRepository.summarizeChapter(
                    context, book.id, idx, chapters[idx].title, text, readLang,
                    keys, vipActive, prefsState.summaryShareEnabled
                )) {
                    is SummaryRepository.Outcome.Ready -> {
                        summaryState = SummaryUiState.Content(r.text, r.fromShared)
                        refreshSummarized(book.id)
                    }
                    is SummaryRepository.Outcome.NoCredit ->
                        summaryState = SummaryUiState.Gate(false, adsPrefs.getRewardedSummary())
                    is SummaryRepository.Outcome.NeedKey -> summaryState = SummaryUiState.Gate(true, 0)
                    is SummaryRepository.Outcome.Failed -> summaryState = SummaryUiState.Error(r.msg)
                }
            } catch (_: Exception) { summaryState = SummaryUiState.Error("error") }
        }
    }

    // B5: xem rewarded ad lấy lượt tóm tắt (mirror Cloud chapters: +3, trần 9).
    // 2026-10-15: Toast phản hồi khi QC chưa sẵn sàng (trước đây nút chết im lặng).
    fun watchAdForSummary(retry: () -> Unit) {
        val activity = context as? android.app.Activity ?: return
        if (!com.bibot.app.ui.components.ads.canShowRewarded(adsState)) {
            android.util.Log.d("BibotReward", "blocked: vip/cooldown, lastShow=${adsState.adLastShowTime}")
            android.widget.Toast.makeText(context, context.getString(R.string.ads_cooldown), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (summaryBusyAd) return
        summaryBusyAd = true
        com.bibot.app.data.ads.AdManager.onRewardedClaimed = {
            scope.launch {
                try {
                    adsPrefs.claimRewardedSummary(3)
                    retry()
                } catch (_: Exception) {
                } finally {
                    summaryBusyAd = false
                }
            }
        }
        val shown = try {
            com.bibot.app.data.ads.AdManager.showRewarded(activity)
        } catch (_: Exception) { false }
        if (!shown) {
            android.util.Log.d("BibotReward", "ad not ready, loading...")
            android.widget.Toast.makeText(context, context.getString(R.string.ads_loading), android.widget.Toast.LENGTH_SHORT).show()
            try { com.bibot.app.data.ads.AdManager.loadRewarded(activity) } catch (_: Exception) {}
            summaryBusyAd = false
        }
    }

    // B5: nghe tóm tắt bằng chain TTS hiện có (offline-first, Cloud nếu có key).
    fun listenSummary(text: String) {
        val book = selectedBook ?: return
        scope.launch {
            try {
                // A3.2: 1 lệnh dừng duy nhất (ExoPlayer + engine TTS) trước khi nghe tóm tắt.
                playerVm.stopAll()
                val readLang = collection.resolveLang(book)
                val sp = if (readLang == "vi") prefsState.storySpeed else prefsState.voiceSpeed
                val key = prefsState.cloudTtsApiKey.takeIf { it.isNotBlank() }
                if (prefsState.ttsPriority != "offline" && !key.isNullOrBlank()) {
                    val voice = if (readLang == "en") com.bibot.app.core.tts.CloudTtsBridge.DEFAULT_EN_VOICE
                    else com.bibot.app.core.tts.CloudTtsBridge.DEFAULT_VI_VOICE
                    val bytes = try {
                        com.bibot.app.core.tts.CloudTtsBridge.synthesize(text, key, voice = voice, speakingRate = sp)
                    } catch (_: Exception) { null }
                    if (bytes != null) {
                        playerVm.playBytes(bytes)
                        return@launch
                    }
                }
                try { playerVm.speakOffline(text, sp, lang = readLang) } catch (_: Exception) {}
            } catch (_: Exception) {}
        }
    }

    // B5: tóm tắt cấp sách chạy nền (WorkManager) + poll tiến trình vào dialog.
    fun startBookSummary() {
        val book = selectedBook ?: return
        summaryJob?.cancel()
        summaryIsBook = true
        summaryTitle = book.title
        summaryOpen = true
        summaryState = SummaryUiState.Loading(0, chapters.size.coerceAtLeast(1))
        com.bibot.app.data.summary.SummaryWorker.schedule(context, book.id)
        summaryJob = scope.launch(Dispatchers.IO) {
            try {
                val wm = androidx.work.WorkManager.getInstance(context)
                val name = com.bibot.app.data.summary.SummaryWorker.uniqueName(book.id)
                while (true) {
                    delay(2500)
                    val infos = try {
                        wm.getWorkInfosForUniqueWork(name)
                            .get(30, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: Exception) { break }
                    val info = infos.lastOrNull() ?: break
                    when (info.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            val t = info.outputData.getString(
                                com.bibot.app.data.summary.SummaryWorker.OUT_TEXT
                            )
                            summaryState = if (!t.isNullOrBlank()) SummaryUiState.Content(t, false)
                            else SummaryUiState.Error("error")
                            refreshSummarized(book.id)
                            break
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            when (info.outputData.getString(
                                com.bibot.app.data.summary.SummaryWorker.OUT_ERROR
                            )) {
                                "nocredit" -> summaryState =
                                    SummaryUiState.Gate(false, adsPrefs.getRewardedSummary())
                                "nokey" -> summaryState = SummaryUiState.Gate(true, 0)
                                else -> summaryState = SummaryUiState.Error("error")
                            }
                            break
                        }
                        androidx.work.WorkInfo.State.CANCELLED -> {
                            summaryState = SummaryUiState.Error("cancelled")
                            break
                        }
                        else -> {
                            val d = info.progress.getInt(
                                com.bibot.app.data.summary.SummaryWorker.PROG_DONE, 0
                            )
                            val t = info.progress.getInt(
                                com.bibot.app.data.summary.SummaryWorker.PROG_TOTAL, 0
                            )
                            if (t > 0) summaryState = SummaryUiState.Loading(d, t)
                        }
                    }
                }
            } catch (_: Exception) { summaryState = SummaryUiState.Error("error") }
        }
    }

    // Thực thi nhảy, không hỏi (dùng trong dialog Có / khi đang dừng).
    fun jumpNow(idx: Int, sentence: Int = 0) {
        if (idx !in chapters.indices) return
        pendingJump = null // dialog Có → đóng hộp
        jumpChapterReq.intValue = idx
        jumpSentenceReq.intValue = sentence.coerceAtLeast(0)
        jumpSeq.intValue++
        // A3.2: nhay vi tri → cắt cả ExoPlayer lẫn engine TTS (truoc chi stop ExoPlayer,
        // cau offline doc het doan cu rui moi nhay → doc chồng/vuot qua vi tri moi).
        playerVm.stopAll()
        audioStarted = false
        status = if (idx == currentChapter) context.getString(R.string.reader_jump_sentence, sentence.coerceAtLeast(0) + 1)
        else context.getString(R.string.reader_jump_chapter, idx + 1, chapters.size, chapters[idx].title)
    }

    // B3.3: đổi chương/câu khi đang READ → hỏi kiểu ReadEra (Không/Có).
    // Đang dừng (BROWSE/pause) → chuyển ngay như cũ.
    fun requestJump(idx: Int, sentence: Int = 0) {
        if (idx !in chapters.indices) return
        if (!isReading) { switchChapter(idx, sentence); return }
        if (readModeState != ReadMode.READ) { jumpNow(idx, sentence); return }
        if (idx == currentChapter && sentence == currentSentence) return
        pendingJump = JumpConfirm(
            context.getString(R.string.chrome_change_title),
            context.getString(R.string.chrome_change_msg)
        ) { jumpNow(idx, sentence) }
    }

    // Rev/Next: nhay doan ngan ± cau trong chuong (thang, khong hoi);
    // doi chuong → qua requestJump (hoi neu dang READ). Chuyen chuong bang TOC/scrubber.
    fun jumpSentences(delta: Int) {
        if (chapters.isEmpty() || sentences.isEmpty()) return
        val target = currentSentence + delta
        when {
            target < 0 -> if (currentChapter > 0) requestJump(currentChapter - 1, Int.MAX_VALUE)
            else { currentSentence = 0; status = context.getString(strAtStart) }
            target >= sentences.size -> if (currentChapter + 1 < chapters.size) requestJump(currentChapter + 1, 0)
            else { currentSentence = sentences.lastIndex; status = context.getString(R.string.reader_at_end) }
            else -> jumpNow(currentChapter, target)
        }
    }

    // F2: vào READ từ anchor đã xác nhận (không hỏi lại B3.3).
    fun startReading() {
        val book = selectedBook ?: return
        readModeState = ReadMode.READ
        audioStarted = false
        jumpSeq.intValue++
        playerVm.stopAll() // A3.2: cắt cả 2 nguồn trước khi vào READ
        isReading = true
        com.bibot.app.ui.player.KeepAwakeService.start(context)
        // F2.3: persist anchor + fileHash cùng progress (neo sống khi move file).
        scope.launch(Dispatchers.IO) {
            try {
                val a = selAnchor
                db.book().upsert(
                    com.bibot.app.data.db.BookProgress(
                        bookId = book.id,
                        path = book.localFile?.absolutePath ?: book.title,
                        position = currentChapter * 100 + currentSentence, // legacy giữ 1 bản
                        total = totalSentencesAll,
                        chapterIdx = currentChapter, // A2.4: vị trí thật
                        sentenceIdx = currentSentence,
                        anchorPage = a?.page ?: -1,
                        anchorOffset = a?.offset ?: 0,
                        readMode = "read",
                        fileHash = fileHashState
                    )
                )
            } catch (_: Exception) {}
        }
    }

    fun enterRead(anchor: ReadAnchor) {
        if (selectedBook == null || chapters.isEmpty()) return
        selAnchor = anchor
        anchorConfirm = null
        if (anchor.view == ReaderView.TEXT) {
            val chap = anchor.page.coerceIn(chapters.indices)
            currentChapter = chap
            scope.launch {
                val list = prepSession?.sentences(chap) ?: emptyList()
                sentences = list
                currentSentence = anchor.offset.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                startReading()
            }
        } else {
            // PAGE: PDF prep Bibot = 1 chương → map tỉ lệ trang → câu (ghi nhận F2).
            val total = totalSentencesAll
            val sent = if (pageCount > 0 && total > 0) {
                ((anchor.page + 0.5) / pageCount * total).toInt()
                    .coerceIn(0, (total - 1).coerceAtLeast(0))
            } else 0
            currentChapter = 0
            scope.launch {
                val list = prepSession?.sentences(0) ?: emptyList()
                sentences = list
                currentSentence = sent.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                startReading()
            }
        }
    }

    // F2.2: nút Play/FAB luôn đi qua neo + xác nhận (tránh chạm nhầm khi vuốt).
    fun startPlayFlow() {
        if (selectedBook == null || chapters.isEmpty()) return
        if (scanNoTts) {
            status = context.getString(
                if ((bookFile?.extension ?: "").equals("pdf", ignoreCase = true)) R.string.reader_pdf_scanned
                else R.string.reader_epub_scanned
            )
            return
        }
        // Đang pause giữa chừng (đã neo, chưa đổi chỗ) → đọc tiếp ngay, không hỏi lại.
        if (readModeState == ReadMode.READ && !isReading && selAnchor != null) {
            audioStarted = false
            jumpSeq.intValue++
            playerVm.stopAll() // A3.2: đọc tiếp → cắt cả 2 nguồn (tóm tắt/direct đang dở)
            isReading = true
            com.bibot.app.ui.player.KeepAwakeService.start(context)
            return
        }
        val anchor = selAnchor ?: if (viewMode == ReaderView.PAGE) ReadAnchor(ReaderView.PAGE, pageIndex, 0)
        else ReadAnchor(ReaderView.TEXT, currentChapter, currentSentence)
        anchorConfirm = anchor
    }

    fun stopAll() {
        isReading = false
        readModeState = ReadMode.BROWSE
        audioStarted = false
        anchorConfirm = null
        pendingJump = null
        // A3.2: 1 lệnh dừng duy nhất — ExoPlayer + engine TTS (không còn 2 dòng tách nhau).
        playerVm.stopAll()
        com.bibot.app.ui.player.KeepAwakeService.stop(context)
    }

    // Nút Play/FAB: pause ngay; play luôn qua neo + xác nhận (F2.2).
    // Đang pause giữa chừng (đã neo, chưa đổi chỗ) → đọc tiếp ngay.
    fun togglePlay() {
        if (isReading) {
            isReading = false
            // A3.2: 1 lệnh dừng duy nhất (ExoPlayer + engine TTS).
            playerVm.stopAll()
            com.bibot.app.ui.player.KeepAwakeService.stop(context)
        } else startPlayFlow()
    }

    // Mô tả anchor cho hộp xác nhận (TEXT chính xác / PAGE ước lượng tỉ lệ).
    fun anchorText(a: ReadAnchor): String {
        return if (a.view == ReaderView.TEXT) {
            val t = chapters.getOrNull(a.page)?.title ?: ""
            "C${a.page + 1} • câu ${a.offset + 1}" + if (t.isBlank()) "" else " — $t"
        } else {
            val pct = if (pageCount > 0) ((a.page + 0.5) / pageCount * 100).toInt().coerceIn(0, 100) else 0
            context.getString(R.string.chrome_page_anchor, a.page + 1, pct)
        }
    }

    // Tìm trong chương đang đọc: offset → câu (kỹ thuật như searchHeading).
    // B4.2 mở rộng toàn sách + SearchWindow ±7 từ sau.
    fun runSearch(q: String) {
        searchHits = emptyList()
        searchPos = 0
        searchChap = -1
        val needle = q.trim().lowercase()
        if (needle.length < 2) return
        val chap = currentChapter
        scope.launch(Dispatchers.IO) {
            try {
                val content = prepSession?.content(chap) ?: ""
                val low = content.lowercase()
                val sents = sentences.toList()
                if (sents.isEmpty()) return@launch
                val found = mutableListOf<Int>()
                var from = 0
                var guard = 0
                while (guard < 200) {
                    guard++
                    val p = low.indexOf(needle, from)
                    if (p < 0) break
                    var acc = 0
                    var idx = sents.size - 1
                    for (i in sents.indices) {
                        if (p < acc + sents[i].length + 1) { idx = i; break }
                        acc += sents[i].length + 1
                    }
                    if (found.isEmpty() || found.last() != idx) found.add(idx)
                    from = p + needle.length
                }
                if (found.isNotEmpty() && chap == currentChapter) {
                    searchHits = found.take(100)
                    searchPos = 0
                    searchChap = chap
                }
            } catch (_: Exception) {}
        }
    }

    // B4.2: Tìm kiếm toàn sách (không chỉ chương hiện tại) — dùng SearchWindow ±7 từ (A2.4)
    suspend fun runSearchFullBook(q: String): List<com.bibot.app.core.text.TextCleaner.SearchWindow> {
        val needle = q.trim().lowercase()
        if (needle.length < 2) return emptyList()
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<com.bibot.app.core.text.TextCleaner.SearchWindow>()
            val session = prepSession ?: return@withContext emptyList()
            val book = selectedBook ?: return@withContext emptyList()
            val file = book.localFile ?: return@withContext emptyList()
            
            // Lấy tất cả nội dung sách (suspend call) — nguyên bản (khớp display).
            val content = session.contentAll() ?: BlockTextReader.readOriginalChapters(
                file, book.title, BlockTextReader.Heading.MARKDOWN_OR_STORY
            ).joinToString("\n") { it.content }
            val low = content.lowercase()
            
            // Tìm tất cả vị trí
            var from = 0
            while (true) {
                val p = low.indexOf(needle, from)
                if (p < 0) break
                // Tạo hitbox từ char offset
                val hitbox = com.bibot.app.core.text.TextCleaner.hitboxFromCharOffset(content, p, p + needle.length)
                if (hitbox != null) {
                    // Tạo SearchWindow ±7 từ
                    val totalWords = content.split(Regex("\\s+")).size
                    val window = com.bibot.app.core.text.TextCleaner.searchWindow(hitbox, totalWords)
                    results.add(window)
                }
                from = p + needle.length
                if (results.size >= 50) break
            }
            results
        }
    }

    fun goHit(step: Int) {
        val hits = searchHits
        if (hits.isEmpty() || searchChap != currentChapter) return
        val next = ((searchPos + step) % hits.size + hits.size) % hits.size
        searchPos = next
        val idx = hits[next]
        if (isReading) jumpNow(currentChapter, idx)
        else currentSentence = idx.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
    }

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
        searchHits = emptyList()
        searchPos = 0
        searchChap = -1
    }

    // "# search" heading cũ (dùng chung findNext/PrevHeading) — giữ cho B4.
    // (content chương hiện tại đọc từ `.prep` trên IO — không còn giữ trong RAM)
    fun searchHeading(direction: Int) { // 1 = next, -1 = prev
        val session = prepSession ?: return
        if (currentChapter !in chapters.indices) return
        scope.launch {
            val content = session.content(currentChapter)
            val approxPos = if (sentences.isNotEmpty() && currentSentence < sentences.size) {
                content.indexOf(sentences[currentSentence])
            } else 0

            val newPos = if (direction > 0) findNextHeading(content, approxPos) else findPrevHeading(content, approxPos)

            newPos?.let { pos ->
                val textBefore = content.substring(0, pos)
                val sentenceCount = textBefore.split(Regex("[.!?…]\\s*")).size - 1
                val targetSentence = sentenceCount.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
                if (isReading) requestJump(currentChapter, targetSentence) else currentSentence = targetSentence
            } ?: run {
                if (direction > 0) {
                    if (currentChapter + 1 < chapters.size) requestJump(currentChapter + 1, 0)
                    else status = context.getString(R.string.reader_at_end)
                } else {
                    if (currentChapter > 0) requestJump(currentChapter - 1, 0)
                    else status = context.getString(strAtStart)
                }
            }
        }
    }

    LaunchedEffect(isReading) {
        // F2.1: producer TTS chỉ chạy ở mode READ (BROWSE chỉ render, không chunking).
        // B6.14: đọc liên tục, không ngắt interstitial giữa chừng (chỉ banner + rewarded Cloud TTS).
        if (!isReading || readModeState != ReadMode.READ || selectedBook == null || chapters.isEmpty()) {
            return@LaunchedEffect
        }
        val book = selectedBook ?: return@LaunchedEffect
        // P1: câu từng chương lấy từ session (.sent cache / seek .prep) — không split content trong RAM
        val session = prepSession ?: return@LaunchedEffect
        val readLang = collection.resolveLang(book)
        val speed = if (readLang == "vi") prefsState.storySpeed else prefsState.voiceSpeed
        val totalSentences = totalSentencesAll
        val useCloud = prefsState.ttsPriority != "offline"
        // P0-1: key Cloud TTS riêng (Gemini key ≠ TTS key)
        val geminiKey = prefsState.cloudTtsApiKey.takeIf { it.isNotBlank() }
        val voice = if (readLang == "en") CloudTtsBridge.DEFAULT_EN_VOICE else CloudTtsBridge.DEFAULT_VI_VOICE
        val cloudOn = useCloud && !geminiKey.isNullOrBlank()
        // Rewarded: mo Cloud theo chuong (chi demo co ads; VIP khong dung nhanh nay)
        val rewardedCloudOk = com.bibot.app.BuildConfig.ENABLE_ADS && !geminiKey.isNullOrBlank() && !vipActive
        var rewardedLeft = try { adsPrefs.getRewardedCloudChapters() } catch (_: Exception) { 0 }
        var rewardedForChap = rewardedLeft > 0
        val piperOn = prefsState.storyPreferPiper &&
            com.bibot.app.core.tts.PiperTtsHelper.isVoiceComplete(context, readLang)
        // A1.1/A1.2/A1.4: voice-resolve TRƯỚC khi plan — snapshot 1 lần cho cả lượt
        // đọc (ordinal là identity resume, xem segments.go/TtsSegments.kt).
        // settingsHash tách theo voice/speed: đổi voice rồi switch-back trúng cache cũ.
        val resolved = com.bibot.app.core.tts.TtsVoiceResolve.resolve(
            readLang, prefsState, context,
            rewardedCloud = rewardedCloudOk && rewardedForChap, vipBypass = vipActive
        )
        degradedEngine = resolved.degraded
        val segSettingsHash = com.bibot.app.core.tts.TtsSegments.settingsHash(
            com.bibot.app.core.tts.TtsSettings(
                voice = resolved.voiceId, speed = speed.toString(),
                format = resolved.format, language = readLang
            ),
            com.bibot.app.core.tts.TtsSegmentation(maxBlockLength = 200, language = readLang)
        )
        val segCache = com.bibot.app.core.tts.TtsAudioCache(context, db)
        val segDocId = book.id.ifBlank { book.title }
        val segDocVersion = try { book.localFile?.lastModified() ?: 0L } catch (_: Exception) { 0L }
        val segPrefix = com.bibot.app.core.tts.TtsSegments.keyPrefix(
            segDocId,
            com.bibot.app.core.tts.TtsSegments.readerTypeFor(book.localFile?.name ?: book.title)
        )
        val ttsChannel = Channel<ReaderTtsItem>(capacity = 2)
        var finishedAll = false

        val producer = launch {
            var chapIdx = currentChapter
            var sentIdx = currentSentence
            var localSentences = sentences
            // A (tách display/TTS): chưa tới mốc bắt đầu (Lời giới thiệu/Chương 1)
            // thì lướt (không đọc phần đầu/mục lục); display vẫn hiện đủ.
            var ttsStarted = firstMarkerChapIdx < 0 || currentChapter >= firstMarkerChapIdx
            try {
                while (isActive && isReading && chapIdx < chapters.size) {
                    val jc = jumpChapterReq.intValue
                    if (jc >= 0) {
                        jumpChapterReq.intValue = -1
                        chapIdx = jc
                        localSentences = session.sentences(chapIdx)
                        currentChapter = chapIdx
                        sentences = localSentences
                        sentIdx = jumpSentenceReq.intValue.coerceIn(0, (localSentences.size - 1).coerceAtLeast(0))
                        currentSentence = sentIdx
                        ttsStarted = firstMarkerChapIdx < 0 || chapIdx >= firstMarkerChapIdx
                        continue
                    }
                    val seqAtStart = jumpSeq.intValue
                    if (sentIdx >= localSentences.size) {
                        // Het chuong: tru 1 luot rewarded neu chuong vua doc dung giong Cloud tang
                        if (rewardedForChap && rewardedLeft > 0) {
                            rewardedLeft--
                            try { adsPrefs.setRewardedCloudChapters(rewardedLeft) } catch (_: Exception) {}
                        }
                        chapIdx++
                        if (chapIdx >= chapters.size) break
                        localSentences = session.sentences(chapIdx)
                        currentChapter = chapIdx
                        sentences = localSentences
                        sentIdx = 0
                        currentSentence = 0
                        if (firstMarkerChapIdx >= 0 && chapIdx >= firstMarkerChapIdx) ttsStarted = true
                        rewardedForChap = rewardedLeft > 0
                        delay(READER_PAUSE_BETWEEN_MS)
                        continue
                    }
                    val rawSent = localSentences[sentIdx]
                    if (!ttsStarted && firstMarkerChapIdx >= 0) {
                        if (com.bibot.app.core.text.BlockTextReader.isReadingStartMarker(chapters.getOrNull(chapIdx)?.title ?: "") ||
                            com.bibot.app.core.text.BlockTextReader.isReadingStartMarker(rawSent)
                        ) {
                            ttsStarted = true
                        } else {
                            sentIdx++; continue
                        }
                    }
                    // Bỏ dòng rác (số trang đứng riêng, mục-lục-dòng) còn sót trong câu.
                    val speakable = com.bibot.app.core.text.BlockTextReader.ttsSpeakable(rawSent)
                    if (speakable.isBlank()) { sentIdx++; continue }
                    val clean = com.bibot.app.core.text.TextCleaner.cleanForTts(speakable, readLang)
                    if (clean.isBlank()) { sentIdx++; continue }
                    if (com.bibot.app.core.text.TextCleaner.looksLikeThinking(clean)) { sentIdx++; continue }
                    val (plain, needPause) = com.bibot.app.core.text.TextCleaner.splitPause(clean)
                    if (plain.isBlank()) {
                        if (needPause) delay(2000)
                        sentIdx++; continue
                    }
                    val pauseMs = if (needPause) 2000L else 0L
                    // D-verify máy thật (tạm, gated DEBUG): text cuối gửi engine + ruler sẵn sàng?
                    if (com.bibot.app.BuildConfig.DEBUG) android.util.Log.d(
                        "BibotDbg",
                        "speak c$chapIdx s$sentIdx ruler=${com.bibot.app.core.text.ReadingRules.isReady()} lang=$readLang text=$plain"
                    )
                    var pcm: ByteArray? = null
                    var mime = "audio/mp3"
                    // A1.2: lookup content-addressed TRƯỚC khi synth — cùng text+voice
                    // (+speed) ở bất kỳ vị trí/chương nào dùng chung 1 file audio.
                    var segAudioHash: String? = null
                    var segFromCache = false
                    if (piperOn || cloudOn || (rewardedCloudOk && rewardedForChap) || (vipActive && !geminiKey.isNullOrBlank())) {
                        try {
                            val segKey = com.bibot.app.core.tts.TtsSegments.segmentKey(segPrefix, plain)
                            segAudioHash = com.bibot.app.core.tts.TtsSegments.audioContentHash(
                                segDocId, segDocVersion, segSettingsHash, segKey,
                                com.bibot.app.core.tts.TtsSegments.normalizeContentText(plain), ""
                            )
                            val hit = segCache.lookup(segSettingsHash, segAudioHash)
                            if (hit != null) {
                                val hb = hit.readBytes()
                                if (hb.isNotEmpty()) {
                                    pcm = hb
                                    mime = if (hit.extension.equals("wav", ignoreCase = true)) "audio/wav" else "audio/mp3"
                                    segFromCache = true
                                }
                            }
                        } catch (_: Exception) { pcm = null; segFromCache = false }
                    }
                    if (piperOn && !segFromCache) {
                        pcm = try { com.bibot.app.core.tts.PiperTtsHelper.synthesize(context, plain, readLang) } catch (_: Exception) { null }
                        if (pcm != null) mime = "audio/wav"
                    }
                    if ((pcm == null || pcm.isEmpty()) && !segFromCache && (cloudOn || (rewardedCloudOk && rewardedForChap) || (vipActive && !geminiKey.isNullOrBlank()))) {
                        pcm = try { CloudTtsBridge.synthesize(plain, geminiKey, voice = voice, speakingRate = speed) } catch (_: Exception) { null }
                        mime = "audio/mp3"
                    }
                    if (pcm != null && pcm.isNotEmpty()) {
                        // Miss mà synth được → lưu cache fire-and-forget (không chặn phát).
                        if (!segFromCache && segAudioHash != null) {
                            val b = pcm; val h = segAudioHash; val m = mime
                            scope.launch(Dispatchers.IO) {
                                segCache.store(segSettingsHash, h, if (m.contains("wav")) "wav" else "mp3", b)
                            }
                        }
                        ttsChannel.send(ReaderTtsItem.Pcm(chapIdx, sentIdx, localSentences.size, chapters[chapIdx].title, pcm, plain.length, pauseMs, mime, seqAtStart))
                    } else {
                        ttsChannel.send(ReaderTtsItem.Direct(chapIdx, sentIdx, localSentences.size, chapters[chapIdx].title, plain, pauseMs, seqAtStart))
                    }
                    sentIdx++
                }
                if (chapIdx >= chapters.size) finishedAll = true
            } finally {
                ttsChannel.close()
            }
        }

        var spokenCount = 0
        try {
            for (item in ttsChannel) {
                ensureActive()
                if (!isReading) break
                if (item.jumpSeq != jumpSeq.intValue) continue
                // Karaoke sync: TO KHI AUDIO START (khong +1 truoc, khong to som luc cho synth).
                when (item) {
                    is ReaderTtsItem.Pcm -> {
                        val mid = "${collection.audioPrefix}${item.chapIdx}_${item.sentIdx}_${item.jumpSeq}"
                        playerVm.enqueueBytes(item.bytes, item.mime, mid)
                        awaitAudioStart(playerVm, mid, (item.textLen * 60L).coerceAtLeast(700L)) { item.jumpSeq != jumpSeq.intValue }
                        if (item.jumpSeq != jumpSeq.intValue) continue
                        currentSentence = item.sentIdx
                        audioStarted = true
                        status = context.getString(strStatus, item.title, item.sentIdx + 1, item.sentTotal)
                        awaitAudioDone(playerVm, mid, (item.textLen * 60L).coerceAtLeast(700L)) { item.jumpSeq != jumpSeq.intValue }
                    }
                    is ReaderTtsItem.Direct -> {
                        currentSentence = item.sentIdx
                        audioStarted = true
                        status = context.getString(strStatus, item.title, item.sentIdx + 1, item.sentTotal)
                        // lang = sách EN → giọng en-US (không còn đọc tiếng Anh bằng giọng Việt)
                        // A3.2: đọc qua controller + ghi mediaId vào activeIds (đọc offline
                        // không còn bypass karaoke như trước).
                        val mid = "${collection.audioPrefix}${item.chapIdx}_${item.sentIdx}_${item.jumpSeq}"
                        try { playerVm.speakOffline(item.text, speed, lang = readLang, mediaId = mid) } catch (_: Exception) {}
                    }
                }
                if (item.pauseAfterMs > 0) delayUntilDone(item.pauseAfterMs) { item.jumpSeq != jumpSeq.intValue }
                spokenCount++
                // B3.4: lưu mỗi câu (không còn mất ~8 câu khi đổi tab); giữ anchor/fileHash.
                try {
                    db.book().upsert(
                        com.bibot.app.data.db.BookProgress(
                            bookId = book.id,
                            path = book.localFile?.absolutePath ?: book.title,
                            position = item.chapIdx * 100 + (item.sentIdx + 1), // legacy giữ 1 bản
                            total = totalSentences,
                            chapterIdx = item.chapIdx, // A2.4: vị trí thật (đúng >100 câu/chương)
                            sentenceIdx = item.sentIdx + 1,
                            anchorPage = selAnchor?.page ?: -1,
                            anchorOffset = selAnchor?.offset ?: 0,
                            readMode = "read",
                            fileHash = fileHashState
                        )
                    )
                } catch (_: Exception) {}
                // Tạm nghỉ mỗi câu — dài hơn nếu câu kết thúc bằng dấu phẩy
                // B6.14: không ngắt interstitial giữa chừng — đọc liên tục.
                val spokenText = if (item is ReaderTtsItem.Direct) item.text else ""
                val isComma = spokenText.trimEnd().endsWith(",") || spokenText.trimEnd().endsWith("，")
                delay(if (isComma) 250 else 180)
            }
        } finally {
            try { producer.cancel() } catch (_: Exception) {}
        }
        if (isReading && finishedAll) {
            status = context.getString(strDone, book.title)
            currentSentence = sentences.size // qua cuối → tắt highlight câu cuối
            isReading = false
        }
    }

    if (selectedBook == null) {
    if (collection == ReaderCollection.EN) {
        // Module "Sách tiếng Anh": hiện EN + local/link. Sách folder VI (source=pdrive, lang=vi)
        // thuộc module Kể chuyện → ẩn, tránh danh sách 2 module giống hệt nhau.
        val enBooks = books.filter { it.lang == "en" }
        val otherBooks = books.filterNot { it.lang == "en" || (it.lang == "vi" && it.source == "pdrive") }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                // Hàng Back về Home (thay nút Home đã bỏ ở bottom bar).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { stopAll(); onBackHome() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.nav_back_home))
                    }
                    Text(
                        stringResource(strListTitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                // Gọn UI: bỏ tiêu đề + chú thích (đã có trên top bar) — chỉ giữ 1 dòng đếm EN
                if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.home_book_count, enBooks.size + otherBooks.size), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { scope.launch { repo.refreshAll(); status = repo.lastSyncWarning.value ?: context.getString(R.string.book_refreshed, repo.books.value.size) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.book_refresh), fontSize = 12.sp)
                    }
                    FilledTonalButton(onClick = { scope.launch { repo.refreshAll(); status = context.getString(R.string.book_scanned, repo.localBooks().size) } }) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.book_scan_local), fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                var urlInput by remember { mutableStateOf("") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = urlInput, onValueChange = { urlInput = it },
                        label = { Text(stringResource(R.string.book_url_label)) },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    Button(onClick = {
                        if (urlInput.isBlank()) { status = context.getString(R.string.book_enter_url_first); return@Button }
                        scope.launch { status = context.getString(R.string.reader_loading_url); try { val f = repo.downloadFromUrl(urlInput.trim()); status = context.getString(R.string.book_downloaded, f.name); repo.refreshAll() } catch (e: Exception) { status = context.getString(R.string.reader_error, e.message ?: "") } }
                    }) { Text(stringResource(R.string.book_download), fontSize = 12.sp) }
                }
            }
            if (enBooks.isNotEmpty()) {
                item { Text(stringResource(R.string.book_section_en, enBooks.size), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            }
            items(enBooks, key = { it.id }) { book ->
                ReaderCard(book = book, onLoad = { loadBook(book) }, onDownload = { scope.launch { repo.download(book); repo.refreshAll(); status = context.getString(R.string.book_download_done, book.title) } }, onDelete = { scope.launch { repo.delete(book); repo.refreshAll() } })
            }
            if (otherBooks.isNotEmpty()) {
                item { Text(stringResource(R.string.book_section_local, otherBooks.size), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            }
            items(otherBooks, key = { it.id }) { book ->
                ReaderCard(book = book, onLoad = { loadBook(book) }, onDownload = { scope.launch { repo.download(book); repo.refreshAll(); status = context.getString(R.string.book_download_done, book.title) } }, onDelete = { scope.launch { repo.delete(book); repo.refreshAll() } })
            }
            item {
                // Chỉ 1 vạch loading (ở đầu list) — bỏ vạch cuối list (trùng khi kệ trống).
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
    } else {
        // R0: kho Ke chuyen / Co tich / Tham khao - list don (khong nut quan ly)
        val visibleBooks = books.filter { collection.filter(it) }
        val listEmoji = if (collection == ReaderCollection.COTICH) "\uD83E\uDDDA" else "\uD83D\uDCD6"
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                // Hàng Back về Home (thay nút Home đã bỏ ở bottom bar).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { stopAll(); onBackHome() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.nav_back_home))
                    }
                    Text(
                        stringResource(strListTitle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.home_story_count, visibleBooks.size), style = MaterialTheme.typography.labelMedium)
                if (visibleBooks.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (collection == ReaderCollection.COTICH) stringResource(R.string.home_cotich_empty)
                        else stringResource(R.string.home_story_empty),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            items(visibleBooks, key = { it.id }) { story ->
                Card(modifier = Modifier.fillMaxWidth().clickable { loadBook(story) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(listEmoji, fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(story.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(collection.resolveLang(story) + " \u2022 " + story.source + " \u2022 " + (if (story.isDownloaded) stringResource(R.string.book_is_downloaded) else stringResource(R.string.book_not_downloaded)) + " " + story.displaySize, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                        }
                        FilledTonalButton(onClick = { loadBook(story) }) { Text(stringResource(if (collection.useStoryStrings) R.string.reader_play_tell else R.string.reader_play_read), fontSize = 12.sp) }
                    }
                }
            }
            if (status != null) item {
                Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    } else {
        val book = selectedBook!!
        val uiLang = collection.resolveLang(book)
        val chap = chapters.getOrNull(currentChapter)
        // Giu man hinh sang trong luc doc (chong may TU nghi/ngu): tat khi Pause/Back/roi man hinh.
        // Bam nut nguon tat man hinh thu cong van duoc — audio doc nen nho WAKE_MODE_LOCAL.
        DisposableEffect(isReading) {
            if (isReading) {
                try { (context as? android.app.Activity)?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
            }
            onDispose {
                try { (context as? android.app.Activity)?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
            }
        }
        // B3.1: brightness trong app — trả về hệ thống khi rời màn đọc.
        val activity = context as? android.app.Activity
        // TEMP (B6 test aid): nút Back hệ thống về list khi đang mở sách
        // (ưu tiên đóng dialog/sheet trước). B2 sẽ đổi thành về Detail.
        // Ở kệ sách (chưa mở sách): Back → về Home (không còn nút Home dưới).
        val backDispatcher = (context as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher
        DisposableEffect(selectedBook) {
            val cb = object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (selectedBook == null) { stopAll(); onBackHome(); return }
                    when {
                        tocOpen -> tocOpen = false
                        anchorConfirm != null -> anchorConfirm = null
                        pendingJump != null -> pendingJump = null
                        settingsOpen -> settingsOpen = false
                        hintOpen -> hintOpen = false
                        else -> { stopAll(); selectedBook = null }
                    }
                }
            }
            backDispatcher?.addCallback(cb)
            onDispose { cb.remove() }
        }
        LaunchedEffect(brightness) {
            try {
                val win = activity?.window ?: return@LaunchedEffect
                val lp = win.attributes
                lp.screenBrightness = brightness ?: -1f
                win.attributes = lp
            } catch (_: Exception) {}
        }
        DisposableEffect(Unit) {
            onDispose {
                try {
                    val win = (context as? android.app.Activity)?.window
                    if (win != null) {
                        val lp = win.attributes
                        lp.screenBrightness = -1f
                        win.attributes = lp
                    }
                } catch (_: Exception) {}
            }
        }

        val playDesc = if (isReading) stringResource(R.string.reader_pause) else stringResource(strPlay)

        // A3.3: Floating control bar data
        val chapterText = (if (viewMode == ReaderView.PAGE && pageCount > 0)
            stringResource(R.string.chrome_page_of, pageIndex + 1, pageCount)
        else stringResource(R.string.chrome_chapter_of, currentChapter + 1, chapters.size)) +
            if (degradedEngine) " • offline" else ""
        val sentenceText = if (sentences.isNotEmpty())
            "${currentSentence + 1}/${sentences.size}" else ""
        val isLandscape = config.screenWidthDp > config.screenHeightDp

        // A3.3: Content area with optional floating control bar when reading
        @Composable
        fun ContentArea() {
            val bf = bookFile
            if (viewMode == ReaderView.PAGE && bf != null) {
                // Scope B: EPUB → rich (ảnh + format gốc, thay phân trang text);
                // PDF → ảnh trang. Karaoke câu chỉ ở TEXT.
                if (isEpubBook) {
                    val richIdx = epubRichTitles
                        .indexOfFirst { it == chapters.getOrNull(currentChapter)?.title }
                        .coerceAtLeast(0)
                    EpubRichView(
                        file = bf,
                        spineIdx = richIdx,
                        defaultTitle = chapters.getOrNull(currentChapter)?.title
                            ?: collection.fallbackTitle ?: "",
                        fontSizeSp = prefsState.readerFontSizeSp,
                        lineHeightMultiplier = prefsState.readerInterline,
                        paragraphMarginDp = prefsState.readerParagraphMargin,
                        sentences = sentences,
                        currentSentence = currentSentence,
                        karaokeActive = isReading && audioStarted,
                        onTap = { menuVisible = !menuVisible }
                    )
                } else {
                    PdfPageView(
                        file = bf,
                        initialPage = 0,
                        selectedPage = selAnchor?.takeIf { it.view == ReaderView.PAGE }?.page,
                        onVisiblePage = { pageIndex = it },
                        onPageCount = { pageCount = it },
                        onPageTap = { menuVisible = !menuVisible },
                        onPageLongPress = { idx ->
                            val a = ReadAnchor(ReaderView.PAGE, idx, 0)
                            selAnchor = a
                            anchorConfirm = a
                        }
                    )
                }
            } else {
                val listState = rememberLazyListState()
                val density = LocalDensity.current
                LaunchedEffect(currentSentence, isReading) {
                    if (isReading && sentences.isNotEmpty()) {
                        // Gop nhip 350ms + bu header + can giua 1/3 man hinh (chung):
                        // cau dau ngan/tieu de khong bi luot qua truoc khi mat kip nhin.
                        delay(350)
                        val total = listState.layoutInfo.totalItemsCount
                        val idx = (currentSentence.coerceIn(0, sentences.lastIndex) + 1)
                            .coerceIn(0, (total - 1).coerceAtLeast(0))
                        try {
                            val center = -(config.screenHeightDp * density.density / 3).toInt()
                            listState.animateScrollToItem(idx, center)
                        } catch (_: Exception) {}
                    }
                }
                // Tự cuộn tới hit tìm kiếm trong chương.
                val hitIdx = if (searchOpen && searchChap == currentChapter) searchHits.getOrNull(searchPos) else null
                LaunchedEffect(hitIdx, searchOpen) {
                    if (searchOpen && hitIdx != null && sentences.isNotEmpty()) {
                        try {
                            listState.animateScrollToItem(
                                (hitIdx + 1).coerceIn(0, (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                            )
                        } catch (_: Exception) {}
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Lề hẹp 10dp kiểu ReadEra (was 16dp): thêm ~12dp ngang
                    // cho font lớn hơn mà không xuống dòng sớm; BODY căn đều.
                    contentPadding = PaddingValues(top = 4.dp, start = 10.dp, end = 10.dp, bottom = contentBottomInset()),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    item(key = "head-$currentChapter") {
                        chap?.let {
                            Text(
                                it.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { menuVisible = !menuVisible }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    itemsIndexed(sentences, key = { idx, _ -> "$currentChapter-$idx" }) { idx, s ->
                        val isCurrent = idx == currentSentence && isReading && audioStarted
                        val isSel = selAnchor?.let {
                            it.view == ReaderView.TEXT && it.page == currentChapter && it.offset == idx
                        } == true
                        // Hit tìm kiếm trong chương (vàng nổi bật + tự cuộn tới).
                        val isHit = searchOpen && searchChap == currentChapter &&
                            searchHits.getOrNull(searchPos) == idx
                        val (displayText, hasPause) = com.bibot.app.core.text.TextCleaner.splitPause(s)
                        Column(
                            modifier = Modifier
                                .padding(vertical = 3.dp)
                                .padding(bottom = prefsState.readerParagraphMargin.dp)
                        ) {
                            Text(
                                if (hasPause) "$displayText ⏸2s" else displayText,
                                fontSize = prefsState.readerFontSizeSp.sp,
                                lineHeight = (prefsState.readerFontSizeSp * prefsState.readerInterline).sp,
                                textAlign = TextAlign.Justify,
                                color = if (isCurrent || isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isCurrent || isSel || isHit) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .background(
                                        if (isHit) MaterialTheme.colorScheme.tertiaryContainer
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                        RoundedCornerShape(2.dp)
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            if (readModeState == ReadMode.BROWSE) {
                                                selAnchor = ReadAnchor(ReaderView.TEXT, currentChapter, idx)
                                            } else menuVisible = !menuVisible
                                        },
                                        onLongClick = {
                                            val a = ReadAnchor(ReaderView.TEXT, currentChapter, idx)
                                            selAnchor = a
                                            anchorConfirm = a
                                        }
                                    )
                            )
                            // Native tinh giua ky (TTS Bypass): label EN cho sach EN
                            val adIdx = if (collection == ReaderCollection.COTICH) com.bibot.app.ui.components.ads.NativeInlineAd.INDEX_COTICH else com.bibot.app.ui.components.ads.NativeInlineAd.INDEX_DEFAULT
                            if (idx == adIdx && sentences.size > adIdx) {
                                Spacer(Modifier.height(4.dp))
                                com.bibot.app.ui.components.ads.NativeInlineAd(lang = uiLang, isFamily = collection == ReaderCollection.COTICH)
                            }
                        }
                    }
                    item(key = "status-$currentChapter") {
                        status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                    }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // B3.1: full-screen khi đọc — chrome ẩn/hiện bằng chạm giữa nội dung.
                // Landscape gom top + điều hướng vào 1 hàng 48dp (màn ngang thấp).
                val chapterLine = (if (viewMode == ReaderView.PAGE && pageCount > 0)
                    stringResource(R.string.chrome_page_of, pageIndex + 1, pageCount)
                else stringResource(R.string.chrome_chapter_of, currentChapter + 1, chapters.size)) +
                    if (degradedEngine) " • offline" else ""
                if (menuVisible) {
                    if (isLandscape) {
                        ReaderCompactBar(
                            title = "${collection.headerEmoji} ${book.title}",
                            chapterText = chapterLine,
                            isReading = isReading,
                            playEnabled = !scanNoTts,
                            onBack = { stopAll(); selectedBook = null },
                            onTogglePlay = { togglePlay() },
                            onSearch = { searchQuery = ""; searchHits = emptyList(); searchOpen = true },
                            onToc = { tocOpen = true },
                            onSettings = { settingsOpen = true },
                            onPrevChapter = {
                                if (currentChapter > 0) requestJump(currentChapter - 1)
                                else status = context.getString(strAtStart)
                            },
                            onNextChapter = {
                                if (currentChapter + 1 < chapters.size) requestJump(currentChapter + 1)
                                else status = context.getString(R.string.reader_at_end)
                            },
                            playLabel = playDesc
                        )
                    } else {
                        ReaderTopBar(
                            title = "${collection.headerEmoji} ${book.title}",
                            isReading = isReading,
                            playEnabled = !scanNoTts,
                            pageMode = (isPdfBook || isEpubBook) && !viewLockedPage,
                            onBack = { stopAll(); selectedBook = null },
                            onTogglePlay = { togglePlay() },
                            onSearch = { searchQuery = ""; searchHits = emptyList(); searchOpen = true },
                            onToc = { tocOpen = true },
                            onToggleView = { settingsOpen = true },
                            playLabel = playDesc
                        )
                    }
                    // B6.7: thanh chỉ báo mảnh sát dưới top-bar (hiển thị-only).
                    // Đây là vạch tiến trình DUY NHẤT (bar chương không còn vạch riêng).
                    val posFraction = if (viewMode == ReaderView.PAGE && pageCount > 0) {
                        (pageIndex + 1).toFloat() / pageCount
                    } else if (chapters.isNotEmpty() && sentences.isNotEmpty()) {
                        (currentChapter + (currentSentence + 1).toFloat() / sentences.size) / chapters.size
                    } else if (chapters.isNotEmpty()) {
                        (currentChapter + 1).toFloat() / chapters.size
                    } else 0f
                    ReaderPositionBar(fraction = posFraction)
                    // Cụm chương gọn portrait: 1 dòng text + 2 mũi tên (không vạch riêng).
                    if (!isLandscape) {
                        ReaderBottomBar(
                            chapterText = chapterLine,
                            onPrevChapter = {
                                if (currentChapter > 0) requestJump(currentChapter - 1)
                                else status = context.getString(strAtStart)
                            },
                            onNextChapter = {
                                if (currentChapter + 1 < chapters.size) requestJump(currentChapter + 1)
                                else status = context.getString(R.string.reader_at_end)
                            }
                        )
                    }
                }
                // Tìm trong chương: thanh kết quả + ↑ ↓ (B4.2 mở rộng toàn sách sau).
                if (searchOpen) {
                    val hitCount = if (searchChap == currentChapter) searchHits.size else 0
                    ReaderSearchBar(
                        query = searchQuery,
                        onQuery = { searchQuery = it; runSearch(it) },
                        hitText = if (hitCount == 0) "" else "${searchPos + 1}/$hitCount",
                        hasPrevNext = hitCount > 1,
                        onPrevHit = { goHit(-1) },
                        onNextHit = { goHit(1) },
                        onClose = { closeSearch() }
                    )
                }
                // F2.1: chip đọc tiếp khi mở sách đã đọc mà chưa neo.
                if (readModeState == ReadMode.BROWSE && chapters.isNotEmpty() &&
                    (savedAnchor != null || savedResume.isStarted || savedPos > 0)
                ) {
                    TextButton(
                        onClick = {
                            val a = savedAnchor ?: ReadAnchor(
                                ReaderView.TEXT,
                                savedResume.chap.coerceIn(chapters.indices),
                                savedResume.sent.coerceAtLeast(0)
                            )
                            anchorConfirm = a
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(
                            if (viewMode == ReaderView.PAGE && savedAnchor?.view == ReaderView.PAGE)
                                stringResource(
                                    R.string.chrome_page_of,
                                    (savedAnchor?.page ?: 0) + 1,
                                    pageCount.coerceAtLeast(1)
                                )
                            else stringResource(R.string.chrome_resume, savedResume.sent.coerceAtLeast(0) + 1),
                            fontSize = 12.sp
                        )
                    }
                }
                // F2.2: thanh neo sau khi chạm/long-press (tránh chạm nhầm khi vuốt).
                if (readModeState == ReadMode.BROWSE && selAnchor != null && !isReading) {
                    AnchorBar(
                        label = anchorText(selAnchor!!),
                        onConfirm = { anchorConfirm = selAnchor },
                        onCancel = { selAnchor = null }
                    )
                } else if (readModeState == ReadMode.BROWSE && selAnchor == null && chapters.isNotEmpty()) {
                    Text(
                        stringResource(R.string.chrome_browse_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        // B6.3: vuốt ngang đổi chương (dialog khi READ, thẳng khi dừng).
                        .pointerInput(currentChapter, chapters.size) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragCancel = { total = 0f },
                                onDragEnd = {
                                    if (total < -120) {
                                        if (currentChapter + 1 < chapters.size) requestJump(currentChapter + 1)
                                        else status = context.getString(R.string.reader_at_end)
                                    } else if (total > 120) {
                                        if (currentChapter > 0) requestJump(currentChapter - 1)
                                        else status = context.getString(strAtStart)
                                    }
                                    total = 0f
                                },
                                onHorizontalDrag = { _, amount -> total += amount }
                            )
                        }
                ) {
                    val bf = bookFile
                    if (viewMode == ReaderView.PAGE && bf != null) {
                        // Scope B: EPUB → rich (ảnh + format gốc, thay phân trang text);
                        // PDF → ảnh trang. Karaoke câu chỉ ở TEXT.
                        if (isEpubBook) {
                            val richIdx = epubRichTitles
                                .indexOfFirst { it == chapters.getOrNull(currentChapter)?.title }
                                .coerceAtLeast(0)
                            EpubRichView(
                                file = bf,
                                spineIdx = richIdx,
                                defaultTitle = chapters.getOrNull(currentChapter)?.title
                                    ?: collection.fallbackTitle ?: "",
                                fontSizeSp = prefsState.readerFontSizeSp,
                                lineHeightMultiplier = prefsState.readerInterline,
                                paragraphMarginDp = prefsState.readerParagraphMargin,
                                sentences = sentences,
                                currentSentence = currentSentence,
                                karaokeActive = isReading && audioStarted,
                                onTap = { menuVisible = !menuVisible }
                            )
                        } else {
                            PdfPageView(
                                file = bf,
                                initialPage = 0,
                                selectedPage = selAnchor?.takeIf { it.view == ReaderView.PAGE }?.page,
                                onVisiblePage = { pageIndex = it },
                                onPageCount = { pageCount = it },
                                onPageTap = { menuVisible = !menuVisible },
                                onPageLongPress = { idx ->
                                    val a = ReadAnchor(ReaderView.PAGE, idx, 0)
                                    selAnchor = a
                                    anchorConfirm = a
                                }
                            )
                        }
                    } else {
                        val listState = rememberLazyListState()
                        val density = LocalDensity.current
                        LaunchedEffect(currentSentence, isReading) {
                            if (isReading && sentences.isNotEmpty()) {
                                // Gop nhip 350ms + bu header + can giua 1/3 man hinh (chung):
                                // cau dau ngan/tieu de khong bi luot qua truoc khi mat kip nhin.
                                delay(350)
                                val total = listState.layoutInfo.totalItemsCount
                                val idx = (currentSentence.coerceIn(0, sentences.lastIndex) + 1)
                                    .coerceIn(0, (total - 1).coerceAtLeast(0))
                                try {
                                    val center = -(config.screenHeightDp * density.density / 3).toInt()
                                    listState.animateScrollToItem(idx, center)
                                } catch (_: Exception) {}
                            }
                        }
                        // Tự cuộn tới hit tìm kiếm trong chương.
                        val hitIdx = if (searchOpen && searchChap == currentChapter) searchHits.getOrNull(searchPos) else null
                        LaunchedEffect(hitIdx, searchOpen) {
                            if (searchOpen && hitIdx != null && sentences.isNotEmpty()) {
                                try {
                                    listState.animateScrollToItem(
                                        (hitIdx + 1).coerceIn(0, (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            // Lề hẹp 10dp kiểu ReadEra (was 16dp): thêm ~12dp ngang
                            // cho font lớn hơn mà không xuống dòng sớm; BODY căn đều.
                            contentPadding = PaddingValues(top = 4.dp, start = 10.dp, end = 10.dp, bottom = contentBottomInset()),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            item(key = "head-$currentChapter") {
                                chap?.let {
                                    Text(
                                        it.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { menuVisible = !menuVisible }
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            itemsIndexed(sentences, key = { idx, _ -> "$currentChapter-$idx" }) { idx, s ->
                                val isCurrent = idx == currentSentence && isReading && audioStarted
                                val isSel = selAnchor?.let {
                                    it.view == ReaderView.TEXT && it.page == currentChapter && it.offset == idx
                                } == true
                                // Hit tìm kiếm trong chương (vàng nổi bật + tự cuộn tới).
                                val isHit = searchOpen && searchChap == currentChapter &&
                                    searchHits.getOrNull(searchPos) == idx
                                val (displayText, hasPause) = com.bibot.app.core.text.TextCleaner.splitPause(s)
Column(
                                     modifier = Modifier
                                         .padding(vertical = 3.dp)
                                         .padding(bottom = prefsState.readerParagraphMargin.dp)
                                 ) {
                                      Text(
                                          if (hasPause) "$displayText ⏸2s" else displayText,
                                          fontSize = prefsState.readerFontSizeSp.sp,
                                          lineHeight = (prefsState.readerFontSizeSp * prefsState.readerInterline).sp,
                                          textAlign = TextAlign.Justify,
                                         color = if (isCurrent || isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                         fontWeight = if (isCurrent || isSel || isHit) FontWeight.Bold else FontWeight.Normal,
                                         modifier = Modifier
                                             .background(
                                                 if (isHit) MaterialTheme.colorScheme.tertiaryContainer
                                                 else androidx.compose.ui.graphics.Color.Transparent,
                                                 RoundedCornerShape(2.dp)
                                             )
                                             .combinedClickable(
                                                onClick = {
                                                    if (readModeState == ReadMode.BROWSE) {
                                                        selAnchor = ReadAnchor(ReaderView.TEXT, currentChapter, idx)
                                                    } else menuVisible = !menuVisible
                                                },
                                                onLongClick = {
                                                    val a = ReadAnchor(ReaderView.TEXT, currentChapter, idx)
                                                    selAnchor = a
                                                    anchorConfirm = a
                                                }
                                            )
                                    )
                                    // Native tinh giua ky (TTS Bypass): label EN cho sach EN
                                    val adIdx = if (collection == ReaderCollection.COTICH) com.bibot.app.ui.components.ads.NativeInlineAd.INDEX_COTICH else com.bibot.app.ui.components.ads.NativeInlineAd.INDEX_DEFAULT
                                    if (idx == adIdx && sentences.size > adIdx) {
                                        Spacer(Modifier.height(4.dp))
                                        com.bibot.app.ui.components.ads.NativeInlineAd(lang = uiLang, isFamily = collection == ReaderCollection.COTICH)
                                    }
                                }
                            }
                            item(key = "status-$currentChapter") {
                                status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                            }
                        }
                    }
                }
            }
            // B3.2: 2 FAB một tay CÙNG UI (FloatingActionButton 56dp, nền primary alpha
            // 0.55) nhưng ĐỊNH VỊ ĐỘC LẬP: Back ở giữa màn hình (50% cao), Play ở giữa
            // khoảng dưới (tâm ≈75% cao: cách đáy Box 20% trừ nửa nút 28dp) — không gian
            // 1 tay rộng. VERIFY uiautomator 2026-10-15: Back [919,1104][1044,1229].
            FloatingActionButton(
                onClick = { if (selectedBook == null) { stopAll(); onBackHome() } else { stopAll(); selectedBook = null } },
                // Nền trong mờ như pill điều khiển (alpha 0.55) → chữ trôi bên dưới vẫn thấy.
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            ) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.reader_back)
                )
            }
            FloatingActionButton(
                onClick = { togglePlay() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(
                    end = 16.dp,
                    bottom = (config.screenHeightDp * 0.20f).dp - 28.dp
                ),
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            ) {
                Icon(
                    if (isReading) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = playDesc
                )
            }
            // B3.3/F2.5: dialog đổi vị trí khi đang READ.
            val pj = pendingJump
            if (pj != null) {
                JumpConfirmDialog(pj) { pendingJump = null }
            }
            // F2.2: hộp xác nhận neo (tránh chạm nhầm khi vuốt).
            val ac = anchorConfirm
            if (ac != null) {
                JumpConfirmDialog(
                    JumpConfirm(
                        context.getString(if (isReading) R.string.chrome_change_title else R.string.chrome_anchor_title),
                        if (isReading) context.getString(R.string.chrome_change_msg)
                        else context.getString(R.string.chrome_anchor_msg, anchorText(ac)),
                        { enterRead(ac) }
                    )
                ) { anchorConfirm = null }
            }
            if (tocOpen) {
                ReaderTocSheet(
                    titles = chapters.map { it.title },
                    current = currentChapter,
                    onPick = { tocOpen = false; requestJump(it) },
                    onDismiss = { tocOpen = false },
                    onSummarize = { idx -> tocOpen = false; fetchChapterSummary(idx) },
                    summarized = summarizedIdx,
                    onSummarizeBook = { tocOpen = false; startBookSummary() }
                )
            }
            // B5: dialog tóm tắt (4 trạng thái) + nút Nghe bằng chain TTS hiện có.
            if (summaryOpen) {
                SummaryDialog(
                    title = summaryTitle,
                    isBook = summaryIsBook,
                    state = summaryState,
                    vipActive = vipActive,
                    creditsLeft = adsState.rewardedSummary,
                    busyAd = summaryBusyAd,
                    onDismiss = {
                        summaryJob?.cancel()
                        if (summaryIsBook) selectedBook?.let {
                            com.bibot.app.data.summary.SummaryWorker.cancel(context, it.id)
                        }
                        summaryOpen = false
                    },
                    onRetry = { if (summaryIsBook) startBookSummary() else fetchChapterSummary(summaryIdx) },
                    onListen = { listenSummary(it) },
                    onWatchAd = {
                        watchAdForSummary { if (summaryIsBook) startBookSummary() else fetchChapterSummary(summaryIdx) }
                    },
                    onBookLevel = if (!summaryIsBook) ({ startBookSummary() }) else null
                )
            }
            // B6.2: popup ⚙ (toggle view + brightness + nền B6.19).
            // A3.1: add export handler
            val currentBook = selectedBook
            val onExport = if (currentBook?.isDownloaded == true) {
                { exportBookId = currentBook.id; exportVm.scheduleExport(currentBook.id) }
            } else null
            ReaderSettingsPopup(
                expanded = settingsOpen,
                onDismiss = { settingsOpen = false },
                viewMode = viewMode,
                pageModeEnabled = (isPdfBook || isEpubBook) && !viewLockedPage,
                onToggleView = {
                    if ((isPdfBook || isEpubBook) && !viewLockedPage) {
                        viewMode = if (viewMode == ReaderView.TEXT) ReaderView.PAGE else ReaderView.TEXT
                    }
                },
                brightness = brightness,
                onBrightness = { brightness = it },
                readerBackground = prefsState.readerBackground,
                onBackgroundChange = { v ->
                    scope.launch { try { prefs.setReaderBackground(v) } catch (_: Exception) {} }
                },
                onExport = onExport
            )
            // A3.1: Observe export state
            val currentExportState = exportVm.exportState.collectAsState().value
            if (exportBookId != null) {
                when (currentExportState) {
                    is ExportViewModel.ExportState.Exporting -> {
                        val msg = stringResource(R.string.export_progress, currentExportState.progress, currentExportState.total, currentExportState.skipped)
                        if (status != msg) status = msg
                    }
                    is ExportViewModel.ExportState.Success -> {
                        status = stringResource(R.string.export_done, currentExportState.outputPath, currentExportState.skipped)
                        exportBookId = null
                    }
                    is ExportViewModel.ExportState.Error -> {
                        status = stringResource(R.string.export_error, currentExportState.message)
                        exportBookId = null
                    }
                    else -> {}
                }
            }
            // B5: preload tập đã tóm tắt + rewarded ad khi mở sách.
            LaunchedEffect(selectedBook?.id) {
                val id = selectedBook?.id ?: return@LaunchedEffect
                refreshSummarized(id)
                try {
                    (context as? android.app.Activity)?.let {
                        com.bibot.app.data.ads.AdManager.loadRewarded(it)
                    }
                } catch (_: Exception) {}
            }
            // B6.3: popup hint lần đầu mỗi máy (nhớ DataStore, có nút xác nhận).
            LaunchedEffect(chapters.size, prefsState.readerHintSeen) {
                if (chapters.isNotEmpty() && !prefsState.readerHintSeen) hintOpen = true
            }
            if (hintOpen && !prefsState.readerHintSeen) {
                // Bấm ngoài dialog cũng tính là đã thấy (tránh nag-loop khi chạm trượt).
                fun dismissHint() {
                    hintOpen = false
                    scope.launch {
                        try { prefs.setReaderHintSeen() } catch (_: Exception) {}
                    }
                }
                AlertDialog(
                    onDismissRequest = { dismissHint() },
                    title = { Text(stringResource(R.string.chrome_hint_title), fontWeight = FontWeight.Bold) },
                    text = { Text(stringResource(R.string.chrome_hint_text)) },
                    confirmButton = {
                        TextButton(onClick = { dismissHint() }) { Text(stringResource(R.string.chrome_got_it)) }
                    }
                )
            }
        }
    }
}

@Composable
private fun ReaderCard(
    book: BookMeta,
    onLoad: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onLoad() }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (book.lang == "en") "📘" else "📗", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(book.source + " • " + book.lang.uppercase() + " • " + book.mimeType + " • " + (if (book.isDownloaded) stringResource(R.string.book_is_downloaded) else stringResource(R.string.book_not_downloaded)) + " " + book.displaySize, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
            }
            if (!book.isDownloaded) {
                IconButton(onClick = onDownload) { Icon(Icons.Default.Download, contentDescription = stringResource(R.string.book_download)) }
            } else {
                FilledTonalButton(onClick = onLoad) { Text(stringResource(R.string.book_read), fontSize = 12.sp) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.book_delete), modifier = Modifier.size(20.dp)) }
            }
        }
    }
}
