package com.bibot.app.feature.reader

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibot.app.R
import com.bibot.app.core.text.BlockTextReader
import com.bibot.app.core.text.BookPrepStore
import com.bibot.app.data.ads.AdManager
import com.bibot.app.data.ads.AdsPreferences
import com.bibot.app.data.ads.AdsState
import com.bibot.app.data.ads.isVipActive
import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.books.BookRepository
import com.bibot.app.data.db.BookProgress
import com.bibot.app.data.db.BibotDatabase
import com.bibot.app.data.files.FilePaths
import com.bibot.app.data.library.ReadingPosition
import com.bibot.app.data.store.BibotPrefs
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.data.store.BotPromptManager
import com.bibot.app.data.summary.SummaryRepository
import com.bibot.app.data.sync.FirestoreSync
import com.bibot.app.ui.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * B2 + B5.8: màn About sách — bìa lớn, ĐỌC (đúng bookmark), đọc từ đầu, hàng action
 * (trong đó Wiki sách), metadata. Nhận SÁCH THẬT từ kệ (icon info) — không còn mock.
 *
 * Wiki (B5.8): list chương từ prep (tự build `.prep` nếu chưa có — reader sau đó hit cache),
 * contribute = SummaryRepository (gating NoCredit/NeedKey/Giữ lượt) + upload `wiki_books`
 * KHI user bật chia sẻ (luật công bằng B5.7); báo xấu → dialog lý do → subcollection reports.
 * Dumb UI Wiki ở [WikiBookScreen]; dialog tóm tắt reuse [SummaryDialog].
 */
