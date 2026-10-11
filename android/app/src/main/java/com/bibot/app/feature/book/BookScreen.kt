package com.bibot.app.feature.book

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.bibot.app.data.offline.OfflineTtsHelper
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.feature.story.findNextHeading
import com.bibot.app.feature.story.findPrevHeading
import com.bibot.app.ui.player.ControlIconButton
import com.bibot.app.ui.player.ControlInfoText
import com.bibot.app.ui.player.ControlPlayButton
import com.bibot.app.ui.player.FloatingControlScaffold
import com.bibot.app.ui.player.PlayerViewModel
import com.bibot.app.ui.player.contentBottomInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val BOOK_SAVE_EVERY = 8
private const val BOOK_PAUSE_BETWEEN_MS = 450L
// Rev/Next nhay doan ngan (± cau) thay vi doi chuong; chuyen chuong bang cham tab Hero bar
private const val BOOK_SHORT_JUMP_SENTENCES = 10

// 8.3 PCM flip-flop — producer synthesize trên IO, consumer đọc trên Main (tối đa 2 chunk đệm).
// Port nguyên luồng đọc của StoryScreen (module Kể chuyện) — thêm lang để sách EN đọc giọng en-US.
private sealed interface BookTtsItem {
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
    ) : BookTtsItem
    data class Direct(
        override val chapIdx: Int,
        override val sentIdx: Int,
        override val sentTotal: Int,
        override val title: String,
        val text: String,
        override val pauseAfterMs: Long = 0L,
        override val jumpSeq: Int = 0
    ) : BookTtsItem
}

