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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.R
import com.bibot.app.core.text.PdfTextSearch
import com.bibot.app.data.db.PdfAnnotation

/**
 * E2.3 (phương án panel text-layer) — sheet dưới cho view PAGE của PDF có chữ:
 * - Trang hiện tại dạng text CHỌN ĐƯỢC (BasicTextField readOnly → Copy/Share hệ thống).
 * - Ô tìm trong toàn text đã trích → hit = TRANG có khớp → nhảy trang.
 * E2.4 (annotation persist): selection controlled qua TextFieldValue (public
 * stable — SelectionContainer controlled là internal trong foundation 1.7.4)
 * → nút Highlight lưu offset theo fileHash (E1.3); nền vàng qua
 * VisualTransformation; chips "P3 • snippet" nhảy trang / xóa.
 * Không thêm lib (giữ AGPL-clean).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfTextPanelSheet(
    pageTexts: List<String>?,
    pageCount: Int,
    currentPage: Int,
    query: String,
    annotations: List<PdfAnnotation>,
    canHighlight: Boolean,
    onQuery: (String) -> Unit,
    onJumpPage: (Int) -> Unit,
    onSaveHighlight: (Int, Int, String) -> Unit,
    onDeleteAnnotation: (Long) -> Unit,
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
                // Mặc định: text trang HIỆN TẠI — chọn/copy + highlight theo fileHash.
                else -> {
                    val text = pageTexts.getOrNull(currentPage)?.trim().orEmpty()
                    val pageAnnotations = remember(annotations, currentPage) {
                        annotations.filter { it.page == currentPage }
                    }
                    if (text.isEmpty()) {
                        Text(
                            stringResource(R.string.chrome_text_panel_empty),
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        // Selection controlled qua TextFieldValue (public stable) —
                        // readOnly chặn sửa chữ, selection vẫn chọn được → Copy menu.
                        var fieldValue by remember(currentPage, text) {
                            mutableStateOf(TextFieldValue(text = text, selection = TextRange(0)))
                        }
                        val selRange = if (fieldValue.selection.collapsed) null
                        else PdfAnnotationLogic.clampSelection(
                            fieldValue.selection.start, fieldValue.selection.end, text.length
                        )
                        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            // Chips "P3 • snippet" — tap nhảy trang, X xóa (cả sách).
                            if (annotations.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.chrome_highlights) +
                                        " (${annotations.size})",
                                    Modifier.padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                annotations.take(40).forEach { a ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { onJumpPage(a.page) },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                PdfAnnotationLogic.chipLabel(a.page, a.text.take(60)),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontSize = 13.sp,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteAnnotation(a.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = stringResource(R.string.chrome_highlight_delete),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            // Nút Highlight hiện khi đang chọn chữ + đã có fileHash.
                            if (canHighlight && selRange != null) {
                                TextButton(
                                    onClick = {
                                        onSaveHighlight(
                                            selRange.first,
                                            selRange.last + 1,
                                            PdfAnnotationLogic.snippet(text, selRange.first, selRange.last + 1, maxChars = 300)
                                        )
                                        fieldValue = fieldValue.copy(selection = TextRange(0))
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                                ) {
                                    Text(stringResource(R.string.chrome_highlight))
                                }
                            }
                            Column(
                                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            ) {
                                // Nền vàng highlight qua VisualTransformation (readOnly →
                                // text bất biến → OffsetMapping.Identity an toàn).
                                val hlTransformation = remember(text, pageAnnotations) {
                                    VisualTransformation { original ->
                                        val annotated = buildAnnotatedString {
                                            append(original.text)
                                            PdfAnnotationLogic.highlightRanges(
                                                original.text,
                                                pageAnnotations.map { Triple(it.startOffset, it.endOffset, it.text) }
                                            ).forEach { r ->
                                                addStyle(
                                                    SpanStyle(background = Color(0x55FFD54F)),
                                                    r.first,
                                                    r.last + 1
                                                )
                                            }
                                        }
                                        TransformedText(annotated, OffsetMapping.Identity)
                                    }
                                }
                                BasicTextField(
                                    value = fieldValue,
                                    onValueChange = { if (it.text == text) fieldValue = it },
                                    readOnly = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(Color.Transparent),
                                    visualTransformation = hlTransformation,
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
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
