package com.bibot.app.ui.menu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
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
import com.bibot.app.ui.components.TrongDongWatermark
import com.bibot.app.ui.components.ads.AdsBanner
import com.bibot.app.ui.components.ads.AdsInterstitialHelper
import com.bibot.app.ui.components.ads.AdsRewardedHelper
import com.bibot.app.feature.reader.ReaderCollection
import com.bibot.app.feature.reader.ReaderScreen
import com.bibot.app.feature.reader.ReaderBackgroundLayer
import com.bibot.app.feature.reader.BookDetailScreen
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
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.data.store.BibotPrefs

enum class BibotMode(val emoji: String, val titleRes: Int) {
    VOICE("🎙️", R.string.nav_voice),
    BOOK("📚", R.string.nav_book),
    STORY("📖", R.string.nav_story),
    COTICH("🧚", R.string.nav_cotich),
    ABOUT("ℹ️", R.string.nav_about),
    MUSIC("🎵", R.string.nav_music),
    WEATHER("⛅", R.string.nav_weather),
    NEWS("📰", R.string.nav_news),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BibotMainScreen(playerViewModel: PlayerViewModel = viewModel()) {
    // null = Home dashboard tinh gọn (mặc định), không phải VOICE
    var currentMode by remember { mutableStateOf<BibotMode?>(null) }
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    // A3.2: MỌI lần đổi màn đi qua đúng 1 lệnh dừng duy nhất — ExoPlayer + engine TTS +
    // job đọc nền (News auto-read / Voice pipeline) dừng trước khi màn mới hiện.
    fun switchMode(mode: BibotMode?) { playerViewModel.stopAll(); currentMode = mode }
    fun goHome() { switchMode(null) }
    // Back ở Home = thoát app (như nút Back hệ thống).
    fun pressBack() { try { activity?.onBackPressedDispatcher?.onBackPressed() } catch (_: Exception) {} }

    // Ads preferences for banner/interstitial/rewarded
    val adsPrefs = remember { AdsPreferences(context) }
    // B6.19: cài đặt nền màn đọc (drum/solid) — đọc chung DataStore với phần còn lại.
    val bibotPrefs = remember { BibotPreferences(context) }
    val bibotPrefsState by bibotPrefs.prefsFlow.collectAsState(initial = BibotPrefs())
    // B6.1: mode đọc (sách/truyện/PDF) ẩn chrome app (topBar) →
    // full-screen kiểu ReadEra; back bằng nút của reader/library. Banner VẪN hiện
    // ở đáy màn đọc (không ẩn banner); interstitial giữa chừng đã xoá (B6.14).
    val inReader = currentMode == BibotMode.BOOK || currentMode == BibotMode.STORY ||
        currentMode == BibotMode.COTICH

    // Nền trống đồng phủ FULL màn hình tới sát 4 góc — MỌI màn hình, phone & tablet.
    // Đặt NGOÀI Scaffold nên đi xuyên cả top bar (thanh trong suốt).
    Box(Modifier.fillMaxSize()) {
        TrongDongWatermark()
        // B6.19: nền đơn sắc ở màn đọc — dưới Scaffold trong suốt nên phủ luôn
        // statusBar/navBar; các màn khác vẫn giữ nền trống đồng.
        if (inReader) ReaderBackgroundLayer(bibotPrefsState.readerBackground)
        // containerColor transparent — M3 Scaffold mặc định vẽ Surface(background)
        // đè mất watermark (nền trống đồng phải thấy xuyên qua Scaffold).
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                // Top_bar thống nhất (Home + mọi mode chức năng, trừ mode đọc):
                // trái = Back (mode → về Home; Home → thoát app),
                // phải = icon Chat (Mic) + Settings.
                // Không còn nút Home / bottom bar / NavigationRail riêng.
                if (!inReader) {
                // Top bar gọn 52dp + title titleMedium (gốc M3: 64dp + titleLarge 22sp)
                // — giảm khoảng cách các dòng đề mục, nhường chỗ cho nội dung chính.
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                        .height(52.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentMode == null) {
                        IconButton(onClick = { pressBack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.reader_back))
                        }
                    } else {
                        IconButton(onClick = { switchMode(null) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.nav_back_home))
                        }
                    }
                    Text(
                        if (currentMode == null) stringResource(R.string.app_name)
                        else "${currentMode!!.emoji} ${currentMode!!.label()}",
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (BuildConfig.ENABLE_VOICE) {
                        IconButton(
                            onClick = { switchMode(BibotMode.VOICE) },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.nav_voice))
                        }
                    }
                    IconButton(
                        onClick = { switchMode(BibotMode.SETTINGS) },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                }
                }
            }
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                Box(Modifier.fillMaxSize().weight(1f)) {
                    if (BuildConfig.ENABLE_VOICE && currentMode == BibotMode.VOICE) {
                        VoiceScreen(playerVm = playerViewModel)
                    } else {
                        when (currentMode) {
                            null -> HomeMenu(onModeSelected = { switchMode(it) })
                                BibotMode.BOOK -> ReaderScreen(playerVm = playerViewModel, collection = ReaderCollection.EN, onBackHome = { goHome() })
                                BibotMode.STORY -> ReaderScreen(playerVm = playerViewModel, collection = ReaderCollection.STORY_VI, onBackHome = { goHome() })
                                BibotMode.COTICH -> ReaderScreen(playerVm = playerViewModel, collection = ReaderCollection.COTICH, onBackHome = { goHome() })
                            BibotMode.ABOUT -> BookDetailScreen(onBack = { goHome() })
                            BibotMode.MUSIC -> MusicScreen(playerVm = playerViewModel)
                            BibotMode.WEATHER -> WeatherScreen(playerVm = playerViewModel)
                            BibotMode.NEWS -> NewsScreen(playerVm = playerViewModel)
                            BibotMode.SETTINGS -> SettingsScreen()
                            else -> HomeMenu(onModeSelected = { switchMode(it) })
                        }
                    }
                }
                // Banner ad (dưới content) — B6.1: HIỆN cả khi
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
