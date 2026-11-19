package com.bibot.app.feature.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.core.text.EpubReader
import com.bibot.app.ui.player.contentBottomInset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// LRU mềm cho bitmap ảnh minh họa (RGB_565, ≤ targetW px).
private const val RICH_IMG_CACHE_N = 8

private fun evictRichImages(images: SnapshotStateMap<String, Bitmap?>) {
    while (images.size > RICH_IMG_CACHE_N) {
        val oldest = images.keys.firstOrNull() ?: break
        val b = images.remove(oldest)
        try { b?.recycle() } catch (_: Exception) {}
    }
}

/** Tính số dòng ước tính cho một block (E2.5 Week 4). */
internal fun blockLineCount(
    block: EpubReader.RichBlock,
    targetW: Int,
    fontSizeSp: Float
): Int {
    val lineH = fontSizeSp * 1.2f
    return when (block) {
        is EpubReader.RichBlock.Para -> {
            val textLen = block.text.length
            val charsPerLine = ((targetW - 20).toFloat() / (fontSizeSp * 0.6f).coerceAtLeast(10f)).toInt().coerceAtLeast(1)
            (textLen / charsPerLine).coerceAtLeast(1)
        }
        is EpubReader.RichBlock.Figure -> 5
        is EpubReader.RichBlock.Table -> block.rows.size + 2
        is EpubReader.RichBlock.Rule -> 1
        is EpubReader.RichBlock.Footnote -> 1
        is EpubReader.RichBlock.Dropcap -> 1
        else -> 1
    }
}

/**
 * Tìm khối chứa câu TTS (karaoke rich): khớp có THỨ TỰ từ khối câu trước
 * (đoạn lặp nguyên văn không highlight nhầm), chuẩn hóa khoảng trắng.
 * @return index khối hoặc -1. Pure — có unit test.
 */
internal fun findSentenceBlock(
    blockTexts: List<String>,
    sentences: List<String>,
    sentIdx: Int,
    startBlock: Int
): Int {
    if (sentIdx !in sentences.indices || blockTexts.isEmpty()) return -1
    fun norm(s: String): String = s.lowercase().filterNot { it.isWhitespace() }
    val key = norm(sentences[sentIdx]).take(24)
    if (key.isEmpty()) return -1
    val from = startBlock.coerceIn(0, (blockTexts.size - 1).coerceAtLeast(0))
    for (pass in 0..1) {
        val range = if (pass == 0) from until blockTexts.size else 0 until from
        for (i in range) {
            if (norm(blockTexts[i]).contains(key)) return i
        }
    }
    return -1
}

/**
 * E1 rich — đường XEM nguyên bản cho EPUB (scope B, ReadEra-like):
 * text giữ đậm/nghiêng/heading/caption + ảnh minh họa decode local
 * (BitmapFactory sampled, +0 lib). TTS vẫn đọc từ câu text (sentences),
 * karaoke highlight khối chứa câu (mức khối/câu — engine không có timestamp từ).
 */
