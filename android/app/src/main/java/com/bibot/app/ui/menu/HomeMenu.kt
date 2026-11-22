package com.bibot.app.ui.menu

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.bibot.app.R
import com.bibot.app.BuildConfig
import com.bibot.app.core.announce.AnnounceApi
import com.bibot.app.core.announce.AnnounceItem
import com.bibot.app.ui.theme.BibotIconSize
import com.bibot.app.data.store.BibotPreferences
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import com.bibot.app.ui.adaptive.isTablet

/**
 * Main menu tinh gọn — Home đặt trên nền trống đồng FULL màn hình (crop phủ kín).
 * - Điều hướng gom hết lên Top_bar (Back trái; Chat/Settings phải) — không còn
 *   header riêng, nút Home, bottom bar hay NavigationRail.
 * - Grid adaptive 150dp phone / 190dp tablet, ô cách nhau 14dp; tile nền trong suốt
 *   45% + viền mảnh → thấy rõ hoa văn trống đồng; icon & chữ căn giữa tuyệt đối.
 */
data class HomeItem(val mode: BibotMode, val accent: androidx.compose.ui.graphics.Color)

@Composable
fun HomeMenu(
    onModeSelected: (BibotMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    // Tên nút NGẮN GỌN 1 dòng (subtitle đã bỏ cho gọn) — BOOK: "EN Books"
    // VOICE không nằm trong grid — mở từ icon Mic góc phải Top_bar.
    val items = listOf(
        HomeItem(BibotMode.BOOK, MaterialTheme.colorScheme.primaryContainer),
        HomeItem(BibotMode.STORY, MaterialTheme.colorScheme.secondaryContainer),
        HomeItem(BibotMode.COTICH, MaterialTheme.colorScheme.primaryContainer),
        HomeItem(BibotMode.MUSIC, MaterialTheme.colorScheme.tertiaryContainer),
        HomeItem(BibotMode.WEATHER, MaterialTheme.colorScheme.primaryContainer),
        HomeItem(BibotMode.NEWS, MaterialTheme.colorScheme.secondaryContainer),
    )
    val allItems = items

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (isTablet()) 190.dp else 150.dp),
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp)
    ) {
        // Header Home (tên app + Mic + Settings) đã dời lên Top_bar —
        // grid chỉ còn banner NPT + các ô chức năng.

        // Banner thong bao NPT (pull JSON, co badge tin chua xem) — tu an khi khong co tin
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            AnnounceBanner()
        }

        items(allItems) { item ->
            HomeTile(item = item, onClick = { onModeSelected(item.mode) })
        }

        // Nút Cài đặt đã được gỡ bỏ vì đã có trên main screen

    }
}

@Composable
private fun HomeTile(item: HomeItem, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(20.dp)
    Card(
        onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
        shape = shape,
        // Nền CHỈM 45% → nhìn rõ hoa văn trống đồng phía sau, vẫn đọc được chữ
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.height(120.dp).semantics { role = Role.Button }
    ) {
        // Căn giữa tuyệt đối (ngang + dọc) — icon & tên cân đối trên mọi ô
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(BibotIconSize.Badge).clip(CircleShape).background(item.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                                Icon(
                    painter = painterResource(item.mode.icon),
                    contentDescription = null,
                    modifier = Modifier.size(BibotIconSize.Tile),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                item.mode.label(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun announceIcon(type: String): ImageVector = when (type) {
    "upgrade" -> Icons.Filled.SystemUpdate
    "bookstore" -> Icons.Filled.Storefront
    "promo" -> Icons.Filled.CardGiftcard
    else -> Icons.Filled.Campaign
}

private fun openLink(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {}
}

/**
 * Banner thong bao NPT tren Home: tin moi nhat chua xem (loc theo ban app).
 * Cham banner: mo hop thoai doc day du (nut "Da hieu" danh dau da xem, tin ke tu hien;
 * co url thi them nut "Mo lien ket"). X: danh dau da xem, khong mo hop thoai.
 * Khong co URL cau hinh hoac khong co tin: tu render rong.
 */
@Composable
private fun AnnounceBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { BibotPreferences(context) }
    var items by remember { mutableStateOf<List<AnnounceItem>>(emptyList()) }
    var open by remember { mutableStateOf<AnnounceItem?>(null) }
    val seen by prefs.seenAnnounceIdsFlow.collectAsState(initial = emptySet())
    LaunchedEffect(Unit) {
        val url = try { BuildConfig.ANNOUNCE_URL } catch (_: Exception) { "" }
        val fallback = try { BuildConfig.ANNOUNCE_URL_FALLBACK } catch (_: Exception) { "" }
        val cur = try { BuildConfig.VERSION_NAME } catch (_: Exception) { "" }
        try {
            items = AnnounceApi.fetch(context, listOf(url, fallback))
                .filter { AnnounceApi.isApplicable(it, cur) }
        } catch (_: Exception) {}
    }
    val unseen = items.filter { it.id !in seen }
    val top = unseen.firstOrNull() ?: return

    // Bấm banner: mở hộp đọc đầy đủ nội dung tin (banner chỉ hiện 2 dòng) — đánh dấu
    // đã xem khi đóng, tin kế tiếp tự hiện. Trước đây bấm là mất nội dung ngay.
    open?.let { a ->
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = {
                TextButton(onClick = {
                    open = null
                    scope.launch { try { prefs.markAnnounceSeen(a.id) } catch (_: Exception) {} }
                }) { Text(stringResource(R.string.announce_got_it)) }
            },
            dismissButton = if (a.url.isNotBlank()) ({
                TextButton(onClick = { openLink(context, a.url) }) {
                    Text(stringResource(R.string.announce_open_link))
                }
            }) else null,
            icon = {
                Icon(
                    imageVector = announceIcon(a.type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            },
            title = { Text(a.title) },
            text = {
                Column {
                    if (a.body.isNotBlank()) Text(a.body)
                    if (a.publishedAt.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            a.publishedAt,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { open = top }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = announceIcon(top.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        top.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (unseen.size > 1) {
                        Spacer(Modifier.width(6.dp))
                        Badge { Text("+${unseen.size - 1}", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Text(
                    top.body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            IconButton(onClick = {
                scope.launch { try { prefs.markAnnounceSeen(top.id) } catch (_: Exception) {} }
            }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.announce_dismiss))
            }
        }
    }
}