@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    book: BookMeta? = null,
    progress: BookProgress? = null,
    playerVm: PlayerViewModel = viewModel(),
    // B2.3/B5.8: mở reader — (book, chapterIdx=-1 theo progress, sentenceIdx).
    onOpenReader: (BookMeta, Int, Int) -> Unit = { _, _, _ -> },
) {
    if (book == null) {
        // C2: empty state (không phải màn mock).
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.book_detail_empty), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onBack) { Text(stringResource(R.string.nav_back_home)) }
        }
        return
    }
    var b by remember(book) { mutableStateOf(book) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BookRepository(context) }
    val db = remember { BibotDatabase.get(context) }
    val prefs = remember { BibotPreferences(context) }
    val prefsState by prefs.prefsFlow.collectAsState(initial = BibotPrefs())
    val adsPrefs = remember { AdsPreferences(context) }
    val adsState by adsPrefs.adsStateFlow.collectAsState(initial = AdsState())
    val vipActive = isVipActive(adsState)

    var wikiOpen by remember { mutableStateOf(false) }
    var prog by remember { mutableStateOf(progress) }
    var chapterTitles by remember { mutableStateOf<List<String>>(emptyList()) }
    var prep by remember { mutableStateOf<BookPrepStore.PrepSession?>(null) }
    var chaptersLoading by remember { mutableStateOf(false) }
    // B5.8b: tải sách ngay từ Detail (sách public chưa tải) + nạp lại prep/TOC sau khi tải.
    var downloading by remember { mutableStateOf(false) }
    var prepKey by remember { mutableIntStateOf(0) }
    var localSummarized by remember { mutableStateOf(setOf<Int>()) }
    var wikiRefresh by remember { mutableIntStateOf(0) }
    // B5.8: contribute (gating + upload wiki) — dialog tóm tắt 4 trạng thái.
    var busyIdx by remember { mutableStateOf<Int?>(null) }
    var summaryIdx by remember { mutableStateOf<Int?>(null) }
    var summaryTitle by remember { mutableStateOf("") }
    var summaryState by remember { mutableStateOf<SummaryUiState?>(null) }
    var summaryJob by remember { mutableStateOf<Job?>(null) }
    var busyAd by remember { mutableStateOf(false) }
    // B5.8: báo xấu tóm tắt (pending review).
    var reportIdx by remember { mutableStateOf<Int?>(null) }
    var reportReason by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { prep?.close() } }

    LaunchedEffect(b.id, prepKey) {
        if (progress == null) {
            prog = try { db.book().get(b.id) } catch (_: Exception) { null }
        }
        localSummarized = try {
            db.summaries().getByBook(b.id).map { it.chapterIdx }.filter { it >= 0 }.toSet()
        } catch (_: Exception) { emptySet() }

        val file = b.localFile?.takeIf { it.exists() } ?: return@LaunchedEffect
        chaptersLoading = true
        withContext(Dispatchers.IO) {
            try {
                val prepDir = FilePaths.prepDir(context)
                val cacheKey = b.id.ifBlank { b.title }
                val heading = BlockTextReader.Heading.MARKDOWN_OR_STORY
                var opened = BookPrepStore.open(prepDir, cacheKey, file, heading)
                // Chưa có `.prep` → build 1 lần (cùng cache với reader, không làm lại).
                if (opened == null) opened = BookPrepStore.build(prepDir, cacheKey, file, heading, b.title)
                if (opened != null) {
                    val session = BookPrepStore.PrepSession.of(opened, BookPrepStore.openSentences(opened))
                    prep = session
                    chapterTitles = session.titles
                }
            } catch (_: Exception) {}
        }
        chaptersLoading = false
    }

    val resumePos = prog?.let { ReadingPosition.fromProgress(it) } ?: ReadingPosition()
    val lang = if (b.lang == "en") "en" else "vi"

    // B5.8: upload/cập nhật doc `wiki_books` cấp sách (chỉ khi user bật chia sẻ).
    suspend fun uploadWikiBook(idx: Int, chapterTitle: String, summary: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val cur = FirestoreSync.getWikiBook(context, b.id)
                val map = LinkedHashMap(cur?.chapterSummaries ?: emptyMap())
                map[idx.toString()] = FirestoreSync.WikiChapterSummary(
                    chapterIdx = idx,
                    title = chapterTitle,
                    summary = summary,
                    model = SummaryRepository.sanitizeModel(prefsState.geminiModel),
                    promptVer = BotPromptManager.SUMMARY_PROMPT_VER,
                    fromShared = false,
                    shared = true
                )
                val total = maxOf(cur?.totalChapters ?: 0, chapterTitles.size)
                val done = map.values.count { it.summary.isNotBlank() }
                val now = System.currentTimeMillis()
                FirestoreSync.putWikiBook(
                    context,
                    FirestoreSync.WikiBook(
                        bookId = b.id,
                        title = b.title.take(300),
                        lang = lang,
                        source = (b.source ?: "local").take(500),
                        chapterSummaries = map,
                        totalChapters = total,
                        summarizedChapters = done,
                        model = SummaryRepository.sanitizeModel(prefsState.geminiModel),
                        promptVer = BotPromptManager.SUMMARY_PROMPT_VER,
                        status = if (total > 0 && done >= total) "complete" else "in_progress",
                        createdAt = cur?.createdAt ?: now,
                        updatedAt = now
                    )
                )
            } catch (_: Exception) { false }
        }
    }

    // B5.8: tóm tắt 1 chương từ màn About — cùng pipeline với TOC ở reader (cache → wiki → LLM).
    fun contribute(idx: Int) {
        val session = prep
        if (session == null) {
            // Sách chưa tải / chưa build prep — báo thay vì chết im lặng (B5.8b).
            if (!chaptersLoading) Toast.makeText(
                context, context.getString(R.string.book_not_downloaded), Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (summaryJob?.isActive == true || busyIdx != null) return
        val title = chapterTitles.getOrElse(idx) { "Chương ${idx + 1}" }
        summaryIdx = idx
        summaryTitle = title
        summaryState = SummaryUiState.Loading()
        busyIdx = idx
        val model = prefsState.geminiModel
        val share = prefsState.summaryShareEnabled
        val keys = SummaryRepository.LlmKeys(prefsState.geminiApiKey, model, prefsState.openRouterKey)
        summaryJob = scope.launch(Dispatchers.IO) {
            try {
                val text = try {
                    session.content(idx).take(BotPromptManager.SUMMARY_MAX_CHARS)
                } catch (_: Exception) { "" }
                if (text.isBlank()) { summaryState = SummaryUiState.Error("empty"); return@launch }
                when (val r = SummaryRepository.summarizeChapter(
                    context, b.id, idx, title, text, lang, keys, vipActive, share
                )) {
                    is SummaryRepository.Outcome.Ready -> {
                        summaryState = SummaryUiState.Content(r.text, r.fromShared)
                        localSummarized = localSummarized + idx
                        // Luật công bằng: chỉ upload khi user bật chia sẻ.
                        if (share && uploadWikiBook(idx, title, r.text)) wikiRefresh++
                    }
                    is SummaryRepository.Outcome.NoCredit ->
                        summaryState = SummaryUiState.Gate(false, adsPrefs.getRewardedSummary())
                    is SummaryRepository.Outcome.NeedKey ->
                        summaryState = SummaryUiState.Gate(true, 0)
                    is SummaryRepository.Outcome.Failed ->
                        summaryState = SummaryUiState.Error(r.msg)
                }
            } catch (_: Exception) {
                summaryState = SummaryUiState.Error("error")
            } finally {
                busyIdx = null
            }
        }
    }

    // B5.4: xem QC +3 lượt rồi tự retry chương đang gate (mirror reader).
    fun watchAdRetry() {
        val activity = context as? Activity ?: return
        if (!com.bibot.app.ui.components.ads.canShowRewarded(adsState)) {
            Toast.makeText(context, context.getString(R.string.ads_cooldown), Toast.LENGTH_SHORT).show()
            return
        }
        if (busyAd) return
        busyAd = true
        AdManager.onRewardedClaimed = {
            scope.launch {
                try {
                    adsPrefs.claimRewardedSummary(3)
                    summaryIdx?.let { contribute(it) }
                } catch (_: Exception) {
                } finally {
                    busyAd = false
                }
            }
        }
        val shown = try { AdManager.showRewarded(activity) } catch (_: Exception) { false }
        if (!shown) {
            busyAd = false
            Toast.makeText(context, context.getString(R.string.ads_loading), Toast.LENGTH_SHORT).show()
        }
    }

    // B5.8 UI: wiki thay hẳn detail (trước đây 2 màn xếp chồng trong 1 Column → bị nén).
    if (wikiOpen) {
        WikiBookScreen(
            onBack = { wikiOpen = false },
            bookId = b.id,
            bookTitle = b.title,
            bookLang = lang,
            bookSource = b.source ?: "local",
            chapters = chapterTitles.mapIndexed { i, t -> Chapter(i, t) },
            localSummarized = localSummarized,
            shareEnabled = prefsState.summaryShareEnabled,
            refreshKey = wikiRefresh,
            busyIdx = busyIdx,
            onOpenChapter = { idx -> onOpenReader(b, idx, 0) },
            onReportBad = { idx, _ -> reportIdx = idx; reportReason = "" },
            onContribute = { contribute(it) }
        )
    } else {
    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Large cover
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(220.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
        ) {
            Text(
                b.title.take(30),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        // Read button - prominent floating action → B2.3: vào đúng bookmark.
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(56.dp)
                .padding(top = 16.dp)
        ) {
            IconButton(onClick = { onOpenReader(b, resumePos.chap, resumePos.sent) }) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.reader_play_read),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                stringResource(R.string.reader_play_read),
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Speaker button - read from beginning
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(48.dp)
                .padding(top = 8.dp)
        ) {
            IconButton(onClick = { onOpenReader(b, 0, 0) }) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = "Đọc từ đầu",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "Đọc từ đầu",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // 7 action items row
        Row(
            Modifier
                .fillMaxWidth(0.8f)
                .height(44.dp)
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Download — B5.8b: tải thật (pdrive/url) → nạp lại prep/TOC để Wiki chạy được.
            IconButton(
                onClick = {
                    if (downloading) return@IconButton
                    downloading = true
                    scope.launch {
                        try {
                            val target = b
                            val f = withContext(Dispatchers.IO) { repo.download(target) }
                            b = target.copy(localFile = f, sizeBytes = f.length())
                            prepKey++
                            Toast.makeText(
                                context,
                                context.getString(R.string.book_download_done, target.title),
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.summary_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        downloading = false
                    }
                },
                enabled = !downloading
            ) {
                if (downloading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.book_download),
                        tint = if (b.localFile?.exists() == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            // Favorite
            IconButton(onClick = { /* toggle favorite */ }) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = "Yêu thích",
                    tint = MaterialTheme.colorScheme.error
                )
            }
            // Read later
            IconButton(onClick = { /* read later */ }) {
                Icon(
                    Icons.Default.BookmarkAdd,
                    contentDescription = "Đọc sau",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            // Share
            IconButton(onClick = { /* share */ }) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Chia sẻ",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            // Wiki
            IconButton(onClick = { wikiOpen = true }) {
                Icon(
                    Icons.Default.MenuBook,
                    contentDescription = "Wiki sách",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // More options
            IconButton(onClick = { /* more */ }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Tùy chọn",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            // Delete
            IconButton(onClick = { /* delete */ }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
            // Restore (in trash)
            IconButton(onClick = { /* restore */ }) {
                Icon(
                    Icons.Default.Restore,
                    contentDescription = "Khôi phục",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Metadata lines
        Column(
            Modifier
                .fillMaxWidth(0.8f)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Language / Format
            Text(
                "Ngôn ngữ: ${b.lang?.uppercase() ?: "VI"}  •  Định dạng: ${b.mimeType?.substringAfterLast("/")?.uppercase() ?: "EPUB"}  •  Kích thước: ${(b.sizeBytes / 1024 / 1024).toString()} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // Bookmark + reading time (A2.4: đọc từ cột mới, đúng >100 câu/chương)
            Text(
                "Bookmark: ${resumePos.resumeLabel()}  •  Đã đọc: ${formatReadTime(prog?.updatedAt ?: 0L)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // Trạng thái nạp mục lục (C2: loading/empty rõ ràng thay vì im lặng)
            if (chaptersLoading) {
                Row(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.reader_cleaning), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            } else if (chapterTitles.isNotEmpty()) {
                Text(
                    stringResource(R.string.book_detail_toc, chapterTitles.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (b.localFile == null) {
                Text(
                    stringResource(R.string.book_not_downloaded),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
    }

    // Dialog tóm tắt (Loading/Content/Gate+QC/Error+Retry) — reuse component của reader.
    summaryIdx?.let { idx ->
        val st = summaryState ?: SummaryUiState.Loading()
        SummaryDialog(
            title = summaryTitle,
            isBook = false,
            state = st,
            vipActive = vipActive,
            creditsLeft = adsState.rewardedSummary,
            busyAd = busyAd,
            onDismiss = { summaryIdx = null; summaryState = null },
            onRetry = { contribute(idx) },
            onListen = { text ->
                scope.launch {
                    try {
                        playerVm.stopAll()
                        val sp = if (lang == "en") prefsState.voiceSpeed else prefsState.storySpeed
                        playerVm.speakOffline(text, sp, lang = lang, mediaId = "wiki_$idx")
                    } catch (_: Exception) {}
                }
            },
            onWatchAd = { watchAdRetry() }
        )
    }

    // Báo xấu tóm tắt → subcollection `reports` (pending review).
    reportIdx?.let { idx ->
        AlertDialog(
            onDismissRequest = { reportIdx = null },
            title = { Text(stringResource(R.string.wiki_report_bad)) },
            text = {
                OutlinedTextField(
                    value = reportReason,
                    onValueChange = { reportReason = it.take(1000) },
                    label = { Text(stringResource(R.string.wiki_report_reason)) },
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val reason = reportReason.trim().ifBlank { "-" }
                    reportIdx = null
                    scope.launch(Dispatchers.IO) {
                        val ok = try {
                            FirestoreSync.reportWikiChapterSummary(context, b.id, idx, reason)
                        } catch (_: Exception) { false }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(if (ok) R.string.wiki_report_done else R.string.summary_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) { Text(stringResource(R.string.wiki_report_send)) }
            },
            dismissButton = {
                TextButton(onClick = { reportIdx = null }) { Text(stringResource(R.string.summary_close)) }
            }
        )
    }
}

private fun formatReadTime(timestamp: Long): String {
    if (timestamp <= 0L) return "—"
    val now = System.currentTimeMillis()
    val diff = (now - timestamp) / 1000
    val h = diff / 3600
    val m = (diff % 3600) / 60
    return if (h > 0) "${h}h ${m}m trước" else "${m}m trước"
}
