package com.bibot.app.feature.story

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibot.app.R
import com.bibot.app.ui.menu.label
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

private const val STORY_SAVE_EVERY = 8
private const val STORY_PAUSE_BETWEEN_MS = 450L
// Rev/Next nhay doan ngan (± cau) thay vi doi chuong; chuyen chuong bang cham tab Hero bar
private const val STORY_SHORT_JUMP_SENTENCES = 10

// Heading pattern dùng chung cho Kể chuyện (VI) + Sách tiếng Anh (EN):
// `##` markdown, từ chương VI ("Chương/Phần/Hồi…"), và từ chương EN ("Chapter/Part/Section/Book").
private val headingSearchRegex = Regex(
    """(?m)^(?:##\s+|Chương\s|Chuong\s|Phần\s|Phan\s|Hồi\s|Hoi\s|Quyển\s|Quyen\s|Tập\s|Tap\s|Mục\s|Muc\s|Truyện\s|Truyen\s|Chuyện\s|Chuyen\s|Sự tích\s|Su tich\s|Chapter\s|Part\s|Section\s|Book\s)""",
    RegexOption.IGNORE_CASE
)

// Search for next/previous heading (#) within content
fun findNextHeading(text: String, currentPos: Int): Int? {
    val matches = headingSearchRegex.findAll(text).toList()
    for (m in matches) {
        if (m.range.first > currentPos) return m.range.first
    }
    return null
}

fun findPrevHeading(text: String, currentPos: Int): Int? {
    val matches = headingSearchRegex.findAll(text).toList()
    for (i in matches.lastIndex downTo 0) {
        if (matches[i].range.first < currentPos) return matches[i].range.first
    }
    return null
}

// 8.3 PCM flip-flop — producer synthesize trên IO, consumer kể trên Main (tối đa 2 chunk đệm)
private sealed interface StoryTtsItem {
    val chapIdx: Int
    val sentIdx: Int
    val sentTotal: Int
    val title: String
    val pauseAfterMs: Long
    // jumpSeq: số hiệu "lượt nhảy chương" lúc synth — consumer bỏ item cũ khi nó lệch với jumpSeq hiện tại
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
    ) : StoryTtsItem
    data class Direct(
        override val chapIdx: Int,
        override val sentIdx: Int,
        override val sentTotal: Int,
        override val title: String,
        val text: String,
        override val pauseAfterMs: Long = 0L,
        override val jumpSeq: Int = 0
    ) : StoryTtsItem
}

