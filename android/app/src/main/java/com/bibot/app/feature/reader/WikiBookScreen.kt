package com.bibot.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.R
import com.bibot.app.data.sync.FirestoreSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun WikiBookScreen(
    onBack: () -> Unit,
    bookId: String,
    bookTitle: String,
    bookLang: String,
    bookSource: String,
    chapters: List<Chapter>, // chapter titles for TOC
    onOpenChapter: (Int) -> Unit,
    onReportBad: (Int, String) -> Unit,
    onContribute: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wikiBook = remember { mutableStateOf<FirestoreSync.WikiBook?>(null) }
    val loading by remember { mutableStateOf(true) }
    val error by remember { mutableStateOf<String?>(null) }

    // Load wiki book
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val wiki = com.bibot.app.data.sync.FirestoreSync.getWikiBook(context, bookId)
                wikiBook.value = wiki
            } catch (e: Exception) {
                // error = e.message
            } finally {
                // loading = false
            }
        }
    }

    val totalChapters = wikiBook.value?.totalChapters ?: chapters.size
    val summarizedCount = wikiBook.value?.summarizedChapters ?: 0
    val isComplete = wikiBook.value?.status == "complete"
    val progressPercent = if (totalChapters > 0) (wikiBook.value?.summarizedChapters?.toFloat() ?: chapters.size.toFloat() / totalChapters * 100).toInt() else 0

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
                    "Tóm tắt wiki cộng đồng",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (isComplete) stringResource(R.string.wiki_complete) else stringResource(R.string.wiki_in_progress, summarizedCount, totalChapters),
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
                androidx.compose.material3.LinearProgressIndicator(
                    progress = if (totalChapters > 0) summarizedCount.toFloat() / totalChapters else 0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface
                )
                // Contribute button
                if (!isComplete) {
                    androidx.compose.material3.FilledTonalButton(
                        onClick = { /* show contribute dialog */ },
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

        // Chapters list
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(chapters) { chapter ->
                val wikiChapter = wikiBook.value?.chapterSummaries?.get(chapter.idx.toString())
                val hasSummary = wikiChapter?.summary?.isNotBlank() == true
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
                            if (hasSummary) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Đã tóm tắt",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        // Summary content
                        if (hasSummary) {
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
                                    wikiBook.value?.chapterSummaries?.get(chapter.idx.toString())?.summary ?: "",
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
                                    TextButton(onClick = { onReportBad(chapter.idx, wikiBook.value?.chapterSummaries?.get(chapter.idx.toString())?.summary ?: "") }) {
                                        Icon(Icons.Default.Flag, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                                        Text(stringResource(R.string.wiki_report_bad), fontSize = 11.sp)
                                    }
                                }
                            }
                        } else {
                            // Missing summary - contribute button
                            androidx.compose.material3.FilledTonalButton(
                                onClick = { onContribute(chapter.idx) },
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