package com.bibot.app.feature.reader

import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.R
import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.db.BookProgress
import com.bibot.app.data.library.ReadingPosition

@Composable
fun BookDetailScreen(
    onBack: () -> Unit,
    book: BookMeta? = null,
    progress: BookProgress? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val b = book ?: BookMeta(
        id = "preview",
        title = "Sample Book Title",
        lang = "vi",
        source = "local",
        mimeType = "application/epub+zip",
        sizeBytes = 2 * 1024 * 1024,
        collection = ""
    )
    val p = progress ?: BookProgress(
        bookId = "preview",
        path = "",
        position = 402,
        total = 500,
        updatedAt = System.currentTimeMillis(),
        anchorPage = -1,
        anchorOffset = 0,
        readMode = "read",
        fileHash = ""
    )

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
                    RoundedCornerShape(12.dp)
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

        // Read button - prominent floating action
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(56.dp)
                .padding(top = 16.dp)
        ) {
            IconButton(onClick = { /* start reading */ }) {
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
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Speaker button - read from beginning
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(48.dp)
                .padding(top = 8.dp)
        ) {
            IconButton(onClick = { /* read from beginning */ }) {
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
                color = MaterialTheme.colorScheme.primary
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
            // Download
            IconButton(onClick = { /* download */ }) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = stringResource(R.string.book_download),
                    tint = MaterialTheme.colorScheme.onSurface
                )
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            // Bookmark + reading time (A2.4: đọc từ cột mới, đúng >100 câu/chương)
            Text(
                "Bookmark: ${ReadingPosition.fromProgress(p).resumeLabel()}  •  Đã đọc: ${formatReadTime(p.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private fun formatReadTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - timestamp) / 1000
    val h = diff / 3600
    val m = (diff % 3600) / 60
    return if (h > 0) "${h}h ${m}m trước" else "${m}m trước"
}