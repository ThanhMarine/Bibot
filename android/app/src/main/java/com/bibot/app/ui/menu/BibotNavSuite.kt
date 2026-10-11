package com.bibot.app.ui.menu

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
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
    MUSIC("🎵", R.string.nav_music),
    WEATHER("⛅", R.string.nav_weather),
    NEWS("📰", R.string.nav_news),
    PDF("📄", R.string.nav_pdf),
    SETTINGS("⚙️", R.string.nav_settings)
}

/** Ten mode theo ngon ngu UI (P2 i18n) — noi dung sach/truyen giu nguyen. */
@Composable
fun BibotMode.label(): String = androidx.compose.ui.res.stringResource(titleRes)

/** Nội dung "Lời tác giả" — hiển thị khi bấm nút Info trên Home. */
private val AUTHOR_NOTE = listOf(
    "Chào bạn,",
    "Bibot được khởi nguồn từ một mong ước gieo mầm tri thức: biến những trang sách lặng lẽ thành dòng chảy âm thanh sống động, mượt mà và đồng hành cùng bạn trên mọi hành trình.",
    "Nhờ ứng dụng công nghệ chuyển đổi văn bản thành giọng nói (TTS), Bibot mang đến một không gian đọc - nghe linh hoạt, gõ cửa từng nhu cầu học tập và thư giãn:",
    "Song hành cùng ngoại ngữ: Giúp bạn nâng tầm phản xạ nghe – đọc tiếng Anh tự nhiên qua các tài liệu tham khảo giàu giá trị.",
    "Khơi nguồn dòng chảy sử Việt: Đưa những trang sử hào hùng của dân tộc đến gần hơn với những tâm hồn yêu tri thức bằng chất giọng mộc mạc, lắng đọng.",
    "Nuôi dưỡng giấc mơ tuổi thơ: Mở ra thế giới cổ tích Việt Nam và thế giới đầy màu sắc, gắn kết tình cảm gia đình sau mỗi ngày dài làm việc.",
    "Dù bạn đang mở rộng tầm nhìn ra thế giới hay tìm về những giá trị cội nguồn, Bibot hy vọng sẽ trở thành người bạn tri kỷ, cùng bạn lưu giữ nét đẹp văn hóa và tiếp lửa cho niềm ham học hỏi mỗi ngày.",
    "Cảm ơn bạn đã tin tưởng và chọn Bibot. Sự đồng hành cùng những phản hồi từ bạn chính là nguồn động lực lớn nhất để ứng dụng ngày một hoàn thiện hơn!"
)

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
    var showAuthorInfo by remember { mutableStateOf(false) }
    // 11.3: tablet landscape → NavigationRail bên trái; phone giữ NavigationBar dưới
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val windowSize = activity?.let { calculateWindowSizeClass(activity = it) }
    val tablet = isTablet(windowSize)
    fun goHome() { playerViewModel.stopPlayback(); currentMode = null }

    // Ads preferences for banner/interstitial/rewarded
    val adsPrefs = remember { AdsPreferences(context) }

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
                // Top bar gọn 52dp + title titleMedium (gốc M3: 64dp + titleLarge 22sp)
                // — giảm khoảng cách các dòng đề mục, nhường chỗ cho nội dung chính.
                Row(
                    Modifier.fillMaxWidth()
                        .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                        .height(52.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentMode != null) {
                        IconButton(onClick = { playerViewModel.stopPlayback(); currentMode = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.nav_back_home))
                        }
                    } else {
                        Spacer(Modifier.width(48.dp))
                    }
                    Text(
                        if (currentMode != null) "${currentMode!!.emoji} ${currentMode!!.label()}" else "",
                        Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    // Nút Info — chỉ trên Home, mở dialog "Lời tác giả"
                    if (currentMode == null) {
                        IconButton(onClick = { showAuthorInfo = true }) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.nav_author_note))
                        }
                    } else {
                        Spacer(Modifier.width(48.dp))
                    }
                }
            },
            bottomBar = {
                // 11.3: tablet dùng Rail (bên trái) nên ẩn bottom bar
                if (!tablet) {
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
                    if (tablet) {
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
                                BibotMode.MUSIC -> MusicScreen(playerVm = playerViewModel)
                                BibotMode.WEATHER -> WeatherScreen()
                                BibotMode.NEWS -> NewsScreen()
                                BibotMode.PDF -> DocBrowserScreen()
                                BibotMode.SETTINGS -> SettingsScreen()
                                else -> HomeMenu(onModeSelected = { currentMode = it })
                            }
                        }
                    }
                }
                // Banner ad (hiển thị ở dưới cùng content area, trên navigation bar)
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

    // Dialog "Lời tác giả" — nội dung thư giữ nguyên tiếng Việt (content gốc P3 mới dịch)
    if (showAuthorInfo) {
        AlertDialog(
            onDismissRequest = { showAuthorInfo = false },
            title = { Text(stringResource(R.string.nav_author_note), fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AUTHOR_NOTE.forEach { p ->
                        Text(p, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
                    }
                    Text(
                        stringResource(R.string.nav_author_sign),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAuthorInfo = false }) { Text(stringResource(R.string.dialog_close)) }
            }
        )
    }
}