// P1: chương chỉ giữ TITLE — content đọc per-chương từ `.prep` (seek) qua PrepSession
data class Chapter(val title: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    playerVm: PlayerViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BookRepository(context) }
    val prefs = remember { BibotPreferences(context) }
    val prefsState by prefs.prefsFlow.collectAsState(initial = com.bibot.app.data.store.BibotPrefs())
    val offlineTts = remember { OfflineTtsHelper(context) }
    val db = remember { BibotDatabase.get(context) }

    // Landscape detection — như StoryScreen
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
    // Phase Doc/Ngat 8-10' + bonus truoc ad (chi ban demo co ads; ban VIP doc lien mach)
    val phaseTracker = remember { com.bibot.app.core.ads.ReadingPhaseTracker() }
    var adPreloaded by remember { mutableStateOf(false) }
    // Highlight chi bat khi audio that su start (sua loi chunk dau to som trong luc cho synth)
    var audioStarted by remember { mutableStateOf(false) }
    // Quyen VIP (ma VIP): sach pha QC + mo Cloud (can key), hieu luc tu lan bam Doc ke tiep
    val adsPrefs = remember { com.bibot.app.data.ads.AdsPreferences(context) }
    val adsState by adsPrefs.adsStateFlow.collectAsState(initial = com.bibot.app.data.ads.AdsState())
    val vipActive = com.bibot.app.data.ads.isVipActive(adsState)
    // Dang doi QC tat de tu doc tiep (khong can bam Play lai)
    var adBreakActive by remember { mutableStateOf(false) }
    // Chong loop counter: lan ngat truoc cach day bao lau + o chuong nao
    var lastAdBreakMs by remember { mutableStateOf(0L) }
    var lastAdBreakChap by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        offlineTts.ensureInit {}
        repo.refreshAll()
        isLoading = false
        repo.lastSyncWarning.value?.let { status = it }
        repo.books.collect { books = it }
    }

    fun loadBook(book: BookMeta) {
        selectedBook = book
        currentChapter = 0
        currentSentence = 0
        isReading = false
        chapters = emptyList()
        sentences = emptyList()
        prepSession?.close()
        prepSession = null
        status = context.getString(R.string.reader_loading_book)
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
                        BookPrepStore.build(prepDir, cacheKey, file, heading, book.title)
                    }
                    session = if (built != null) BookPrepStore.PrepSession.of(built, null)
                    else {
                        // Fallback in-memory khi cache đĩa lỗi (giữ hành vi bản cũ, không mất sách)
                        val chaps = withContext(Dispatchers.IO) {
                            BlockTextReader.readChapters(file, book.title, heading)
                        }
                        BookPrepStore.PrepSession.inMemory(chaps.map { it.title }, chaps.map { it.content })
                    }
                }
                prepSession = session
                chapters = session.titles.map { Chapter(it) }
                totalSentencesAll = session.sentenceTotal ?: session.estimateTotal

                // Resume: load đúng chương đã đọc (session trả từ `.sent` nếu có, không split lại)
                val startChap = if (progress != null && chapters.isNotEmpty())
                    (progress.position / 100).coerceIn(0, chapters.lastIndex) else 0
                currentChapter = startChap
                sentences = session.sentences(startChap)
                currentSentence = if (progress != null)
                    (progress.position % 100).coerceIn(0, (sentences.size - 1).coerceAtLeast(0)) else 0

                if (progress != null && progress.position > 0) {
                    scope.launch {
                        val currentPos = progress.position
                        val totalNow = if (totalSentencesAll > 0) totalSentencesAll else progress.total
                        // Thông báo resume đúng NGÔN NGỮ sách — trước đây sách EN bị đọc bằng câu tiếng Việt không dấu
                        val announce = if (book.lang == "en") {
                            "You were listening to ${book.title} at sentence $currentPos of $totalNow. Continuing from sentence ${currentPos + 1}."
                        } else {
                            "Bạn đã nghe ${book.title} tới câu $currentPos trên $totalNow. Tiếp tục từ câu ${currentPos + 1}."
                        }
                        val useCloud = prefsState.ttsPriority != "offline"
                        // P0-1: key Cloud TTS riêng (Gemini key ≠ TTS key)
                        val geminiKey = prefsState.cloudTtsApiKey.takeIf { it.isNotBlank() }
                        val voice = if (book.lang == "en") CloudTtsBridge.DEFAULT_EN_VOICE else CloudTtsBridge.DEFAULT_VI_VOICE
                        if (useCloud && !geminiKey.isNullOrBlank()) {
                            val bytes = CloudTtsBridge.synthesize(announce, geminiKey, voice = voice, speakingRate = prefsState.voiceSpeed)
                            if (bytes != null) {
                                playerVm.playBytes(bytes)
                                delay((announce.length * 60L).coerceAtLeast(700L))
                            }
                        } else {
                            offlineTts.speakOffline(announce, prefsState.voiceSpeed, lang = book.lang)
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
                            status = context.getString(R.string.reader_loaded, chapters.size, real)
                        }
                    }
                }
                status = context.getString(R.string.reader_loaded, chapters.size, totalSentencesAll)
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

    // Nút Rev/Next khi đang đọc — nhảy thẳng, bỏ audio đã synth (như StoryScreen.requestJump)
    fun requestJump(idx: Int, sentence: Int = 0) {
        if (idx !in chapters.indices) return
        if (!isReading) { switchChapter(idx, sentence); return }
        // Huy lenh tu-resume neu user nhay thu cong giua luc QC (chong doc lai ngoai y)
        adBreakActive = false
        com.bibot.app.data.ads.AdManager.onAdClicked = null
        jumpChapterReq.intValue = idx
        jumpSentenceReq.intValue = sentence.coerceAtLeast(0)
        jumpSeq.intValue++
        playerVm.stopPlayback()
        audioStarted = false
        status = if (idx == currentChapter) context.getString(R.string.reader_jump_sentence, sentence.coerceAtLeast(0) + 1)
        else context.getString(R.string.reader_jump_chapter, idx + 1, chapters.size, chapters[idx].title)
    }

    // Rev/Next: nhay doan ngan ± cau trong/khap chuong. Chuyen chuong bang cham tab Hero bar.
    fun jumpSentences(delta: Int) {
        if (chapters.isEmpty() || sentences.isEmpty()) return
        val target = currentSentence + delta
        when {
            target < 0 -> if (currentChapter > 0) requestJump(currentChapter - 1, Int.MAX_VALUE)
            else { currentSentence = 0; status = context.getString(R.string.reader_at_start_book) }
            target >= sentences.size -> if (currentChapter + 1 < chapters.size) requestJump(currentChapter + 1, 0)
            else { currentSentence = sentences.lastIndex; status = context.getString(R.string.reader_at_end) }
            else -> requestJump(currentChapter, target)
        }
    }

    // "# search" — tìm heading kế/trước trong chương hiện tại (dùng chung findNext/PrevHeading với Kể chuyện)
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
                    else status = context.getString(R.string.reader_at_start_book)
                }
            }
        }
    }

    LaunchedEffect(isReading) {
        if (!isReading || selectedBook == null || chapters.isEmpty()) {
            adPreloaded = false
            return@LaunchedEffect
        }
        // Preload interstitial early when reading starts (fix race condition)
        if (com.bibot.app.BuildConfig.ENABLE_ADS && !vipActive && !adPreloaded) {
            try {
                (context as? android.app.Activity)?.let { act ->
                    com.bibot.app.data.ads.AdManager.loadInterstitial(act)
                }
                adPreloaded = true
            } catch (_: Exception) {}
        }
        val book = selectedBook ?: return@LaunchedEffect
        // P1: câu từng chương lấy từ session (.sent cache / seek .prep) — không split content trong RAM
        val session = prepSession ?: return@LaunchedEffect
        val speed = if (book.lang == "vi") prefsState.storySpeed else prefsState.voiceSpeed
        val totalSentences = totalSentencesAll
        val useCloud = prefsState.ttsPriority != "offline"
        // P0-1: key Cloud TTS riêng (Gemini key ≠ TTS key)
        val geminiKey = prefsState.cloudTtsApiKey.takeIf { it.isNotBlank() }
        val voice = if (book.lang == "en") CloudTtsBridge.DEFAULT_EN_VOICE else CloudTtsBridge.DEFAULT_VI_VOICE
        val cloudOn = useCloud && !geminiKey.isNullOrBlank()
        // Rewarded: mo Cloud theo chuong (chi demo co ads; VIP khong dung nhanh nay)
        val rewardedCloudOk = com.bibot.app.BuildConfig.ENABLE_ADS && !geminiKey.isNullOrBlank() && !vipActive
        var rewardedLeft = try { adsPrefs.getRewardedCloudChapters() } catch (_: Exception) { 0 }
        var rewardedForChap = rewardedLeft > 0
        val piperOn = prefsState.storyPreferPiper &&
            com.bibot.app.core.tts.PiperTtsHelper.isVoiceComplete(context, book.lang)
        val ttsChannel = Channel<BookTtsItem>(capacity = 2)
        var finishedAll = false

        val producer = launch {
            var chapIdx = currentChapter
            var sentIdx = currentSentence
            var localSentences = sentences
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
                        rewardedForChap = rewardedLeft > 0
                        delay(BOOK_PAUSE_BETWEEN_MS)
                        continue
                    }
                    val clean = com.bibot.app.core.text.TextCleaner.cleanForTts(localSentences[sentIdx])
                    if (clean.isBlank()) { sentIdx++; continue }
                    if (com.bibot.app.core.text.TextCleaner.looksLikeThinking(clean)) { sentIdx++; continue }
                    val (plain, needPause) = com.bibot.app.core.text.TextCleaner.splitPause(clean)
                    if (plain.isBlank()) {
                        if (needPause) delay(2000)
                        sentIdx++; continue
                    }
                    val pauseMs = if (needPause) 2000L else 0L
                    var pcm: ByteArray? = null
                    var mime = "audio/mp3"
                    if (piperOn) {
                        pcm = try { com.bibot.app.core.tts.PiperTtsHelper.synthesize(context, plain, book.lang) } catch (_: Exception) { null }
                        if (pcm != null) mime = "audio/wav"
                    }
                    if ((pcm == null || pcm.isEmpty()) && (cloudOn || (rewardedCloudOk && rewardedForChap) || (vipActive && !geminiKey.isNullOrBlank()))) {
                        pcm = try { CloudTtsBridge.synthesize(plain, geminiKey, voice = voice, speakingRate = speed) } catch (_: Exception) { null }
                        mime = "audio/mp3"
                    }
                    if (pcm != null && pcm.isNotEmpty()) {
                        ttsChannel.send(BookTtsItem.Pcm(chapIdx, sentIdx, localSentences.size, chapters[chapIdx].title, pcm, plain.length, pauseMs, mime, seqAtStart))
                    } else {
                        ttsChannel.send(BookTtsItem.Direct(chapIdx, sentIdx, localSentences.size, chapters[chapIdx].title, plain, pauseMs, seqAtStart))
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
                    is BookTtsItem.Pcm -> {
                        val mid = "book_${item.chapIdx}_${item.sentIdx}_${item.jumpSeq}"
                        playerVm.enqueueBytes(item.bytes, item.mime, mid)
                        awaitAudioStart(playerVm, mid, (item.textLen * 60L).coerceAtLeast(700L)) { item.jumpSeq != jumpSeq.intValue }
                        if (item.jumpSeq != jumpSeq.intValue) continue
                        currentSentence = item.sentIdx
                        audioStarted = true
                        status = context.getString(R.string.reader_status_reading, item.title, item.sentIdx + 1, item.sentTotal)
                        awaitAudioDone(playerVm, mid, (item.textLen * 60L).coerceAtLeast(700L)) { item.jumpSeq != jumpSeq.intValue }
                    }
                    is BookTtsItem.Direct -> {
                        currentSentence = item.sentIdx
                        audioStarted = true
                        status = context.getString(R.string.reader_status_reading, item.title, item.sentIdx + 1, item.sentTotal)
                        // lang = sách EN → giọng en-US (không còn đọc tiếng Anh bằng giọng Việt)
                        try { offlineTts.speakOffline(item.text, speed, lang = book.lang) } catch (_: Exception) {}
                    }
                }
                if (item.pauseAfterMs > 0) delayUntilDone(item.pauseAfterMs) { item.jumpSeq != jumpSeq.intValue }
                spokenCount++
                if (spokenCount % BOOK_SAVE_EVERY == 0) {
                    try {
                        db.book().upsert(
                            com.bibot.app.data.db.BookProgress(
                                bookId = book.id,
                                path = book.localFile?.absolutePath ?: book.title,
                                position = item.chapIdx * 100 + (item.sentIdx + 1),
                                total = totalSentences
                            )
                        )
                    } catch (_: Exception) {}
                }
                // Tạm nghỉ mỗi câu như StoryScreen — dài hơn nếu câu kết thúc bằng dấu phẩy
                val spokenText = if (item is BookTtsItem.Direct) item.text else ""
                val isComma = spokenText.trimEnd().endsWith(",") || spokenText.trimEnd().endsWith("，")
                delay(if (isComma) 250 else 180)
                // Phase Doc/Ngat 8-10': VIP bo qua (khong bonus, khong dung, khong QC)
                if (com.bibot.app.BuildConfig.ENABLE_ADS && !vipActive && item.jumpSeq == jumpSeq.intValue) {
                    if (item is BookTtsItem.Direct) phaseTracker.onBlock(item.text)
                    else if (item is BookTtsItem.Pcm) phaseTracker.onChars(item.textLen)
                    val lv = phaseTracker.level()
                    val chapEnd = item.sentIdx + 1 >= item.sentTotal
                    // Chong loop counter: cach lan ngat truoc >= 5 phut VA khac chuong da ngat.
                    val nowMs = System.currentTimeMillis()
                    val gapOk = nowMs - lastAdBreakMs >= 5 * 60 * 1000L
                    val chapOk = item.chapIdx != lastAdBreakChap
                    if (phaseTracker.shouldBreakAtBoundary(lv, chapEnd) && gapOk && chapOk) {
                        val bonus = com.bibot.app.core.ads.AdCopy.bonusChapter(book.lang, item.title)
                        status = bonus
                        try { offlineTts.speakOffline(bonus, speed, lang = book.lang) } catch (_: Exception) {}
                        if (item.jumpSeq == jumpSeq.intValue) {
                            try { offlineTts.stop() } catch (_: Exception) {}
                            playerVm.stopPlayback()
                            // Hien QC neu co san; khong co -> doc lien mach khong dung
                            var adShown = false
                            try {
                                (context as? android.app.Activity)?.let { act ->
                                    adShown = com.bibot.app.data.ads.AdManager.showInterstitial(act)
                                }
                            } catch (_: Exception) {}
                            phaseTracker.reset()
                            adPreloaded = false
                            lastAdBreakMs = nowMs
                            lastAdBreakChap = item.chapIdx
                            try {
                                Log.d("PhaseAd", "break book chap=${item.chapIdx} sent=${item.sentIdx} lv=$lv ${phaseTracker.debugStats()}")
                            } catch (_: Exception) {}
                            currentSentence = item.sentIdx + 1
                            if (adShown) {
                                // Tu dong doc tiep khi QC tat (onAdDismissed) — ke ca khi tat man hinh
                                adBreakActive = true
                                com.bibot.app.data.ads.AdManager.onAdClicked = {
                                    com.bibot.app.data.ads.AdManager.onAdClicked = null
                                    if (adBreakActive) { adBreakActive = false; isReading = true }
                                }
                                com.bibot.app.data.ads.AdManager.onAdFailed = {
                                    com.bibot.app.data.ads.AdManager.onAdFailed = null
                                    if (adBreakActive) { adBreakActive = false; isReading = true }
                                }
                                status = context.getString(R.string.reader_ad_resume, bonus)
                                isReading = false
                                break
                            }
                            // Khong co QC san: doc tiep cau ke ma khong ngat
                        }
                    } else if (lv == 1 && !adPreloaded) {
                        // Armed: preload interstitial de san o bien chuong (fallback nếu preload đầu thất bại)
                        try {
                            (context as? android.app.Activity)?.let { act ->
                                com.bibot.app.data.ads.AdManager.loadInterstitial(act)
                            }
                            adPreloaded = true
                        } catch (_: Exception) {}
                    }
                }
            }
        } finally {
            try { producer.cancel() } catch (_: Exception) {}
        }
        if (isReading && finishedAll) {
            status = context.getString(R.string.reader_done_reading, book.title)
            currentSentence = sentences.size // qua cuối → tắt highlight câu cuối
            isReading = false
        }
    }

    if (selectedBook == null) {
        // Module "Sách tiếng Anh": hiện EN + local/link. Sách folder VI (source=pdrive, lang=vi)
        // thuộc module Kể chuyện → ẩn, tránh danh sách 2 module giống hệt nhau.
        val enBooks = books.filter { it.lang == "en" }
        val otherBooks = books.filterNot { it.lang == "en" || (it.lang == "vi" && it.source == "pdrive") }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                BookCard(book = book, onLoad = { loadBook(book) }, onDownload = { scope.launch { repo.download(book); repo.refreshAll(); status = context.getString(R.string.book_download_done, book.title) } }, onDelete = { scope.launch { repo.delete(book); repo.refreshAll() } })
            }
            if (otherBooks.isNotEmpty()) {
                item { Text(stringResource(R.string.book_section_local, otherBooks.size), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            }
            items(otherBooks, key = { it.id }) { book ->
                BookCard(book = book, onLoad = { loadBook(book) }, onDownload = { scope.launch { repo.download(book); repo.refreshAll(); status = context.getString(R.string.book_download_done, book.title) } }, onDelete = { scope.launch { repo.delete(book); repo.refreshAll() } })
            }
            item {
                if (isLoading && books.isEmpty()) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
    } else {
        val book = selectedBook!!
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
        // 12.x: thanh điều khiển nổi + nền trong suốt (dùng chung) — không trừ chiều cao,
        // dọc & ngang đều lấy lại toàn bộ không gian đọc.
        FloatingControlScaffold(
            alignment = if (isLandscape) Alignment.BottomEnd else Alignment.BottomCenter,
            info = {
                Column {
                    ControlInfoText(stringResource(R.string.reader_pos, currentChapter + 1, chapters.size, currentSentence + 1, sentences.size))
                    ControlInfoText((if (book.lang == "en") "EN" else "VI") + " • " + (if (book.lang == "en") prefsState.voiceSpeed else prefsState.storySpeed).toString() + "x", secondary = true)
                }
            },
            controls = {
                ControlIconButton(
                    onClick = { jumpSentences(-BOOK_SHORT_JUMP_SENTENCES) },
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.reader_jump_back, BOOK_SHORT_JUMP_SENTENCES)
                )
                ControlPlayButton(
                    isPlaying = isReading,
                    onClick = { if (isReading) { isReading = false; adBreakActive = false; offlineTts.stop(); playerVm.stopPlayback(); com.bibot.app.ui.player.KeepAwakeService.stop(context) } else { audioStarted = false; phaseTracker.reset(); adPreloaded = false; isReading = true; com.bibot.app.ui.player.KeepAwakeService.start(context) } },
                    contentDescription = if (isReading) stringResource(R.string.reader_pause) else stringResource(R.string.reader_play_read)
                )
                ControlIconButton(
                    onClick = { jumpSentences(BOOK_SHORT_JUMP_SENTENCES) },
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = stringResource(R.string.reader_jump_fwd, BOOK_SHORT_JUMP_SENTENCES)
                )
            }
        ) {
            // Header reader gọn 40dp (gốc TopAppBar 64dp) — tăng tối đa không gian đọc
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { isReading = false; adBreakActive = false; audioStarted = false; offlineTts.stop(); playerVm.stopPlayback(); com.bibot.app.ui.player.KeepAwakeService.stop(context); selectedBook = null },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.reader_back), modifier = Modifier.size(20.dp))
                    }
                    Text(
                        "📚 ${book.title}",
                        maxLines = 1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            if (chapters.size > 1) {
                ScrollableTabRow(selectedTabIndex = currentChapter, modifier = Modifier.fillMaxWidth()) {
                    chapters.forEachIndexed { idx, c ->
                        Tab(selected = idx == currentChapter, onClick = {
                            isReading = false; adBreakActive = false; audioStarted = false; offlineTts.stop(); playerVm.stopPlayback(); com.bibot.app.ui.player.KeepAwakeService.stop(context)
                            switchChapter(idx)
                        }, text = { Text(c.title.take(16), fontSize = 11.sp) })
                    }
                }
            }
            val listState = rememberLazyListState()
            val density = LocalDensity.current
            LaunchedEffect(currentSentence, isReading) {
                if (isReading && sentences.isNotEmpty()) {
                    // Gop nhip 350ms + bu header + can giua 1/3 man hinh (nhu StoryScreen):
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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 4.dp, start = 16.dp, end = 16.dp, bottom = contentBottomInset()),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                item(key = "head-$currentChapter") {
                    chap?.let {
                        Text(it.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                itemsIndexed(sentences, key = { idx, _ -> "$currentChapter-$idx" }) { idx, s ->
                    val isCurrent = idx == currentSentence && isReading && audioStarted
                    val (displayText, hasPause) = com.bibot.app.core.text.TextCleaner.splitPause(s)
                    Column {
                        Text(
                            if (hasPause) "$displayText ⏸2s" else displayText,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 3.dp)
                        )
                        // Native tinh giua ky (TTS Bypass): label EN cho sach EN
                        val adIdx = com.bibot.app.ui.components.ads.NativeInlineAd.INDEX_DEFAULT
                        if (idx == adIdx && sentences.size > adIdx) {
                            Spacer(Modifier.height(4.dp))
                            com.bibot.app.ui.components.ads.NativeInlineAd(lang = book.lang, isFamily = false)
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

@Composable
private fun BookCard(
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
