package com.bibot.app.feature.reader

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.R
import com.bibot.app.data.books.BookMeta

@Composable
fun ViewScreen(
    onBack: () -> Unit,
    book: BookMeta? = null
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

    var viewMode by remember { mutableStateOf<ReaderView>(ReaderView.TEXT) }
    var selectedChapter by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar with back button
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = stringResource(R.string.reader_back)
                )
            }
            Text(
                b.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            IconButton(onClick = { viewMode = if (viewMode == ReaderView.TEXT) ReaderView.PAGE else ReaderView.TEXT }) {
                Icon(
                    if (viewMode == ReaderView.TEXT) Icons.Default.MenuBook else Icons.Default.PictureAsPdf,
                    contentDescription = "Toggle view mode",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Cover
        Box(
            Modifier
                .fillMaxWidth(0.8f)
                .height(180.dp)
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

        // Chapter list
        if (viewMode == ReaderView.TEXT) {
            Column(
                Modifier
                    .fillMaxWidth(0.8f)
                    .padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Mục lục",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Sample chapter list
                (1..10).forEach { i ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Chương $i: Chapter $i Title",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (i - 1 == selectedChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (i - 1 == selectedChapter) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (i - 1 == selectedChapter) {
                            IconButton(onClick = { /* play from here */ }) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play from here",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // PAGE mode - show page preview
            Box(
                Modifier
                    .fillMaxWidth(0.8f)
                    .height(180.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    "Trang $selectedChapter / 10",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        // TTS Controls
        Row(
            Modifier
                .fillMaxWidth(0.8f)
                .height(56.dp)
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Speaker button - read from beginning
            Box(
                Modifier
                    .width(48.dp)
                    .height(48.dp)
            ) {
                IconButton(onClick = { /* read from beginning */ }) {
                    Icon(
                        Icons.Default.VolumeUp,
                        contentDescription = "Đọc từ đầu",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            // Play button
            Box(
                Modifier
                    .width(56.dp)
                    .height(56.dp)
            ) {
                IconButton(onClick = { /* play */ }) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.reader_play_read),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            // Pause button
            Box(
                Modifier
                    .width(48.dp)
                    .height(48.dp)
            ) {
                IconButton(onClick = { /* pause */ }) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = stringResource(R.string.reader_pause),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Copyright notice
        Text(
            "© Unknown - All rights reserved",
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .padding(top = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}