package com.bibot.app.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.R
import com.bibot.app.data.sync.FirestoreSync

/**
 * B5.8: màn Wiki cấp sách — tiến độ tóm tắt cộng đồng + đóng góp/báo cáo/mở chương.
 *
 * Chú ước danh sách chương (BẤT BIẾN có test ý định):
 * - chương từ TOC local (nếu máy đã mở/prepare sách);
 * - KHÔNG có local → lấy từ `chapterSummaries` của wiki doc (máy khác đã đóng góp);
 * - còn thiếu → `Chương N+1`. Máy chưa có sách vẫn thấy tiến độ wiki.
 * Luật công bằng (B5.7): share OFF = không đọc doc wiki (chỉ hiện local + banner nhắc bật).
 * Dumb UI: logic contribute/report/gating ở [BookDetailScreen].
 */
@Composable
fun WikiBookScreen(
    onBack: () -> Unit,
    bookId: String,
    bookTitle: String,
    bookLang: String,
    bookSource: String,
    chapters: List<Chapter>, // TOC local (rỗng = chưa prepare sách)
    localSummarized: Set<Int> = emptySet(), // chương đã có tóm tắt trong Room
    shareEnabled: Boolean = true,
    refreshKey: Int = 0, // tăng sau mỗi lần contribute để reload doc wiki
    busyIdx: Int? = null, // chương đang tóm tắt (spinner nút Đóng góp)
    onOpenChapter: (Int) -> Unit,
    onReportBad: (Int, String) -> Unit,
    onContribute: (Int) -> Unit
) {
    val context = LocalContext.current
    var wikiBook by remember { mutableStateOf<FirestoreSync.WikiBook?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bookId, refreshKey, shareEnabled) {
        if (!shareEnabled) { wikiBook = null; loading = false; error = null; return@LaunchedEffect }
        loading = true
        error = null
        try {
            wikiBook = FirestoreSync.getWikiBook(context, bookId)
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }

    val remote = wikiBook
    val remoteMax = remote?.chapterSummaries?.keys
        ?.maxOfOrNull { (it.toIntOrNull() ?: -1) + 1 } ?: 0
    val total = maxOf(chapters.size, remote?.totalChapters ?: 0, remoteMax)
    val items = (0 until total).map { idx ->
        chapters.getOrNull(idx)
            ?: remote?.chapterSummaries?.get(idx.toString())?.title?.takeIf { it.isNotBlank() }
                ?.let { Chapter(idx, it) }
            ?: Chapter(idx, "Chương ${idx + 1}")
    }
    fun hasSummary(idx: Int): Boolean =
        remote?.chapterSummaries?.get(idx.toString())?.summary?.isNotBlank() == true ||
            localSummarized.contains(idx)
    val summarizedCount = (0 until total).count { hasSummary(it) }
    val isComplete = total > 0 && summarizedCount >= total
    val progressPercent = if (total > 0) summarizedCount * 100 / total else 0

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.reader_back))
            }
            Column(Modifier
                .weight(1f)
                .padding(start = 8.dp)
            ) {
                Text(
                    bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(R.string.wiki_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Luật công bằng (B5.7): tắt share = không đọc/cộng tác wiki.
        if (!shareEnabled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    stringResource(R.string.wiki_share_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // AI Warning banner
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Cảnh báo",
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp).padding(end = 8.dp)
                )
                Text(
                    stringResource(R.string.wiki_ai_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Progress header
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                when {
                    loading -> Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.summary_loading, 0, 0), style = MaterialTheme.typography.bodySmall)
                    }
                    error != null -> Text(
                        stringResource(R.string.summary_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (isComplete) stringResource(R.string.wiki_complete, summarizedCount, total)
                                else stringResource(R.string.wiki_in_progress, summarizedCount, total),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!isComplete) {
                                Text(
                                    "$progressPercent%",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        // Progress bar
                        LinearProgressIndicator(
                            progress = { if (total > 0) summarizedCount.toFloat() / total else 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface
                        )
                        // Contribute button (đóng góp chương thiếu) — ẩn khi chưa có TOC.
                        if (total > 0 && !isComplete && shareEnabled) {
                            FilledTonalButton(
                                onClick = {
                                    val next = (0 until total).firstOrNull { !hasSummary(it) } ?: return@FilledTonalButton
                                    onContribute(next)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.wiki_contribute), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        if (total == 0 && !loading) {
            Text(
                stringResource(R.string.wiki_no_toc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Chapters list
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.idx }) { chapter ->
                val wikiChapter = remote?.chapterSummaries?.get(chapter.idx.toString())
                val summaryText = wikiChapter?.summary?.takeIf { it.isNotBlank() }
                val hasSummary = summaryText != null || localSummarized.contains(chapter.idx)
                val busy = busyIdx == chapter.idx
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        // Chapter header
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${chapter.idx + 1}. ${chapter.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            when {
                                busy -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                hasSummary -> Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = stringResource(R.string.wiki_has_summary),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        // Summary content
                        if (summaryText != null) {
                            Column(Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                            ) {
                                // AI warning
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "AI",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp).padding(end = 4.dp)
                                    )
                                    Text(
                                        stringResource(R.string.summary_ai_warning),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // Summary text
                                Text(
                                    summaryText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 5,
                                    overflow = TextOverflow.Ellipsis
                                )
                                // Action row
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    TextButton(onClick = { onOpenChapter(chapter.idx) }) {
                                        Text(stringResource(R.string.reader_play_read), fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { onReportBad(chapter.idx, summaryText) }) {
                                        Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                                        Text(stringResource(R.string.wiki_report_bad), fontSize = 11.sp)
                                    }
                                }
                            }
                        } else {
                            // Missing summary - contribute button
                            FilledTonalButton(
                                onClick = { onContribute(chapter.idx) },
                                enabled = !busy && shareEnabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.wiki_contribute_chapter), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
