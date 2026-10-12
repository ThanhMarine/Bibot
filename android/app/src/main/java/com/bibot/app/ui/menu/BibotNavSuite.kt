package com.bibot.app.ui.menu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.bibot.app.R
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bibot.app.ui.adaptive.isTablet
import com.bibot.app.ui.components.TrongDongWatermark
import com.bibot.app.ui.components.ads.AdsBanner
import com.bibot.app.ui.components.ads.AdsInterstitialHelper
import com.bibot.app.ui.components.ads.AdsRewardedHelper
import com.bibot.app.feature.doc.DocBrowserScreen
import com.bibot.app.feature.reader.ReaderCollection
import com.bibot.app.feature.reader.ReaderScreen
import com.bibot.app.feature.reader.BookDetailScreen
import com.bibot.app.feature.reader.ViewScreen
import com.bibot.app.feature.music.MusicScreen
import com.bibot.app.feature.news.NewsScreen
import com.bibot.app.feature.settings.SettingsScreen
import com.bibot.app.BuildConfig
import com.bibot.app.feature.weather.WeatherScreen
import com.bibot.app.feature.voice.VoiceScreen
import com.bibot.app.ui.player.PlayerViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibot.app.data.ads.AdsPreferences
import com.bibot.app.data.ads.AdManager

enum class BibotMode(val emoji: String, val titleRes: Int) {
    VOICE("🎙️", R.string.nav_voice),
    BOOK("📚", R.string.nav_book),
    STORY("📖", R.string.nav_story),
    COTICH("🧚", R.string.nav_cotich),
    ABOUT("ℹ️", R.string.nav_about),
    MUSIC("🎵", R.string.nav_music),
    WEATHER("⛅", R.string.nav_weather),
    NEWS("📰", R.string.nav_news),
    VIEW("👁️", R.string.nav_view),
    PDF("📄", R.string.nav_pdf),
    SETTINGS("⚙️", R.string.nav_settings)
}

/** Ten mode theo ngon ngu UI (P2 i18n) — noi dung sach/truyen giu nguyen. */
@Composable
fun BibotMode.label(): String = androidx.compose.ui.res.stringResource(titleRes)

// B6: "Lời tác giả" chuyển vào Settings > About (xem AuthorNoteContent) —
// không còn dialog Info ở đây.

