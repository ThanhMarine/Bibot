package com.bibot.app.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bibot.app.R

/**
 * B5.5: dialog tóm tắt — đủ 4 trạng thái C2 (loading / content / gate + ad / error + Retry).
 * Dumb UI: logic ở caller (ReaderScreen).
 */
sealed interface SummaryUiState {
    data class Loading(val done: Int = 0, val total: Int = 0) : SummaryUiState
    data class Content(val text: String, val fromShared: Boolean) : SummaryUiState
    data class Gate(val noKey: Boolean, val left: Int) : SummaryUiState
    data class Error(val msg: String) : SummaryUiState
}

@Composable
fun SummaryDialog(
    title: String,
    isBook: Boolean,
    state: SummaryUiState,
    vipActive: Boolean,
    creditsLeft: Int,
    busyAd: Boolean,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onListen: (String) -> Unit,
    onWatchAd: () -> Unit,
    onBookLevel: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isBook) stringResource(R.string.summary_book_title) else stringResource(R.string.summary_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                when (state) {
                    is SummaryUiState.Loading -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.padding(top = 2.dp))
                            Text(
                                if (state.total > 0) stringResource(R.string.summary_loading, state.done, state.total)
                                else stringResource(R.string.summary_loading, 0, 0)
                            )
                        }
                    }
                    is SummaryUiState.Content -> {
                        if (state.fromShared) {
                            Text(
                                stringResource(R.string.summary_from_community),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(state.text, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.summary_ai_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    is SummaryUiState.Gate -> {
                        Text(
                            if (state.noKey) stringResource(R.string.summary_nokey)
                            else stringResource(R.string.summary_nocredit, state.left),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    is SummaryUiState.Error -> {
                        Text(
                            stringResource(R.string.summary_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (!vipActive) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.summary_left, creditsLeft),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state) {
                    is SummaryUiState.Content -> TextButton(onClick = { onListen(state.text) }) {
                        Text(stringResource(R.string.summary_listen))
                    }
                    is SummaryUiState.Gate -> if (!state.noKey) FilledTonalButton(
                        onClick = onWatchAd, enabled = !busyAd
                    ) { Text(stringResource(R.string.summary_watch_btn)) }
                    is SummaryUiState.Error -> TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.summary_retry))
                    }
                    else -> {}
                }
                if (!isBook && onBookLevel != null && state !is SummaryUiState.Loading) {
                    TextButton(onClick = onBookLevel) { Text(stringResource(R.string.summary_book_btn)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.summary_close)) }
            }
        }
    )
}