@Composable
fun EpubRichView(
    file: File,
    spineIdx: Int,
    defaultTitle: String = "Nội dung",
    fontSizeSp: Float = 18f,
    // B6.16: style prefs đầy đủ (ngang TEXT view).
    lineHeightMultiplier: Float = 1.2f,
    paragraphMarginDp: Float = 8f,
    // B6.16: karaoke chính xác — match có thứ tự (thay contains lẻ).
    sentences: List<String> = emptyList(),
    currentSentence: Int = -1,
    karaokeActive: Boolean = false,
    onTap: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val targetW = ((LocalConfiguration.current.screenWidthDp * density.density).toInt())
        .coerceIn(240, 1200)
    val screenH = (LocalConfiguration.current.screenHeightDp * density.density).toInt()
    val lineH = fontSizeSp * lineHeightMultiplier * density.density

    var blocks by remember(file.absolutePath, spineIdx) {
        mutableStateOf<List<EpubReader.RichBlock>>(emptyList())
    }
    LaunchedEffect(file.absolutePath, spineIdx) {
        blocks = withContext(Dispatchers.IO) {
            try {
                EpubReader.richChapterAt(file, defaultTitle, spineIdx)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val images = remember(file.absolutePath, spineIdx) { mutableStateMapOf<String, Bitmap?>() }
    val imgFailed = remember(file.absolutePath, spineIdx) { mutableStateMapOf<String, Boolean>() }
    // B6.16: zoom decode bitmap RIÊNG (không mượn pool — tránh race recycle → đen).
    var zoomPath by remember(file.absolutePath, spineIdx) { mutableStateOf<String?>(null) }
    var zoomAlt by remember(file.absolutePath, spineIdx) { mutableStateOf<String?>(null) }
    var zoomDecoded by remember(file.absolutePath, spineIdx) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(file.absolutePath, spineIdx) {
        onDispose { try { zoomDecoded?.recycle() } catch (_: Exception) {} }
    }

    // E2.5 Week 4: Trạng thái phân trang (thay LazyColumn liên tục)
    var currentPage by remember { mutableStateOf(0) }
    var totalPages by remember { mutableStateOf(1) }
    var pageBlocks by remember { mutableStateOf<List<EpubReader.RichBlock>>(emptyList()) }

    // Tính toán phân trang khi blocks thay đổi
    LaunchedEffect(blocks, fontSizeSp, lineHeightMultiplier) {
        val contentH = screenH - 100 // Trừ khoảng trống top/bottom
        val maxLinesPerPage = (contentH.toFloat() / lineH).coerceAtLeast(1f).toInt().coerceAtLeast(1)

        var currentLines = 0
        var currentPageBlocks = mutableListOf<EpubReader.RichBlock>()
        var allPageBlocks = mutableListOf<MutableList<EpubReader.RichBlock>>()
        allPageBlocks.add(currentPageBlocks)

        for (block in blocks) {
            val blockLines = blockLineCount(block, targetW, fontSizeSp)
            if (currentLines + blockLines > maxLinesPerPage && currentPageBlocks.isNotEmpty()) {
                // Trang mới
                currentPageBlocks = mutableListOf()
                allPageBlocks.add(currentPageBlocks)
                currentLines = blockLines
            } else {
                currentLines += blockLines
            }
            currentPageBlocks.add(block)
        }

        pageBlocks = allPageBlocks.firstOrNull() ?: emptyList()
        totalPages = allPageBlocks.size
        if (currentPage >= totalPages) {
            currentPage = totalPages.coerceAtLeast(1) - 1
        }
    }

    // B6.16: map câu TTS → khối (nhớ vị trí câu trước để đoạn lặp không nhầm).
    val blockTexts = remember(blocks) {
        blocks.map { b -> if (b is EpubReader.RichBlock.Para) b.text else "" }
    }
    val lastBlockBySent = remember(file.absolutePath, spineIdx) { mutableStateMapOf<Int, Int>() }
    val hlBlock = remember(blocks, sentences, currentSentence, karaokeActive) {
        if (!karaokeActive || currentSentence !in sentences.indices) -1
        else {
            val start = lastBlockBySent[currentSentence - 1] ?: 0
            findSentenceBlock(blockTexts, sentences, currentSentence, start)
                .also { if (it >= 0) lastBlockBySent[currentSentence] = it }
        }
    }

    // E2.5 Week 4: Phân trang thay vì LazyColumn liên tục
    // UX 2026-11-16: cùng khung dàn trang với TEXT view — cột ≤640dp, căn giữa,
    // lề 2 bên bằng nhau (trước đây chữ dính sát mép màn hình).
    Column(
        modifier = modifier.balancedTextColumn().padding(horizontal = ReaderTextGutter),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        pageBlocks.forEachIndexed { blkIdx, b ->
            when (b) {
                is EpubReader.RichBlock.Figure -> {
                    val bmp = images[b.path]
                    if (bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = b.alt.ifBlank { null },
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f))
                                // B6.16: bấm ảnh → zoom fullscreen (thay vì toggle menu).
                                .clickable {
                                    // B6.16: bấm ảnh → zoom fullscreen (thay vì toggle menu).
                                    try { zoomDecoded?.recycle() } catch (_: Exception) {}
                                    zoomDecoded = null
                                    zoomAlt = b.alt.ifBlank { null }
                                    zoomPath = b.path
                                }
                        )
                    } else if (imgFailed[b.path] != true) {
                        Box(
                            Modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHighest,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onTap() },
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        }
                        LaunchedEffect(b.path) {
                            if (!images.containsKey(b.path) && imgFailed[b.path] != true) {
                                val d = withContext(Dispatchers.IO) {
                                    decodeEpubImage(file, b.path, targetW)
                                }
                                if (d != null) {
                                    images[b.path] = d
                                    evictRichImages(images)
                                } else {
                                    imgFailed[b.path] = true
                                }
                            }
                        }
                    }
                }
                is EpubReader.RichBlock.Para -> {
                    val hl = blkIdx == hlBlock
                    val ann = buildAnnotatedString {
                        for (s in b.lines) {
                            withStyle(
                                SpanStyle(
                                    fontWeight = if (s.bold) FontWeight.Bold else null,
                                    fontStyle = if (s.italic) FontStyle.Italic else null
                                )
                            ) {
                                append(s.text)
                            }
                        }
                    }
                    val (style, align, size) = when (b.style) {
                        EpubReader.RichStyle.H1 -> Triple(
                            MaterialTheme.typography.headlineSmall, TextAlign.Start, fontSizeSp + 6f
                        )
                        EpubReader.RichStyle.H2 -> Triple(
                            MaterialTheme.typography.titleLarge, TextAlign.Start, fontSizeSp + 3f
                        )
                        EpubReader.RichStyle.H3 -> Triple(
                            MaterialTheme.typography.titleMedium, TextAlign.Start, fontSizeSp + 1f
                        )
                        EpubReader.RichStyle.CAPTION -> Triple(
                            MaterialTheme.typography.bodySmall, TextAlign.Center, fontSizeSp - 3f
                        )
                        EpubReader.RichStyle.CENTER -> Triple(
                            MaterialTheme.typography.bodyLarge, TextAlign.Center, fontSizeSp
                        )
                        EpubReader.RichStyle.BODY -> Triple(
                            MaterialTheme.typography.bodyLarge, TextAlign.Justify, fontSizeSp
                        )
                    }
                    Text(
                        text = ann,
                        style = style.copy(
                            fontSize = size.coerceAtLeast(11f).sp,
                            // B6.16: giãn dòng + cách đoạn theo prefs (ngang TEXT view).
                            lineHeight = (size.coerceAtLeast(11f) * lineHeightMultiplier).sp,
                            textAlign = align,
                            fontWeight = if (b.style == EpubReader.RichStyle.H1 ||
                                b.style == EpubReader.RichStyle.H2
                            ) {
                                FontWeight.Bold
                            } else {
                                style.fontWeight
                            },
                            fontStyle = if (b.style == EpubReader.RichStyle.CAPTION) {
                                FontStyle.Italic
                            } else {
                                style.fontStyle
                            }
                        ),
                        color = if (hl) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 0.dp)
                            .padding(bottom = paragraphMarginDp.dp)
                            .then(
                                if (hl) Modifier.background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(8.dp)
                                ).padding(6.dp) else Modifier
                            )
                            .clickable { onTap() }
                    )
                }
                EpubReader.RichBlock.Rule -> {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                is EpubReader.RichBlock.Table -> {
                    TableBlock(b)
                }
                is EpubReader.RichBlock.Footnote -> {
                    Text(
                        text = "[${b.id}] ${b.text}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 2.dp, horizontal = 10.dp)
                            .padding(bottom = paragraphMarginDp.dp)
                    )
                }
                is EpubReader.RichBlock.Dropcap -> {
                    Text(
                        text = b.text,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 2.dp, horizontal = 0.dp)
                            .padding(bottom = paragraphMarginDp.dp)
                    )
                }
                else -> {
                    // Handle TableRow, TableCell and any other block types
                    Text(
                        text = b.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    )
                }
            }
        }
        // Nút điều hướng trang
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = { if (currentPage > 0) currentPage-- },
                enabled = currentPage > 0
            ) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = "Trang trước")
            }
            Text(
                text = "${currentPage + 1} / $totalPages",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.wrapContentSize(Alignment.Center)
            )
            TextButton(
                onClick = { if (currentPage < totalPages - 1) currentPage++ },
                enabled = currentPage < totalPages - 1
            ) {
                Text(text = "Trang sau")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }

    // B6.16: dialog zoom ảnh (pinch), decode riêng cho dialog.
    val zp = zoomPath
    if (zp != null) {
        LaunchedEffect(zp) {
            zoomDecoded = withContext(Dispatchers.IO) { decodeEpubImage(file, zp, targetW) }
        }
        val zd = zoomDecoded
        if (zd != null && !zd.isRecycled) {
            ZoomableImageDialog(
                bitmap = zd,
                contentDescription = zoomAlt,
                onDismiss = {
                    zoomPath = null
                    try { zoomDecoded?.recycle() } catch (_: Exception) {}
                    zoomDecoded = null
                }
            )
        }
    }
}

/** Decode ảnh EPUB sampled theo bề rộng mục tiêu (IO). Null khi lỗi/svg. */
private suspend fun decodeEpubImage(file: File, path: String, targetW: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val bytes = EpubReader.readImageBytes(file, path) ?: return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / sample > targetW) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }         catch (_: Exception) {
            null
        }
    }

    /** Hiển thị bảng (E2.5 Week 2). */
    @Composable
    fun TableBlock(table: EpubReader.RichBlock.Table) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Caption
            if (table.caption?.isNotBlank() == true) {
                Text(
                    text = table.caption ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }
            // Table content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                    .padding(8.dp)
            ) {
                table.rows.forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.cells.forEach { cell ->
                            Text(
                                text = cell.text,
                                style = if (cell.isHeader) {
                                    MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                                } else MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                maxLines = 2
                            )
                        }
                    }
                    // Divider between rows (except last)
                    if (row != table.rows.last()) {
                        Divider(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