// P1: chương chỉ giữ TITLE — content đọc per-chương từ `.prep` (seek) qua PrepSession
data class StoryChapter(val title: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryScreen(
    playerVm: PlayerViewModel = viewModel(),
    // Bộ sưu tập kho: "story" (Kể chuyện — folder VI) | "cotich" (Cổ tích — folder Cổ tích)
    collection: String = "story",
) {
    val isCotich = collection == "cotich"
    val listEmoji = if (isCotich) "🧚" else "📖"
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BookRepository(context) }
    val prefs = remember { BibotPreferences(context) }
    val prefsState by prefs.prefsFlow.collectAsState(initial = com.bibot.app.data.store.BibotPrefs())
    val offlineTts = remember { OfflineTtsHelper(context) }
    val db = remember { BibotDatabase.get(context) }
    
    // Landscape detection
    val config = LocalConfiguration.current
    val isLandscape = config.screenWidthDp > config.screenHeightDp

    var stories by remember { mutableStateOf<List<BookMeta>>(emptyList()) }
    var selected by remember { mutableStateOf<BookMeta?>(null) }
    var chapters by remember { mutableStateOf<List<StoryChapter>>(emptyList()) }
    var currentChap by remember { mutableIntStateOf(0) }
    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSent by remember { mutableIntStateOf(0) }
    var totalSentencesAll by remember { mutableIntStateOf(0) }
    var isReading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // P1: phiên đọc sách — title list không content, content/câu đọc per-chương (cache đĩa)
    var prepSession by remember { mutableStateOf<BookPrepStore.PrepSession?>(null) }
    // Đóng handle file khi màn hình thực sự rời composition
    DisposableEffect(Unit) { onDispose { prepSession?.close() } }
    // Nhảy chương khi đang kể (nút Rev/Next — "search #"): producer poll request này ở đầu vòng lặp
    val jumpChapterReq = remember { mutableIntStateOf(-1) }
    val jumpSentenceReq = remember { mutableIntStateOf(0) }
    // Tăng khi bấm Rev/Next → consumer bỏ audio đã synth ở vị trí cũ + hủy delay "chờ kể xong"
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
        if (isCotich) {
            // Kho Cổ tích: CHỈ sách từ folder Cổ tích (không lẫn kho VI / local)
            stories = repo.books.value.filter { it.collection == "cotich" }
            repo.books.collect { all -> stories = all.filter { it.collection == "cotich" } }
        } else {
            // Truyện VI: md/txt + PDF (vd Việt Nam Sử Lược — trích text bám font
            // ToUnicode/TCVN3 trong BlockTextReader). LOẠI sách Cổ tích (module riêng).
            val storyOk: (BookMeta) -> Boolean = {
                it.lang == "vi" && it.collection != "cotich" &&
                    (it.mimeType.contains("markdown") || it.mimeType.contains("plain") ||
                        it.mimeType.contains("pdf") || it.mimeType.contains("epub") ||
                        it.title.endsWith(".md") || it.title.endsWith(".txt") ||
                        it.title.endsWith(".pdf", ignoreCase = true) ||
                        it.title.endsWith(".epub", ignoreCase = true))
            }
            stories = repo.books.value.filter(storyOk)
            // Also scan local vi files directly
            if (stories.isEmpty()) {
                repo.books.collect { all -> stories = all.filter(storyOk) }
            }
        }
    }

    fun loadStory(meta: BookMeta) {
        selected = meta
        currentChap = 0
        currentSent = 0
        isReading = false
        chapters = emptyList()
        sentences = emptyList()
        prepSession?.close()
        prepSession = null
        status = context.getString(R.string.reader_loading_story)
        scope.launch {
            try {
                val file: File = if (meta.isDownloaded && meta.localFile != null) meta.localFile
                else {
                    status = context.getString(R.string.reader_downloading)
                    with(Dispatchers.IO) { repo.download(meta) }
                }
                // P1: đọc progress TRƯỚC — biết chương resume để mở đúng câu
                status = context.getString(R.string.reader_checking_progress)
                val progress = db.book().get(meta.id)

                // P1: hit `.prep` → bỏ hẳn resolveHeading + readChapters/clean (2 pass toàn file).
                // Miss → build streaming (sink, không giữ sách RAM) → ghi đĩa.
                val prepDir = FilePaths.prepDir(context)
                val cacheKey = meta.id.ifBlank { meta.title }
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
                        BookPrepStore.build(prepDir, cacheKey, file, heading, "Truyện")
                    }
                    session = if (built != null) BookPrepStore.PrepSession.of(built, null)
                    else {
                        // Fallback in-memory khi cache đĩa lỗi (giữ hành vi bản cũ, không mất sách)
                        val chaps = withContext(Dispatchers.IO) {
                            BlockTextReader.readChapters(file, "Truyện", heading)
                        }
                        BookPrepStore.PrepSession.inMemory(chaps.map { it.title }, chaps.map { it.content })
                    }
                }
                prepSession = session
                chapters = session.titles.map { StoryChapter(it) }
                totalSentencesAll = session.sentenceTotal ?: session.estimateTotal

                // Resume: load đúng chương đã nghe (session trả từ `.sent` nếu có, không split lại)
                val startChap = if (progress != null && chapters.isNotEmpty())
                    (progress.position / 100).coerceIn(0, chapters.lastIndex) else 0
                currentChap = startChap
                sentences = session.sentences(startChap)
                currentSent = if (progress != null)
                    (progress.position % 100).coerceIn(0, (sentences.size - 1).coerceAtLeast(0)) else 0

                // Resume announcement if progress exists
                if (progress != null && progress.position > 0) {
                    scope.launch {
                        val totalSentences = if (totalSentencesAll > 0) totalSentencesAll else progress.total
                        val currentPos = progress.position
                        val announce = "Bạn đã nghe ${meta.title} tới câu $currentPos trên $totalSentences. Tiếp tục kể từ câu ${currentPos + 1}."
                        // Inline TTS logic for announce
                        // P0-1: key Cloud TTS riêng (Gemini key ≠ TTS key)
                        val key = prefsState.cloudTtsApiKey.takeIf { it.isNotBlank() }
                        val useCloud = prefsState.ttsPriority != "offline" && !key.isNullOrBlank()
                        if (useCloud) {
                            val bytes = CloudTtsBridge.synthesize(announce, key, voice = CloudTtsBridge.DEFAULT_VI_VOICE, speakingRate = prefsState.storySpeed)
                            if (bytes != null) {
                                playerVm.playBytes(bytes)
                                delay((announce.length * 62L).coerceAtLeast(700L))
                            }
                        } else {
                            offlineTts.speakOffline(announce, prefsState.storySpeed)
                        }
                    }
                }

                // P1: build sentence cache NỀN (song song 3-4 luồng, ghi streaming) — chưa có mới build
                if (session.sentenceTotal == null) {
                    scope.launch {
                        val real = BookPrepStore.buildSentences(prepDir, cacheKey, file, heading)
                        if (real != null && real > 0 && selected?.id == meta.id) {
                            session.sentenceTotal = real
                            totalSentencesAll = real
                            status = context.getString(R.string.reader_loaded, chapters.size, real) + " • 0.88x"
                        }
                    }
                }
                status = context.getString(R.string.reader_loaded, chapters.size, totalSentencesAll) + " • 0.88x"
                // Sách scan/ảnh không có text thật để trích — báo rõ thay vì hiển thị
                // rác/trang trắng: PDF font Type3 bitmap / EPUB toàn ảnh.
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
                    if (scannedMsg != null && selected?.id == meta.id) status = scannedMsg
                }
            } catch (e: Exception) {
                status = context.getString(R.string.reader_error, e.message ?: "")
            }
        }
    }

    // Đổi chương — lấy câu từ `.sent`/LRU (miss mới split, trên IO)
    // sentence < 0 → nhảy tới câu cuối chương đích.
    fun switchChapter(idx: Int, sentence: Int = 0) {
        if (idx !in chapters.indices) return
        val session = prepSession ?: return
        val wantLast = sentence < 0
        currentChap = idx
        currentSent = if (wantLast) 0 else sentence.coerceAtLeast(0)
        scope.launch {
            val list = session.sentences(idx)
            sentences = list
            currentSent = if (wantLast) (list.size - 1).coerceAtLeast(0)
            else sentence.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        }
    }

    // Nút Rev/Next — "search #": nhảy thẳng sang chương đích KHÔNG cần kể hết câu hiện tại.
    // Đang kể → báo producer nhảy + dọn audio đang phát; đang dừng → chuyển UI như tab chương.
    fun requestJump(idx: Int, sentence: Int = 0) {
        if (idx !in chapters.indices) return
        if (!isReading) { switchChapter(idx, sentence); return }
        // Huy lenh tu-resume neu user nhay thu cong giua luc QC (chong doc lai ngoai y)
        adBreakActive = false
        com.bibot.app.data.ads.AdManager.onAdClicked = null
        jumpChapterReq.intValue = idx
        jumpSentenceReq.intValue = sentence.coerceAtLeast(0)
        jumpSeq.intValue++            // consumer: bỏ item cũ + cắt delay chờ audio
        playerVm.stopPlayback()       // dừng audio cũ ngay (không gọi offlineTts.stop() — tránh kẹt speakOffline)
        audioStarted = false          // highlight moi chi bat khi audio cau moi that su start
        status = if (idx == currentChap) context.getString(R.string.reader_jump_sentence, sentence.coerceAtLeast(0) + 1)
        else context.getString(R.string.reader_jump_chapter, idx + 1, chapters.size, chapters[idx].title)
    }

    // Rev/Next: nhay doan ngan ± cau trong/khap chuong. Chuyen chuong bang cham tab Hero bar.
    fun jumpSentences(delta: Int) {
        if (chapters.isEmpty() || sentences.isEmpty()) return
        val target = currentSent + delta
        when {
            target < 0 -> if (currentChap > 0) requestJump(currentChap - 1, Int.MAX_VALUE)
            else { currentSent = 0; status = context.getString(R.string.reader_at_start_story) }
            target >= sentences.size -> if (currentChap + 1 < chapters.size) requestJump(currentChap + 1, 0)
            else { currentSent = sentences.lastIndex; status = context.getString(R.string.reader_at_end) }
            else -> requestJump(currentChap, target)
        }
    }

    // "# search" - tìm heading (#) kế tiếp/trước trong nội dung chương hiện tại
    // (content chương hiện tại đọc từ `.prep` trên IO — không còn giữ trong RAM)
    fun searchHeading(direction: Int) { // 1 = next, -1 = prev
        val session = prepSession ?: return
        if (currentChap !in chapters.indices) return
        scope.launch {
            val content = session.content(currentChap)
            // Estimate current position in chapter text based on sentence index
            val sentencesInChap = sentences
            val approxPos = if (sentencesInChap.isNotEmpty() && currentSent < sentencesInChap.size) {
                val currentSentence = sentencesInChap[currentSent]
                content.indexOf(currentSentence)
            } else 0

            val newPos = if (direction > 0) findNextHeading(content, approxPos) else findPrevHeading(content, approxPos)

            newPos?.let { pos ->
                // Convert position to sentence index
                val textBefore = content.substring(0, pos)
                val sentenceCount = textBefore.split(Regex("[.!?…]\\s*")).size - 1
                val targetSentence = sentenceCount.coerceIn(0, sentencesInChap.size - 1)
                if (isReading) {
                    requestJump(currentChap, targetSentence)
                } else {
                    currentSent = targetSentence
                }
            } ?: run {
                // Fallback to chapter navigation if no heading found
                if (direction > 0) {
                    if (currentChap + 1 < chapters.size) requestJump(currentChap + 1, 0)
                    else status = context.getString(R.string.reader_at_end)
                } else {
                    if (currentChap > 0) requestJump(currentChap - 1, 0)
                    else status = context.getString(R.string.reader_at_start_story)
                }
            }
        }
    }

    // 8.3 PCM flip-flop — producer synthesize trên IO, consumer kể trên Main (overlap thật)
    LaunchedEffect(isReading) {
        if (!isReading || selected == null || chapters.isEmpty()) {
            // Reset ad preload flag when reading stops
            adPreloaded = false
            return@LaunchedEffect
        }
        // Preload interstitial early when reading starts (fix race condition)
        if (com.bibot.app.BuildConfig.ENABLE_ADS && !vipActive && !isCotich && !adPreloaded) {
            try {
                (context as? android.app.Activity)?.let { act ->
                    com.bibot.app.data.ads.AdManager.loadInterstitial(act)
                }
                adPreloaded = true
            } catch (_: Exception) {}
        }
        // P1: câu từng chương lấy từ session (.sent cache / seek .prep) — không split content trong RAM
        val session = prepSession ?: return@LaunchedEffect
        val speed = prefsState.storySpeed // 0.88 default
        // Tổng số câu đã tính lúc nạp (trên IO) — KHÔNG tách lại toàn bộ truyện trên Main
        val totalSentences = totalSentencesAll
        // P0-1: key Cloud TTS riêng (Gemini key ≠ TTS key)
        val key = prefsState.cloudTtsApiKey.takeIf { it.isNotBlank() }
        val cloudKeyOk = !key.isNullOrBlank()
        val cloudOn = prefsState.ttsPriority != "offline" && cloudKeyOk
        // Rewarded: mo Cloud theo chuong (chi demo co ads; VIP khong dung nhanh nay)
        val rewardedCloudOk = com.bibot.app.BuildConfig.ENABLE_ADS && cloudKeyOk && !vipActive
        var rewardedLeft = try { adsPrefs.getRewardedCloudChapters() } catch (_: Exception) { 0 }
        var rewardedForChap = rewardedLeft > 0
        val piperOn = prefsState.storyPreferPiper &&
            com.bibot.app.core.tts.PiperTtsHelper.isVoiceComplete(context, "vi")
        val ttsChannel = Channel<StoryTtsItem>(capacity = 2)
        var finishedAll = false

        val producer = launch {
            var chapIdx = currentChap
            var sentIdx = currentSent
            var localSentences = sentences
            try {
                while (isActive && isReading && chapIdx < chapters.size) {
                    // Rev/Next → "search #": nhảy thẳng tới chương đích, không chờ câu hiện tại xong
                    val jc = jumpChapterReq.intValue
                    if (jc >= 0) {
                        jumpChapterReq.intValue = -1
                        chapIdx = jc
                        localSentences = session.sentences(chapIdx)
                        currentChap = chapIdx
                        sentences = localSentences
                        sentIdx = jumpSentenceReq.intValue.coerceIn(0, (localSentences.size - 1).coerceAtLeast(0))
                        currentSent = sentIdx
                        continue
                    }
                    // Số hiệu lượt nhảy lúc BẮT ĐẦU câu này — bấm Rev/Next giữa chừng → item mang
                    // seq cũ → consumer bỏ đi, không kể nhầm chỗ cũ.
                    val seqAtStart = jumpSeq.intValue
                    if (sentIdx >= localSentences.size) {
                        // Het chuong: tru 1 luot rewarded neu chuong vua doc dung giong Cloud tang
                        if (rewardedForChap && rewardedLeft > 0) {
                            rewardedLeft--
                            try { adsPrefs.setRewardedCloudChapters(rewardedLeft) } catch (_: Exception) {}
                        }
                        chapIdx++
                        if (chapIdx >= chapters.size) break
                        // Tách câu chương mới trên IO — không chặn Main
                        localSentences = session.sentences(chapIdx)
                        currentChap = chapIdx
                        sentences = localSentences
                        sentIdx = 0
                        currentSent = 0
                        rewardedForChap = rewardedLeft > 0
                        delay(STORY_PAUSE_BETWEEN_MS)
                        continue
                    }
                    // 8.4 + 2.5: clean trước synthesize
                    val clean = com.bibot.app.core.text.TextCleaner.cleanForTts(localSentences[sentIdx])
                    if (clean.isBlank()) { sentIdx++; continue }
                    if (com.bibot.app.core.text.TextCleaner.looksLikeThinking(clean)) { sentIdx++; continue }
                    val (plain, needPause) = com.bibot.app.core.text.TextCleaner.splitPause(clean)
                    if (plain.isBlank()) {
                        if (needPause) delay(2000)
                        sentIdx++; continue
                    }
                    val pauseMs = if (needPause) 2000L else 0L
                    // Dùng `plain` (đã bỏ PAUSE2SEC) cho mọi engine — marker chỉ còn làm cờ pause
                    var pcm: ByteArray? = null
                    var mime = "audio/mp3"
                    if (piperOn) {
                        pcm = try { com.bibot.app.core.tts.PiperTtsHelper.synthesize(context, plain, "vi") } catch (_: Exception) { null }
                        if (pcm != null) mime = "audio/wav"
                    }
                    if ((pcm == null || pcm.isEmpty()) && (cloudOn || (rewardedCloudOk && rewardedForChap) || (vipActive && cloudKeyOk))) {
                        pcm = try { CloudTtsBridge.synthesize(plain, key, voice = CloudTtsBridge.DEFAULT_VI_VOICE, speakingRate = speed) } catch (_: Exception) { null }
                        mime = "audio/mp3"
                    }
                    if (pcm != null && pcm.isNotEmpty()) {
                        ttsChannel.send(StoryTtsItem.Pcm(chapIdx, sentIdx, localSentences.size, chapters[chapIdx].title, pcm, plain.length, pauseMs, mime, seqAtStart))
                    } else {
                        ttsChannel.send(StoryTtsItem.Direct(chapIdx, sentIdx, localSentences.size, chapters[chapIdx].title, plain, pauseMs, seqAtStart))
                    }
                    sentIdx++
                }
                if (chapIdx >= chapters.size) finishedAll = true
            } finally {
                ttsChannel.close()
            }
        }

        var count = 0
        try {
            for (item in ttsChannel) {
                ensureActive()
                if (!isReading) break // abort_check khi Back/Stop
                // Đã bấm Rev/Next sau khi item này được synth → bỏ, không kể nhầm chỗ cũ
                if (item.jumpSeq != jumpSeq.intValue) continue
                // Karaoke sync: TO KHI AUDIO START (khong +1 truoc, khong to som luc cho synth).
                // Pcm: doi player chuyen sang cau nay that roi moi gan highlight.
                // Direct: gan ngay truoc khi speak (engine offline start ~ngay lap tuc).
                when (item) {
                    is StoryTtsItem.Pcm -> {
                        val mid = "story_${item.chapIdx}_${item.sentIdx}_${item.jumpSeq}"
                        playerVm.enqueueBytes(item.bytes, item.mime, mid)
                        awaitAudioStart(playerVm, mid, (item.textLen * 62L).coerceAtLeast(700L)) { item.jumpSeq != jumpSeq.intValue }
                        if (item.jumpSeq != jumpSeq.intValue) continue
                        currentSent = item.sentIdx
                        audioStarted = true
                        status = context.getString(R.string.reader_status_telling, item.title, item.sentIdx + 1, item.sentTotal)
                        awaitAudioDone(playerVm, mid, (item.textLen * 62L).coerceAtLeast(700L)) { item.jumpSeq != jumpSeq.intValue }
                    }
                    is StoryTtsItem.Direct -> {
                        currentSent = item.sentIdx
                        audioStarted = true
                        status = context.getString(R.string.reader_status_telling, item.title, item.sentIdx + 1, item.sentTotal)
                        try { offlineTts.speakOffline(item.text, speed) } catch (_: Exception) {}
                    }
                }
                if (item.pauseAfterMs > 0) delayUntilDone(item.pauseAfterMs) { item.jumpSeq != jumpSeq.intValue }
                count++
                if (count % STORY_SAVE_EVERY == 0) {
                    try {
                        db.book().upsert(
                            com.bibot.app.data.db.BookProgress(
                                bookId = selected!!.id,
                                path = selected!!.localFile?.absolutePath ?: selected!!.title,
                                position = item.chapIdx * 100 + (item.sentIdx + 1),
                                total = totalSentences
                            )
                        )
                    } catch (_: Exception) {}
                }
                // Micro pause per sentence, longer for comma (imitate STORY_PAUSE_COMMA 0.25s)
                val spokenText = if (item is StoryTtsItem.Direct) item.text else ""
                val isComma = spokenText.trimEnd().endsWith(",") || spokenText.trimEnd().endsWith("，")
                delay(if (isComma) 250 else 180)
                // Phase Doc/Ngat 8-10': VIP bo qua (khong bonus, khong dung, khong QC)
                if (com.bibot.app.BuildConfig.ENABLE_ADS && !vipActive && item.jumpSeq == jumpSeq.intValue) {
                    if (item is StoryTtsItem.Direct) phaseTracker.onBlock(item.text)
                    else if (item is StoryTtsItem.Pcm) phaseTracker.onChars(item.textLen)
                    val lv = phaseTracker.level()
                    val chapEnd = item.sentIdx + 1 >= item.sentTotal
                    // Chong loop counter: du dieu kien van phai cach lan ngat truoc >= 5 phut
                    // VA khac chuong da ngat (doan ngan doc tiep cung chuong khong kich QC lai).
                    val nowMs = System.currentTimeMillis()
                    val gapOk = nowMs - lastAdBreakMs >= 5 * 60 * 1000L
                    val chapOk = item.chapIdx != lastAdBreakChap
                    if (phaseTracker.shouldBreakAtBoundary(lv, chapEnd) && gapOk && chapOk) {
                        val bonus = com.bibot.app.core.ads.AdCopy.bonusChapter("vi", item.title)
                        status = bonus
                        try { offlineTts.speakOffline(bonus, speed) } catch (_: Exception) {}
                        if (item.jumpSeq == jumpSeq.intValue) {
                            try { offlineTts.stop() } catch (_: Exception) {}
                            playerVm.stopPlayback()
                            // Hien QC neu co san; khong co -> doc lien mach khong dung
                            var adShown = false
                            if (!isCotich) {
                                try {
                                    (context as? android.app.Activity)?.let { act ->
                                        adShown = com.bibot.app.data.ads.AdManager.showInterstitial(act)
                                    }
                                } catch (_: Exception) {}
                            }
                            phaseTracker.reset()
                            adPreloaded = false
                            lastAdBreakMs = nowMs
                            lastAdBreakChap = item.chapIdx
                            try {
                                Log.d("PhaseAd", "break story chap=${item.chapIdx} sent=${item.sentIdx} lv=$lv ${phaseTracker.debugStats()}")
                            } catch (_: Exception) {}
                            currentSent = item.sentIdx + 1
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
                    } else if (lv == 1 && !adPreloaded && !isCotich) {
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
            status = context.getString(R.string.reader_done_telling, selected?.title ?: "")
            currentSent = sentences.size // qua cuối → tắt highlight câu cuối
            isReading = false
        }
    }

    if (selected == null) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                // Gọn UI: bỏ tiêu đề + chú thích (đã có trên top bar) — chỉ giữ số lượng
                Text(stringResource(R.string.home_story_count, stories.size), style = MaterialTheme.typography.labelMedium)
                if (stories.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (isCotich) stringResource(R.string.home_cotich_empty)
                        else stringResource(R.string.home_story_empty),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            items(stories, key = { it.id }) { story ->
                Card(modifier = Modifier.fillMaxWidth().clickable { loadStory(story) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(listEmoji, fontSize = 22.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(story.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("vi • " + story.source + " • " + (if (story.isDownloaded) stringResource(R.string.book_is_downloaded) else stringResource(R.string.book_not_downloaded)) + " " + story.displaySize, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                        }
                        FilledTonalButton(onClick = { loadStory(story) }) { Text(stringResource(R.string.reader_play_tell), fontSize = 12.sp) }
                    }
                }
            }
            if (status != null) item {
                Text(status!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    } else {
        val meta = selected!!
        val chap = chapters.getOrNull(currentChap)
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
                    ControlInfoText(stringResource(R.string.reader_pos, currentChap + 1, chapters.size, currentSent + 1, sentences.size))
                    ControlInfoText((if (isCotich) com.bibot.app.ui.menu.BibotMode.COTICH else com.bibot.app.ui.menu.BibotMode.STORY).label() + " • 0.88x • pause 0.45s", secondary = true)
                }
            },
            controls = {
                ControlIconButton(
                    onClick = { jumpSentences(-STORY_SHORT_JUMP_SENTENCES) },
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = stringResource(R.string.reader_jump_back, STORY_SHORT_JUMP_SENTENCES)
                )
                ControlPlayButton(
                    isPlaying = isReading,
                    onClick = { if (isReading) { isReading = false; adBreakActive = false; offlineTts.stop(); playerVm.stopPlayback(); com.bibot.app.ui.player.KeepAwakeService.stop(context) } else { audioStarted = false; phaseTracker.reset(); adPreloaded = false; isReading = true; com.bibot.app.ui.player.KeepAwakeService.start(context) } },
                    contentDescription = if (isReading) stringResource(R.string.reader_pause) else stringResource(R.string.reader_play_tell)
                )
                ControlIconButton(
                    onClick = { jumpSentences(STORY_SHORT_JUMP_SENTENCES) },
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = stringResource(R.string.reader_jump_fwd, STORY_SHORT_JUMP_SENTENCES)
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
                        onClick = { isReading = false; adBreakActive = false; audioStarted = false; offlineTts.stop(); playerVm.stopPlayback(); com.bibot.app.ui.player.KeepAwakeService.stop(context); selected = null },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.reader_back), modifier = Modifier.size(20.dp))
                    }
                    Text(
                        "$listEmoji ${meta.title}",
                        maxLines = 1,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            if (chapters.size > 1) {
                ScrollableTabRow(selectedTabIndex = currentChap, modifier = Modifier.fillMaxWidth()) {
                    chapters.forEachIndexed { idx, c ->
                        Tab(selected = idx == currentChap, onClick = {
                            isReading = false; adBreakActive = false; audioStarted = false; offlineTts.stop(); playerVm.stopPlayback(); com.bibot.app.ui.player.KeepAwakeService.stop(context)
                            switchChapter(idx)
                        }, text = { Text(c.title.take(16), fontSize = 11.sp) })
                    }
                }
            }
            // Content — LazyColumn: chỉ compose câu đang hiển thị (truyện dài không treo UI)
            val listState = rememberLazyListState()
            val density = LocalDensity.current
            LaunchedEffect(currentSent, isReading) {
                if (isReading && sentences.isNotEmpty()) {
                    // Gop nhip 350ms: cau tieu de ngan hien chua kip nhin da bi cau sau cuop scroll.
                    // Chi cuon khi vi tri dung yen: het thay cau ngan nhay lien tuc.
                    delay(350)
                    // +1 bu item tieu de chuong dung dau list; offset am dua cau dang doc
                    // xuong ~1/3 man hinh (thay vi dinh sat mep tren) de cau ngan van nhin thay.
                    val total = listState.layoutInfo.totalItemsCount
                    val idx = (currentSent.coerceIn(0, sentences.lastIndex) + 1)
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
                item(key = "head-$currentChap") {
                    chap?.let {
                        Text(it.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                itemsIndexed(sentences, key = { idx, _ -> "$currentChap-$idx" }) { idx, s ->
                    val isCurrent = idx == currentSent && isReading && audioStarted
                    // UI không hiện marker PAUSE2SEC — hiện ký hiệu pause thay cho nó
                    val (displayText, hasPause) = com.bibot.app.core.text.TextCleaner.splitPause(s)
                    Column {
                        Text(if (hasPause) "$displayText ⏸2s" else displayText, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(vertical = 3.dp))
                        // Native tinh giua ky (TTS Bypass): Cotich ~80 (1 native/2 chu ky + badge gia dinh)
                        val adIdx = if (isCotich) com.bibot.app.ui.components.ads.NativeInlineAd.INDEX_COTICH else com.bibot.app.ui.components.ads.NativeInlineAd.INDEX_DEFAULT
                        if (idx == adIdx && sentences.size > adIdx) {
                            Spacer(Modifier.height(4.dp))
                            com.bibot.app.ui.components.ads.NativeInlineAd(lang = "vi", isFamily = isCotich)
                        }
                    }
                }
                item(key = "status-$currentChap") {
                    status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                }
            }
        }
    }
}