@Composable
private fun AdsHelpers(
    activity: ComponentActivity,
    adsPrefs: AdsPreferences,
    currentModeName: String,
    isVoiceActive: Boolean
) {
    AdsInterstitialHelper(
        activity = activity,
        adsPreferences = adsPrefs,
        currentMode = currentModeName,
        isVoiceActive = isVoiceActive
    )
    AdsRewardedHelper(
        activity = activity,
        adsPreferences = adsPrefs
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun BibotMainScreen(playerViewModel: PlayerViewModel = viewModel()) {
    // null = Home dashboard tinh gọn (mặc định), không phải VOICE
    var currentMode by remember { mutableStateOf<BibotMode?>(null) }
    // 11.3: tablet landscape → NavigationRail bên trái; phone giữ NavigationBar dưới
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val windowSize = activity?.let { calculateWindowSizeClass(activity = it) }
    val tablet = isTablet(windowSize)
    fun goHome() { playerViewModel.stopPlayback(); currentMode = null }

    // Ads preferences for banner/interstitial/rewarded
    val adsPrefs = remember { AdsPreferences(context) }
    // B6.1: mode đọc (sách/truyện/PDF) ẩn chrome app (topBar/bottomBar/Rail) →
    // full-screen kiểu ReadEra; back bằng nút của reader/library. Banner VẪN hiện
    // ở đáy màn đọc (không ẩn banner); interstitial giữa chừng đã xoá (B6.14).
    val inReader = currentMode == BibotMode.BOOK || currentMode == BibotMode.STORY ||
        currentMode == BibotMode.COTICH || currentMode == BibotMode.PDF

    // Nền trống đồng phủ FULL màn hình tới sát 4 góc — MỌI màn hình, phone & tablet.
    // Đặt NGOÀI Scaffold nên đi xuyên cả top bar / thanh điều hướng (2 thanh trong suốt).
    val barColor = Color.Transparent
    Box(Modifier.fillMaxSize()) {
        TrongDongWatermark()
        // containerColor transparent — M3 Scaffold mặc định vẽ Surface(background)
        // đè mất watermark (nền trống đồng phải thấy xuyên qua Scaffold).
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                // B6.1 + Home gọn: chỉ hiện topBar app ở mode chức năng
                // (Music/Weather/News/Settings/Voice) — Home có header riêng,
                // mode đọc có top-bar riêng.
                if (!inReader && currentMode != null) {
                // Top bar gọn 52dp + title titleMedium (gốc M3: 64dp + titleLarge 22sp)
                // — giảm khoảng cách các dòng đề mục, nhường chỗ cho nội dung chính.
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                        .height(52.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { playerViewModel.stopPlayback(); currentMode = null }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.nav_back_home))
                    }
                    Text(
                        "${currentMode!!.emoji} ${currentMode!!.label()}",
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(48.dp))
                }
                }
            },
            bottomBar = {
                // 11.3: tablet dùng Rail (bên trái) nên ẩn bottom bar
                // B6.1 + Home gọn: ẩn trong mode đọc và ở Home (Mic/Settings lên header).
                if (!tablet && !inReader && currentMode != null) {
                    NavigationBar(containerColor = barColor, tonalElevation = 0.dp) {
                        NavigationBarItem(
                            selected = currentMode == null,
                            onClick = { goHome() },
                            icon = { Text("🏠", fontSize = 20.sp) },
                            label = { Text(stringResource(R.string.nav_home), fontSize = 10.sp) }
                        )
                        if (BuildConfig.ENABLE_VOICE) {
                            NavigationBarItem(
                                selected = currentMode == BibotMode.VOICE,
                                onClick = { currentMode = BibotMode.VOICE },
                                icon = { Text("🎙️", fontSize = 20.sp) },
                                label = { Text(stringResource(R.string.nav_voice), fontSize = 10.sp) }
                            )
                        }
                        NavigationBarItem(
                            selected = currentMode == BibotMode.SETTINGS,
                            onClick = { currentMode = BibotMode.SETTINGS },
                            icon = { Text("⚙️", fontSize = 20.sp) },
                            label = { Text(stringResource(R.string.nav_settings), fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                Row(Modifier.fillMaxSize().weight(1f)) {
                    // 11.3: NavigationRail landscape cho tablet (WindowSizeClass Adaptive)
                    // B6.1 + Home gọn: ẩn trong mode đọc và ở Home.
                    if (tablet && !inReader && currentMode != null) {
                        NavigationRail(containerColor = barColor) {
                            NavigationRailItem(
                                selected = currentMode == null,
                                onClick = { goHome() },
                                icon = { Text("🏠", fontSize = 22.sp) },
                                label = { Text(stringResource(R.string.nav_home), fontSize = 11.sp) }
                            )
                            if (BuildConfig.ENABLE_VOICE) {
                                NavigationRailItem(
                                    selected = currentMode == BibotMode.VOICE,
                                    onClick = { currentMode = BibotMode.VOICE },
                                    icon = { Text("🎙️", fontSize = 22.sp) },
                                    label = { Text(stringResource(R.string.nav_voice), fontSize = 11.sp) }
                                )
                            }
                            NavigationRailItem(
                                selected = currentMode == BibotMode.SETTINGS,
                                onClick = { currentMode = BibotMode.SETTINGS },
                                icon = { Text("⚙️", fontSize = 22.sp) },
                                label = { Text(stringResource(R.string.nav_settings), fontSize = 11.sp) }
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize().weight(1f)) {
                        if (BuildConfig.ENABLE_VOICE && currentMode == BibotMode.VOICE) {
                            VoiceScreen(playerVm = playerViewModel)
                        } else {
                            when (currentMode) {
                                null -> HomeMenu(onModeSelected = { currentMode = it })
                                BibotMode.BOOK -> ReaderScreen(playerVm = playerViewModel, collection = ReaderCollection.EN)
                                BibotMode.STORY -> ReaderScreen(playerVm = playerViewModel, collection = ReaderCollection.STORY_VI)
                                BibotMode.COTICH -> ReaderScreen(playerVm = playerViewModel, collection = ReaderCollection.COTICH)
                                BibotMode.ABOUT -> BookDetailScreen(onBack = { goHome() })
                                BibotMode.MUSIC -> MusicScreen(playerVm = playerViewModel)
                                BibotMode.WEATHER -> WeatherScreen()
                                BibotMode.NEWS -> NewsScreen()
                                BibotMode.VIEW -> ViewScreen(onBack = { goHome() })
                                BibotMode.PDF -> DocBrowserScreen(onBackHome = { goHome() })
                                BibotMode.SETTINGS -> SettingsScreen()
                                else -> HomeMenu(onModeSelected = { currentMode = it })
                            }
                        }
                    }
                }
                // Banner ad (dưới content, trên navigation bar) — B6.1: HIỆN cả khi
                // đọc (không ẩn banner ở màn đọc); interstitial giữa chừng đã xoá,
                // chỉ giữ banner + rewarded Cloud TTS + VIP bypass.
                AdsBanner(adsPreferences = adsPrefs)
                // Interstitial & Rewarded helpers: hiển thị QC khi chuyển mode + preload rewarded
                val currentModeName = currentMode?.name ?: "HOME"
                val isVoice = currentMode == BibotMode.VOICE
                if (activity != null) {
                    AdsHelpers(activity!!, adsPrefs, currentModeName, isVoice)
                }
            }
        }
    }
}
