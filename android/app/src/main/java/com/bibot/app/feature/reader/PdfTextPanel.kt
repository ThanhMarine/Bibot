package com.bibot.app.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.R
import com.bibot.app.core.text.PdfTextSearch

/**
 * E2.3 (phương án panel text-layer) — sheet dưới cho view PAGE của PDF có chữ:
 * - Trang hiện tại dạng text CHỌN ĐƯỢC (SelectionContainer → menu Copy/Share của hệ thống).
 * - Ô tìm trong toàn text đã trích → hit = TRANG có khớp → nhảy trang
 *   (PdfRenderer không expose glyph → không highlight trên bitmap; nhảy trang = dấu hiệu).
 * Không thêm lib (giữ AGPL-clean). Scan PDF không có chữ → button mở panel bị ẩn ngoài này.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfTextPanelSheet(
    pageTexts: List<String>?,
    pageCount: Int,
    currentPage: Int,
    query: String,
    onQuery: (String) -> Unit,
    onJumpPage: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.chrome_text_panel) + " — " +
                        stringResource(
                            R.string.chrome_page_of,
                            currentPage + 1,
                            pageCount.coerceAtLeast(1)
                        ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.dialog_close)
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.chrome_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))
            val q = query.trim()
            when {
                pageTexts == null -> Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.size(12.dp))
                    Text(
                        stringResource(R.string.chrome_text_panel_loading),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // Tìm ≥2 ký tự → danh sách TRANG có khớp (1 hit/trang), gõ tiếp vẫn sống.
                q.length >= 2 -> {
                    val hits = remember(q, pageTexts) { PdfTextSearch.search(pageTexts, q) }
                    if (hits.isEmpty()) {
                        Text(
                            stringResource(R.string.chrome_text_panel_none),
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 420.dp)
                        ) {
                            items(hits) { hit ->
                                TextButton(
                                    onClick = { onJumpPage(hit.page) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "${hit.page + 1}. ${hit.snippet}",
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
                // Mặc định: text trang HIỆN TẠI — chọn/copy bằng long-press (menu hệ thống).
                else -> {
                    val text = pageTexts.getOrNull(currentPage)?.trim().orEmpty()
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.chrome_text_panel_empty),
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            SelectionContainer {
                                Text(
                                    text,
                                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
