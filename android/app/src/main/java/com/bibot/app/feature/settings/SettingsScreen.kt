package com.bibot.app.feature.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bibot.app.BuildConfig
import com.bibot.app.feature.weather.WeatherApi
import com.bibot.app.feature.weather.City
import com.bibot.app.R
import com.bibot.app.core.locale.AppLocale
import com.bibot.app.core.tts.PiperTtsHelper
import com.bibot.app.core.tts.SherpaEngine
import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.books.BookRepository
import com.bibot.app.data.books.DriveBookSource
import com.bibot.app.data.files.FilePaths
import com.bibot.app.data.offline.DownloadState
import com.bibot.app.data.offline.ModelInfo
import com.bibot.app.data.offline.ModelType
import com.bibot.app.data.offline.OfflineSttHelper
import com.bibot.app.data.store.BibotPreferences
import com.bibot.app.data.store.ALLOWED_FONT_SIZES
import com.bibot.app.data.store.ALLOWED_INTERLINES
import com.bibot.app.data.store.ALLOWED_PARAGRAPH_MARGINS
import com.bibot.app.data.store.BibotPrefs
import com.bibot.app.data.store.BotPromptManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Chỉ 2 lựa chọn dễ hiểu cho người dùng phổ thông (Chat + tóm tắt thông tin).
private val GEMINI_MODELS = listOf(
    "gemini-3.5-flash-lite" to R.string.api_model_lite,
    "gemini-3.5-flash" to R.string.api_model_flash
)

private val TTS_PRIORITY_VALUES = listOf("auto", "offline", "online")

@Composable
private fun ttsLabel(v: String): String = when (v) {
    "offline" -> stringResource(R.string.set_tts_offline)
    "online" -> stringResource(R.string.set_tts_online)
    else -> stringResource(R.string.set_tts_auto)
}

/**
 * Tên file hiển thị của Uri (để user biết đã chọn đúng file chưa).
 * SAF không cho đường dẫn thật nên chỉ query được DISPLAY_NAME.
 */
@Composable
private fun rememberUriName(uri: Uri?): String? {
    val context = LocalContext.current
    if (uri == null) return null
    return remember(uri) {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
            } ?: uri.lastPathSegment
        } catch (_: Exception) { uri.lastPathSegment }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modelsVm: ModelsViewModel = viewModel()
) {
    val context = LocalContext.current
    val prefs = remember { BibotPreferences(context) }
    val scope = rememberCoroutineScope()
    val prefsState by prefs.prefsFlow.collectAsState(initial = BibotPrefs())
    val uiState by modelsVm.uiState.collectAsState()

    val installedCount = uiState.installed.count { it.value }
    var booksCount by remember { mutableIntStateOf(-1) }

    // Cài đặt > Thời tiết: nguồn chọn vị trí DUY NHẤT (tab Weather chỉ hiển thị).
    var weatherQuery by remember { mutableStateOf("") }
    var weatherResults by remember { mutableStateOf<List<City>>(emptyList()) }
    var weatherCoordQuery by remember { mutableStateOf("") }
    var weatherSearching by remember { mutableStateOf(false) }
    var weatherSearchMsg by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                stringResource(R.string.set_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // ===== Ngôn ngữ / Language (P1 i18n — dogfooding stringResource) =====
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.fluent_globe),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        val activity = context as? android.app.Activity
                        val opts = listOf(
                            AppLocale.SYSTEM to stringResource(R.string.language_system),
                            AppLocale.VI to stringResource(R.string.language_vi),
                            AppLocale.EN to stringResource(R.string.language_en)
                        )
                        opts.forEach { (v, label) ->
                            FilterChip(
                                selected = prefsState.language == v,
                                onClick = {
                                    scope.launch {
                                        try { prefs.setLanguage(v) } catch (_: Exception) {}
                                        try { AppLocale.applyAndRecreate(activity, v) } catch (_: Exception) {}
                                    }
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        }

        // ===== Thời tiết — chọn địa phương cho Bản tin thời tiết =====
        // ===== Thời tiết — nơi DUY NHẤT chọn vị trí cho bản tin thời tiết =====
        item {
            val selectedCity = WeatherApi.cityFromPref(prefsState.weatherCity)
            ExpandableSection(
                title = stringResource(R.string.sec_weather),
                subtitle = selectedCity.label,
                iconRes = R.drawable.fluent_globe,
                defaultExpanded = false
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.weather_city_pick),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // Gợi ý nhanh — không phải giới hạn; bên dưới tìm được mọi nơi trên thế giới.
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WeatherApi.CITIES.forEach { c ->
                            FilterChip(
                                selected = c.id == selectedCity.id,
                                onClick = {
                                    scope.launch {
                                        try { prefs.setWeatherCity(WeatherApi.encodeCity(c)) } catch (_: Exception) {}
                                    }
                                },
                                label = { Text(c.chip, fontSize = 12.sp) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = weatherQuery,
                        onValueChange = { weatherQuery = it },
                        label = { Text(stringResource(R.string.weather_search_label)) },
                        placeholder = { Text(stringResource(R.string.weather_search_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                if (weatherQuery.isBlank()) return@IconButton
                                weatherSearching = true
                                weatherSearchMsg = ""
                                scope.launch {
                                    val list = try {
                                        WeatherApi.searchLocations(weatherQuery).getOrElse { emptyList() }
                                    } catch (_: Exception) { emptyList() }
                                    weatherResults = list
                                    if (list.isEmpty()) {
                                        weatherSearchMsg = context.getString(R.string.weather_search_empty)
                                    }
                                    weatherSearching = false
                                }
                            }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.weather_search_cd))
                            }
                        }
                    )
                    if (weatherSearching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (weatherSearchMsg.isNotBlank()) {
                        Text(
                            weatherSearchMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    weatherResults.forEach { c ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    try { prefs.setWeatherCity(WeatherApi.encodeCity(c)) } catch (_: Exception) {}
                                }
                                weatherResults = emptyList()
                                weatherQuery = c.label
                                weatherSearchMsg = ""
                            },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(c.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${c.lat}, ${c.lon}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.weather_coord_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    OutlinedTextField(
                        value = weatherCoordQuery,
                        onValueChange = { weatherCoordQuery = it },
                        label = { Text(stringResource(R.string.weather_coord_label)) },
                        placeholder = { Text("10.8231, 106.6297") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = {
                        val parts = weatherCoordQuery.split(",").map { it.trim() }
                        val lat = parts.getOrNull(0)?.toDoubleOrNull()
                        val lon = parts.getOrNull(1)?.toDoubleOrNull()
                        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            weatherSearchMsg = context.getString(R.string.weather_coord_invalid)
                        } else {
                            val c = WeatherApi.customCity("$lat, $lon", "$lat, $lon", lat, lon)
                            scope.launch {
                                try { prefs.setWeatherCity(WeatherApi.encodeCity(c)) } catch (_: Exception) {}
                            }
                            weatherSearchMsg = ""
                        }
                    }) {
                        Text(stringResource(R.string.weather_coord_use))
                    }
                    Text(
                        stringResource(R.string.weather_city_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        // ===== AI Trò chuyện (chỉ flavor có chat AI) =====
        if (BuildConfig.ENABLE_VOICE) {
            item {
                ExpandableSection(
                    title = stringResource(R.string.sec_ai),
                    subtitle = stringResource(R.string.ai_sub, prefsState.persona, BotPromptManager.styleLabel(prefsState.botStyle), prefsState.botMaxWords),
                    iconRes = R.drawable.fluent_robot,
                    defaultExpanded = false
                ) {
                    AiPromptContent(prefsState = prefsState, prefs = prefs)
                }
            }
        }

        // ===== AI Gemini (duy nhất) =====
        item {
            val hasKey = prefsState.geminiApiKey.isNotBlank()
            ExpandableSection(
                title = stringResource(R.string.sec_api),
                subtitle = if (hasKey) stringResource(R.string.api_sub_key, prefsState.geminiModel)
                else stringResource(R.string.api_sub_free, prefsState.geminiModel),
                iconRes = R.drawable.fluent_key,
                defaultExpanded = false
            ) {
                ApiKeysContent(prefsState = prefsState, prefs = prefs)
            }
        }

        // ===== Giọng đọc =====
        item {
            ExpandableSection(
                title = stringResource(R.string.sec_voice),
                subtitle = ttsLabel(prefsState.ttsPriority) + " • " + "%.1f".format(prefsState.voiceSpeed) + "x",
                iconRes = R.drawable.fluent_speaker,
                defaultExpanded = true
            ) {
                VoiceContent(prefsState = prefsState, prefs = prefs)
            }
        }

        // ===== Quảng cáo & Premium (chỉ bản demo chính thức) =====
        if (BuildConfig.ENABLE_ADS) {
            item {
                ExpandableSection(
                    title = stringResource(R.string.sec_ads),
                    subtitle = stringResource(R.string.ads_sub),
                    iconRes = R.drawable.fluent_ticket,
                    defaultExpanded = false
                ) {
                    AdsRewardContent()
                }
            }
        }

        // ===== Mã VIP (chỉ bản demo chính thức) =====
        if (BuildConfig.ENABLE_ADS) {
            item {
                ExpandableSection(
                    title = stringResource(R.string.sec_vip),
                    subtitle = stringResource(R.string.vip_sub),
                    iconRes = R.drawable.fluent_crown,
                    defaultExpanded = false
                ) {
                    VipContent()
                }
            }
        }

        // ===== Mô hình offline =====
        item {
            ExpandableSection(
                title = stringResource(R.string.sec_models),
                subtitle = stringResource(R.string.models_sub, installedCount, uiState.models.size, uiState.freeSpaceMb),
                iconRes = R.drawable.fluent_package,
                defaultExpanded = false,
                onRefresh = { modelsVm.refresh() }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.voice_pick_from_file),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    uiState.models.forEach { model ->
                        ModelCard(
                            model = model,
                            installed = uiState.installed[model.id] ?: false,
                            installedBytes = uiState.sizes[model.id] ?: 0L,
                            downloadState = uiState.downloadStates[model.id] ?: DownloadState.Idle,
                            onDownload = { modelsVm.download(model) },
                            onInstallFromFile = { uriString -> modelsVm.installFromFile(model, Uri.parse(uriString)) },
                            onDelete = { modelsVm.delete(model) }
                        )
                    }
                }
            }
        }

        // ===== Kho sách =====
        item {
            ExpandableSection(
                title = stringResource(R.string.sec_books),
                subtitle = if (booksCount >= 0) stringResource(R.string.books_sub_count, booksCount) else stringResource(R.string.books_sub_default),
                iconRes = R.drawable.fluent_books,
                defaultExpanded = false
            ) {
                BookLibrarySection(onCount = { booksCount = it })
            }
        }

        // ===== Thông báo (cả 2 bản) =====
        item {
            ExpandableSection(
                title = stringResource(R.string.sec_announce),
                subtitle = stringResource(R.string.announce_sub),
                iconRes = R.drawable.fluent_bell,
                defaultExpanded = false
            ) {
                AnnounceHistoryContent()
            }
        }

        // ===== Dữ liệu =====
        item {
            ExpandableSection(
                title = stringResource(R.string.sec_data),
                subtitle = stringResource(R.string.data_reset_sub),
                iconRes = R.drawable.fluent_broom,
                defaultExpanded = false
            ) {
                DataResetSection()
            }
        }

        // ===== Hướng dẫn & Trợ giúp =====
        item {
            ExpandableSection(
                title = stringResource(R.string.help_section_title),
                subtitle = stringResource(R.string.help_section_sub),
                iconRes = R.drawable.fluent_question,
                defaultExpanded = false
            ) {
                HelpGuideContent()
            }
        }

        // ===== Lời tác giả (B6: chuyển từ nút Info ở Home) =====
        item {
            ExpandableSection(
                title = stringResource(R.string.nav_author_note),
                subtitle = stringResource(R.string.nav_author_sign),
                iconRes = R.drawable.fluent_love_letter,
                defaultExpanded = false
            ) {
                AuthorNoteContent()
            }
        }

        // ===== Giấy phép & ghi công (Fluent Emoji MIT, Material Icons Apache 2.0) =====
        item {
            ExpandableSection(
                title = stringResource(R.string.sec_licenses),
                subtitle = stringResource(R.string.licenses_sub),
                iconRes = R.drawable.fluent_information,
                defaultExpanded = false
            ) {
                CreditsContent()
            }
        }

        item {
            val verName = try { BuildConfig.VERSION_NAME } catch (_: Exception) { "?" }
            val verCode = try { BuildConfig.VERSION_CODE } catch (_: Exception) { 0 }
            // Dòng nhận diện phiên bản: dùng thẻ nổi bật thay vì chữ nhỏ/mờ.
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = stringResource(R.string.app_version_format, verName, verCode),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 16.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ===== Khung nhóm thu gọn/mở =====

@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String = "",
    @DrawableRes iconRes: Int? = null,
    defaultExpanded: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconRes != null) {
                    // Fluent Emoji 3D (MIT) — ảnh màu, không tint như Icon().
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (onRefresh != null && expanded) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh))
                    }
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider()
                    content()
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ===== 1. AI Prompt =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiPromptContent(prefsState: BibotPrefs, prefs: BibotPreferences) {
    val scope = rememberCoroutineScope()
    var persona by remember(prefsState.persona) { mutableStateOf(prefsState.persona) }
    var botPrompt by remember(prefsState.botPrompt) { mutableStateOf(prefsState.botPrompt) }
    var botStyle by remember(prefsState.botStyle) { mutableStateOf(prefsState.botStyle) }
    var botTone by remember(prefsState.botTone) { mutableStateOf(prefsState.botTone) }
    var botMaxWords by remember(prefsState.botMaxWords) { mutableStateOf(prefsState.botMaxWords) }
    var msg by remember { mutableStateOf<String?>(null) }
    var styleExpanded by remember { mutableStateOf(false) }
    var toneExpanded by remember { mutableStateOf(false) }
    // stringResource khong goi duoc trong scope.launch/try -> lay truoc o day
    val promptSavedMsg = stringResource(R.string.set_prompt_saved)
    val restoredMsg = stringResource(R.string.set_restored)

    LaunchedEffect(msg) { if (msg != null) { delay(3000); msg = null } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = persona, onValueChange = { persona = it },
            label = { Text(stringResource(R.string.set_persona)) }, placeholder = { Text("Em là Bi") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = botPrompt, onValueChange = { botPrompt = it },
            label = { Text(stringResource(R.string.set_template)) }, placeholder = { Text(stringResource(R.string.set_template_hint)) },
            modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ExposedDropdownMenuBox(expanded = styleExpanded, onExpandedChange = { styleExpanded = !styleExpanded }, modifier = Modifier.weight(1f)) {
                OutlinedTextField(value = BotPromptManager.styleLabel(botStyle), onValueChange = {}, readOnly = true, label = { Text(stringResource(R.string.set_style)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(styleExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = styleExpanded, onDismissRequest = { styleExpanded = false }) {
                    BotPromptManager.STYLE_OPTIONS.forEach { (v, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { botStyle = v; styleExpanded = false })
                    }
                }
            }
            ExposedDropdownMenuBox(expanded = toneExpanded, onExpandedChange = { toneExpanded = !toneExpanded }, modifier = Modifier.weight(1f)) {
                OutlinedTextField(value = BotPromptManager.toneLabel(botTone), onValueChange = {}, readOnly = true, label = { Text(stringResource(R.string.set_tone)) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(toneExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = toneExpanded, onDismissRequest = { toneExpanded = false }) {
                    BotPromptManager.TONE_OPTIONS.forEach { (v, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { botTone = v; toneExpanded = false })
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            BotPromptManager.MAX_WORDS_OPTIONS.forEach { n ->
                FilterChip(selected = botMaxWords == n, onClick = { botMaxWords = n }, label = { Text("$n") })
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(10.dp)) {
                Text(stringResource(R.string.common_preview), style = MaterialTheme.typography.labelSmall)
                Text(
                    BotPromptManager.build(template = botPrompt, persona = persona, style = botStyle, tone = botTone, maxWords = botMaxWords),
                    style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                scope.launch {
                    prefs.setPersona(persona)
                    prefs.setBotPrompt(botPrompt)
                    prefs.setBotStyle(botStyle)
                    prefs.setBotTone(botTone)
                    prefs.setBotMaxWords(botMaxWords)
                    msg = promptSavedMsg
                }
            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_save)) }
            OutlinedButton(onClick = {
                persona = "Em là Bi"
                botPrompt = BotPromptManager.DEFAULT_TEMPLATE
                botStyle = "friendly"
                botTone = "warm"
                botMaxWords = 30
                msg = restoredMsg
            }) { Text(stringResource(R.string.common_default)) }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

// ===== 2. AI Gemini — cấu hình duy nhất cho Chat & Information =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeysContent(prefsState: BibotPrefs, prefs: BibotPreferences) {
    val scope = rememberCoroutineScope()
    var apiKey by remember(prefsState.geminiApiKey) { mutableStateOf(prefsState.geminiApiKey) }
    var msg by remember { mutableStateOf<String?>(null) }
    var modelExpanded by remember { mutableStateOf(false) }
    // stringResource không gọi được trong scope.launch -> lấy trước ở đây.
    val removedMsg = stringResource(R.string.api_removed)
    val savedMsg = stringResource(R.string.api_saved)

    LaunchedEffect(msg) { if (msg != null) { delay(3000); msg = null } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.api_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.api_gemini)) },
            placeholder = { Text(stringResource(R.string.api_hint_relay)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = !modelExpanded }) {
            OutlinedTextField(
                value = stringResource(
                    GEMINI_MODELS.firstOrNull { it.first == prefsState.geminiModel }?.second
                        ?: R.string.api_model_lite
                ),
                onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.api_model_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true
            )
            ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                GEMINI_MODELS.forEach { (id, labelRes) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(labelRes), fontSize = 13.sp) },
                        onClick = {
                            scope.launch { prefs.setGeminiModel(id) }
                            modelExpanded = false
                        }
                    )
                }
            }
        }
        Button(onClick = {
            scope.launch {
                prefs.setGeminiKey(apiKey.trim())
                msg = if (apiKey.isBlank()) removedMsg else savedMsg
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (apiKey.isBlank()) stringResource(R.string.api_use_free) else stringResource(R.string.api_save))
        }
        msg?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
}

// ===== 3. Giọng đọc (gộp trùng lặp TTS thành 1 dropdown) =====

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Một dòng trạng thái dưới công tắc "STT offline": model nào có, engine sherpa đã nạp chưa.
 * Thiếu engine (~46MB, tải 1 lần cần mạng) là nguyên nhân phổ biến nhất khiến bật offline
 * mà không nhận dạng được — trước đây app im lặng nên user tưởng model hỏng.
 */
@Composable
private fun VoiceSttStatusLine(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    // Poll 3s: user có thể vừa tải model ở mục "Mô hình offline" rồi quay lại.
    val status by produceState<String?>(initialValue = null, enabled) {
        val helper = OfflineSttHelper(context.applicationContext)
        while (true) {
            value = try { helper.statusLine() } catch (_: Exception) { null }
            delay(3000)
        }
    }
    status?.let {
        Text(
            "STT offline: $it",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceContent(prefsState: BibotPrefs, prefs: BibotPreferences) {
    val scope = rememberCoroutineScope()
    var ttsExpanded by remember { mutableStateOf(false) }
    var showPiperAdvanced by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ExposedDropdownMenuBox(expanded = ttsExpanded, onExpandedChange = { ttsExpanded = !ttsExpanded }) {
            OutlinedTextField(
                value = ttsLabel(prefsState.ttsPriority), onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.voice_priority)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(ttsExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true
            )
            ExposedDropdownMenu(expanded = ttsExpanded, onDismissRequest = { ttsExpanded = false }) {
                TTS_PRIORITY_VALUES.forEach { v ->
                    DropdownMenuItem(
                        text = { Text(ttsLabel(v), fontSize = 13.sp) },
                        onClick = { scope.launch { prefs.setTtsPriority(v) }; ttsExpanded = false }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.voice_speed, "%.1f".format(prefsState.voiceSpeed)), style = MaterialTheme.typography.labelMedium)
            Slider(
                value = prefsState.voiceSpeed,
                onValueChange = { v -> scope.launch { prefs.setVoiceSpeed(v) } },
                valueRange = 0.5f..2.0f,
                steps = 15
            )
        }

        HorizontalDivider()
        SettingSwitchRow(stringResource(R.string.voice_stt), prefsState.sttOfflineFirst) {
            scope.launch { prefs.setSttOfflineFirst(it) }
        }
        // Trạng thái STT offline (model + engine): trước đây user bật công tắc rồi nói
        // không nhận được nhưng không biết thiếu gì. Chỉ đọc file nên poll 3s là rẻ.
        VoiceSttStatusLine(enabled = prefsState.sttOfflineFirst)
        SettingSwitchRow(stringResource(R.string.voice_barge), prefsState.bargeInEnabled) {
            scope.launch { prefs.setBargeInEnabled(it) }
        }
        SettingSwitchRow(stringResource(R.string.voice_piper), prefsState.storyPreferPiper) {
            scope.launch { prefs.setStoryPreferPiper(it) }
        }

        HorizontalDivider()
        PiperCompactRow(showAdvanced = showPiperAdvanced, onToggleAdvanced = { showPiperAdvanced = !showPiperAdvanced })

        // A2.3: Reading display settings (Snap config + RenderIfDirty)
        HorizontalDivider()
        Text(stringResource(R.string.reader_display_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.reader_display_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        // Font Size
        ReaderValueSlider(
            label = stringResource(R.string.reader_font_size),
            valueText = stringResource(R.string.reader_font_size_sub, prefsState.readerFontSizeSp),
            options = ALLOWED_FONT_SIZES,
            value = prefsState.readerFontSizeSp,
            onChange = { v -> scope.launch { prefs.setReaderFontSizeSp(v) } }
        )

        Spacer(Modifier.height(12.dp))

        // Interline (line spacing)
        ReaderValueSlider(
            label = stringResource(R.string.reader_interline),
            valueText = stringResource(R.string.reader_interline_sub, prefsState.readerInterline),
            options = ALLOWED_INTERLINES,
            value = prefsState.readerInterline,
            onChange = { v -> scope.launch { prefs.setReaderInterline(v) } }
        )

        Spacer(Modifier.height(12.dp))

        // Paragraph Margin
        ReaderValueSlider(
            label = stringResource(R.string.reader_paragraph_margin),
            valueText = stringResource(R.string.reader_paragraph_margin_sub, prefsState.readerParagraphMargin),
            options = ALLOWED_PARAGRAPH_MARGINS,
            value = prefsState.readerParagraphMargin,
            onChange = { v -> scope.launch { prefs.setReaderParagraphMargin(v) } }
        )

        // B5.7: toggle chia se tom tat len wiki cong dong (mac dinh bat, tat duoc).
        HorizontalDivider()
        SettingSwitchRow(stringResource(R.string.summary_share), prefsState.summaryShareEnabled) {
            scope.launch { prefs.setSummaryShareEnabled(it) }
        }
        Text(stringResource(R.string.summary_share_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===== Helper Composables =====

/**
 * Slider cho giá trị rời rạc KHÔNG đều (vd ALLOWED_FONT_SIZES: 20 → 22 → 24 → 26…).
 * Compose `Slider` nội suy tuyến tính theo `steps`, nên với mảng không đều thì kéo tay
 * ra giá trị lạ (11.25sp, 1.33…) lệch hẳn giá trị hiển thị, và không bao giờ chạm được
 * 22/24/26/28/30. Ở đây slider chạy theo CHỈ SỐ mảng → mỗi nấc = đúng một giá trị hợp lệ.
 */
@Composable
private fun ReaderValueSlider(
    label: String,
    valueText: String,
    options: FloatArray,
    value: Float,
    onChange: (Float) -> Unit
) {
    if (options.size < 2) return
    val last = options.size - 1
    val nearest = options.indices.minByOrNull { kotlin.math.abs(options[it] - value) } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = nearest.toFloat(),
            onValueChange = { pos -> onChange(options[(pos + 0.5f).toInt().coerceIn(0, last)]) },
            valueRange = 0f..last.toFloat(),
            steps = (last - 1).coerceAtLeast(0)
        )
    }
}

@Composable
fun SectionHeader(title: String, action: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        action?.let { IconButton(onClick = it) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.common_refresh)) } }
    }
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
}

/**
 * Quản lý kho sách — bản gọn, nằm trong ExpandableSection:
 * - 1 nút đồng bộ, tìm nhanh, thêm từ link, xoá sách đã tải
 * - Google Drive riêng thu gọn trong "Tùy chọn nâng cao"
 */
@Composable
private fun BookLibrarySection(onCount: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BookRepository(context) }
    val prefs = remember { BibotPreferences(context) }
    val driveSource = remember { DriveBookSource(context) }
    val driveEnabled = BuildConfig.ENABLE_DRIVE
    val books by repo.books.collectAsState()

    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var urlInput by remember { mutableStateOf("") }
    var urlBusy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var expandedGroup by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    var folderId by remember { mutableStateOf("") }
    var signedInEmail by remember { mutableStateOf<String?>(null) }
    var wipeBusy by remember { mutableStateOf(false) }

    fun setStatus(msg: String, error: Boolean = false) {
        status = msg
        statusIsError = error
    }

    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val acct = GoogleSignIn.getLastSignedInAccount(context)
        signedInEmail = acct?.email
        if (acct != null) setStatus(context.getString(R.string.books_signed_in, acct.email ?: ""))
        else setStatus(context.getString(R.string.books_signin_fail), error = true)
    }

    LaunchedEffect(Unit) {
        repo.refreshAll(null)
        if (driveEnabled) {
            signedInEmail = GoogleSignIn.getLastSignedInAccount(context)?.email
            folderId = (try { prefs.driveFolderIdFlow.first() } catch (_: Exception) { null }) ?: ""
        }
    }
    LaunchedEffect(books.size) { onCount(books.size) }

    val filtered = if (query.isBlank()) books
        else books.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val viBooks = filtered.filter { it.lang == "vi" }
    val enBooks = filtered.filter { it.lang == "en" }
    val otherBooks = filtered.filter { it.lang != "vi" && it.lang != "en" }
    val driveFolder: String? = if (driveEnabled) {
        folderId.trim().substringAfterLast("/").substringBefore("?").takeIf { it.isNotBlank() }
    } else null

    val handleDownload: (BookMeta) -> Unit = { book ->
        scope.launch {
            busyId = book.id
            setStatus(context.getString(R.string.books_downloading, book.title))
            try {
                repo.download(book)
                repo.refreshAll(driveFolder)
                setStatus(context.getString(R.string.books_dl_one, book.title))
            } catch (e: Exception) {
                setStatus(context.getString(R.string.book_download_fail, e.message ?: ""), error = true)
            }
            busyId = null
        }
    }
    val handleDelete: (BookMeta) -> Unit = { book ->
        scope.launch {
            busyId = book.id
            setStatus(context.getString(R.string.books_del_progress, book.title))
            try {
                repo.delete(book)
                repo.refreshAll(driveFolder)
                setStatus(context.getString(R.string.books_del_one, book.title))
            } catch (e: Exception) {
                setStatus(context.getString(R.string.books_del_fail, e.message ?: ""), error = true)
            }
            busyId = null
        }
    }
    val handleWipeBooks: () -> Unit = {
        scope.launch {
            wipeBusy = true
            setStatus(context.getString(R.string.books_deleting_dl))
            try {
                val removed = withContext(Dispatchers.IO) {
                    var n = 0
                    listOf(FilePaths.pdfInbox(context), FilePaths.audioBooks(context)).forEach { dir ->
                        dir.listFiles()?.forEach { f -> if (f.deleteRecursively()) n++ }
                    }
                    n
                }
                repo.refreshAll(driveFolder)
                setStatus(context.getString(R.string.books_deleted_dl, removed))
            } catch (e: Exception) {
                setStatus(context.getString(R.string.books_del_fail, e.message ?: ""), error = true)
            }
            wipeBusy = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }

        Button(
            onClick = {
                scope.launch {
                    syncing = true
                    setStatus(context.getString(R.string.books_syncing))
                    try {
                        val ok = repo.refreshAll(driveFolder)
                        if (ok) setStatus(context.getString(R.string.books_synced, repo.books.value.size))
                        else setStatus(context.getString(R.string.books_no_catalog), error = true)
                    } catch (e: Exception) {
                        setStatus(context.getString(R.string.books_sync_fail, e.message ?: ""), error = true)
                    }
                    syncing = false
                }
            },
            enabled = !syncing && !urlBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (syncing) stringResource(R.string.books_syncing_short) else stringResource(R.string.books_sync))
        }

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text(stringResource(R.string.books_search)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = urlInput, onValueChange = { urlInput = it },
                label = { Text(stringResource(R.string.books_add_link)) },
                placeholder = { Text("https://…") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (urlInput.isBlank()) return@Button
                    scope.launch {
                        urlBusy = true
                        setStatus(context.getString(R.string.books_downloading_link))
                        try {
                            val f = repo.downloadFromUrl(urlInput.trim())
                            setStatus(context.getString(R.string.books_added, f.name))
                            urlInput = ""
                        } catch (e: Exception) {
                            setStatus(context.getString(R.string.books_link_fail, e.message ?: ""), error = true)
                        }
                        urlBusy = false
                    }
                },
                enabled = !urlBusy && !syncing
            ) { Text(stringResource(R.string.common_add)) }
        }

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) stringResource(R.string.common_hide_advanced) else stringResource(R.string.common_advanced), fontSize = 11.sp)
            }
            TextButton(onClick = handleWipeBooks, enabled = !wipeBusy && !syncing && busyId == null) {
                Text(if (wipeBusy) stringResource(R.string.common_deleting) else stringResource(R.string.books_delete_dl), fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        if (showAdvanced && driveEnabled) {
            OutlinedTextField(
                value = folderId, onValueChange = { folderId = it },
                label = { Text(stringResource(R.string.books_drive_folder)) },
                placeholder = { Text("https://drive.google.com/drive/folders/…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        try { signInLauncher.launch(driveSource.getSignInIntent()) }
                        catch (e: Exception) { setStatus(context.getString(R.string.books_signin_err, e.message ?: ""), error = true) }
                    },
                    enabled = !syncing, modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (signedInEmail != null) stringResource(R.string.books_signed_in, signedInEmail ?: "") else stringResource(R.string.books_signin),
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            try {
                                prefs.setDriveFolderId(folderId.trim())
                                setStatus(context.getString(R.string.books_drive_saved))
                            } catch (e: Exception) {
                                setStatus(context.getString(R.string.books_drive_save_fail, e.message ?: ""), error = true)
                            }
                        }
                    },
                    enabled = !syncing
                ) { Text(stringResource(R.string.common_save), fontSize = 12.sp) }
            }
        }

        HorizontalDivider()

        if (filtered.isEmpty()) {
            Text(
                if (books.isEmpty()) stringResource(R.string.books_empty)
                else stringResource(R.string.books_not_found, query),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
        } else {
            BookGroup(
                title = stringResource(R.string.books_group_vi), list = viBooks,
                expanded = expandedGroup == "vi",
                onToggle = { expandedGroup = if (expandedGroup == "vi") null else "vi" },
                busyId = busyId, onDownload = handleDownload, onDelete = handleDelete
            )
            BookGroup(
                title = stringResource(R.string.books_group_en), list = enBooks,
                expanded = expandedGroup == "en",
                onToggle = { expandedGroup = if (expandedGroup == "en") null else "en" },
                busyId = busyId, onDownload = handleDownload, onDelete = handleDelete
            )
            BookGroup(
                title = stringResource(R.string.books_group_other), list = otherBooks,
                expanded = expandedGroup == "other",
                onToggle = { expandedGroup = if (expandedGroup == "other") null else "other" },
                busyId = busyId, onDownload = handleDownload, onDelete = handleDelete
            )
        }
    }
}

/**
 * Vùng dữ liệu nguy hiểm: đặt lại toàn bộ cài đặt.
 * 2 bước xác nhận để tránh bấm nhầm.
 */
@Composable
private fun DataResetSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { BibotPreferences(context) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(confirm) { if (confirm) { delay(5000); confirm = false } }
    LaunchedEffect(msg) { if (msg != null) { delay(5000); msg = null } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.data_nobackup),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
        OutlinedButton(
            onClick = {
                if (!confirm) {
                    confirm = true
                    msg = context.getString(R.string.data_confirm_hint)
                } else {
                    scope.launch {
                        confirm = false
                        try {
                            prefs.clearAll()
                            msg = context.getString(R.string.data_reset_done)
                        } catch (e: Exception) {
                            msg = context.getString(R.string.common_error, e.message ?: "")
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (confirm) stringResource(R.string.data_confirm) else stringResource(R.string.data_reset_all),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        msg?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BookGroup(
    title: String,
    list: List<BookMeta>,
    expanded: Boolean,
    onToggle: () -> Unit,
    busyId: String?,
    onDownload: (BookMeta) -> Unit,
    onDelete: (BookMeta) -> Unit
) {
    if (list.isEmpty()) return
    val collapsedLimit = 6
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "$title — ${list.size}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        if (list.size > collapsedLimit) {
            TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_view_all), fontSize = 11.sp)
            }
        }
    }
    val shown = if (expanded) list else list.take(collapsedLimit)
    shown.forEachIndexed { i, book ->
        BookLibraryItem(
            book = book,
            busy = busyId == book.id,
            onDownload = { onDownload(book) },
            onDelete = { onDelete(book) }
        )
        if (i < shown.lastIndex) HorizontalDivider()
    }
}

@Composable
private fun BookLibraryItem(
    book: BookMeta,
    busy: Boolean = false,
    onDownload: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (book.lang == "vi") Icons.Filled.Book else Icons.Filled.Book,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (book.lang == "vi") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                "${book.lang.uppercase()} • ${book.source} • " +
                    (if (book.isDownloaded) stringResource(R.string.book_is_downloaded) else stringResource(R.string.book_not_downloaded)) +
                    if (book.displaySize.isNotEmpty()) " • ${book.displaySize}" else "",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = if (book.isDownloaded) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else if (!book.isDownloaded && onDownload != null) {
            OutlinedButton(
                onClick = onDownload,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                enabled = !busy
            ) { Text(stringResource(R.string.common_download), fontSize = 11.sp) }
        } else if (book.isDownloaded && onDelete != null) {
            TextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) { Text(stringResource(R.string.common_delete), fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelInfo, installed: Boolean, installedBytes: Long, downloadState: DownloadState,
    onDownload: () -> Unit,
    onInstallFromFile: (String) -> Unit,
    onDelete: () -> Unit
) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { onInstallFromFile(it.toString()) }
    }
    val pickedHint = stringResource(R.string.voice_need_file, model.fileName)

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = if (model.type == ModelType.STT) Icons.Filled.Mic else Icons.Filled.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleSmall)
                    Text("${model.type} • ${model.lang.uppercase()} • ~${model.installedSizeMb}MB", style = MaterialTheme.typography.labelSmall)
                }
                if (installed) Badge { Text(stringResource(R.string.common_installed)) } else Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer) { Text(stringResource(R.string.common_not_installed)) }
            }
            Spacer(Modifier.height(6.dp))
            Text(model.description, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
            if (installed) Text(stringResource(R.string.voice_used_mb, installedBytes / (1024*1024)), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
            when (downloadState) {
                is DownloadState.Downloading -> { LinearProgressIndicator(progress = { downloadState.percent / 100f }, modifier = Modifier.fillMaxWidth()); Text(stringResource(R.string.set_downloading, downloadState.percent), style = MaterialTheme.typography.labelSmall) }
                is DownloadState.Unzipping -> { LinearProgressIndicator(progress = { downloadState.percent / 100f }, modifier = Modifier.fillMaxWidth()); Text(stringResource(R.string.set_unzipping, downloadState.percent), style = MaterialTheme.typography.labelSmall) }
                is DownloadState.Error -> Text(stringResource(R.string.common_error, downloadState.message), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else -> {}
            }
            Spacer(Modifier.height(6.dp))
            // File tải về (Download) thường bị gắn MIME lạ (.onnx/.tar.bz2) nên picker
            // DÙNG */* để luôn thấy file — không lọc MIME chặt gây "không tìm thấy file".
            Text(pickedHint, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!installed && downloadState !is DownloadState.Downloading && downloadState !is DownloadState.Unzipping) {
                    Button(onClick = onDownload, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_download)) }
                    OutlinedButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.common_install_file), fontSize = 11.sp)
                        }
                    }
                }
                if (installed) OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.common_delete)) }
                if (downloadState is DownloadState.Error) OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.common_retry)) }
            }
        }
    }
}

/** Dòng file .onnx đang chờ bước 2: hiện tên + cho cài luôn không cần .json. */
@Composable
private fun PendingOnnxRow(
    langLabel: String,
    onnxUri: Uri?,
    onInstallWithoutJson: () -> Unit,
    onClear: () -> Unit,
    busy: Boolean
) {
    if (onnxUri == null) return
    val name = rememberUriName(onnxUri) ?: stringResource(R.string.voice_picked_onnx)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_picked_lang, langLabel, name), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.voice_step2_optional), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
            }
            Button(onClick = onInstallWithoutJson, enabled = !busy, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.common_install_now), fontSize = 11.sp)
            }
            TextButton(onClick = onClear, enabled = !busy, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(stringResource(R.string.common_cancel), fontSize = 11.sp)
            }
        }
    }
}

/** Piper gọn: 1 dòng trạng thái + tải tự động, file lẻ ẩn trong "Cài từ file". */
@Composable
private fun PiperCompactRow(showAdvanced: Boolean, onToggleAdvanced: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    val readyVi = remember(busy) {
        try { PiperTtsHelper.isVoiceComplete(context, "vi") } catch (_: Exception) { false }
    }
    val readyEn = remember(busy) {
        try { PiperTtsHelper.isVoiceComplete(context, "en") } catch (_: Exception) { false }
    }

    val voiceViOnnxUri = remember { mutableStateOf<Uri?>(null) }
    val voiceEnOnnxUri = remember { mutableStateOf<Uri?>(null) }
    // NOTE: SAF trên nhiều máy gắn MIME lạ cho .onnx/.tar.bz2/.onnx.json trong
    // thư mục Download → mọi picker dùng */* để luôn thấy file (không lọc MIME).
    // File .onnx.json (2 đuôi) hay bị ẩn nhất khi lọc application/json.

    val enginePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                busy = true; progress = 0; msg = context.getString(R.string.voice_install_engine_file)
                val ok = SherpaEngine.installEngineFromFile(context, it) { progress = it }
                msg = if (ok) context.getString(R.string.voice_engine_ok) else context.getString(R.string.voice_engine_fail)
                busy = false
            }
        }
    }
    // Bước 2 (json) là TÙY CHỌN: user hủy picker (back) → vẫn cài với json=null,
    // deriveTokensFromJson sẽ tự tải .json từ mạng. Trước đây hủy là kẹt, không cài được.
    val voiceViJsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { jsonUri: Uri? ->
        val onnxUri = voiceViOnnxUri.value ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; progress = 0; msg = context.getString(R.string.voice_installing_vi_file)
            val ok = PiperTtsHelper.installVoiceFromFile(context, "vi", onnxUri, jsonUri) { progress = it }
            msg = if (ok) (if (jsonUri == null) context.getString(R.string.voice_vi_ok_json) else context.getString(R.string.voice_vi_ok)) else context.getString(R.string.voice_vi_fail)
            voiceViOnnxUri.value = null
            busy = false
        }
    }
    val voiceViPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { onnxUri: Uri? ->
        onnxUri?.let {
            voiceViOnnxUri.value = it
            voiceViJsonPicker.launch(arrayOf("*/*"))
        }
    }
    val voiceEnJsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { jsonUri: Uri? ->
        val onnxUri = voiceEnOnnxUri.value ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; progress = 0; msg = context.getString(R.string.voice_installing_en_file)
            val ok = PiperTtsHelper.installVoiceFromFile(context, "en", onnxUri, jsonUri) { progress = it }
            msg = if (ok) (if (jsonUri == null) context.getString(R.string.voice_en_ok_json) else context.getString(R.string.voice_en_ok)) else context.getString(R.string.voice_en_fail)
            voiceEnOnnxUri.value = null
            busy = false
        }
    }
    val voiceEnPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { onnxUri: Uri? ->
        onnxUri?.let {
            voiceEnOnnxUri.value = it
            voiceEnJsonPicker.launch(arrayOf("*/*"))
        }
    }
    val espeakPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                busy = true; progress = 0; msg = context.getString(R.string.voice_installing_espeak_file)
                val ok = PiperTtsHelper.installEspeakFromFile(context, it) { progress = it }
                msg = if (ok) context.getString(R.string.voice_espeak_ok) else context.getString(R.string.voice_espeak_fail)
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Piper: " + (if (SherpaEngine.loaded) "engine ready" else "engine missing") +
                " • VI " + (if (readyVi) "ready" else "missing") +
                " • EN " + (if (readyEn) "ready" else "missing"),
            style = MaterialTheme.typography.labelMedium
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    busy = true; progress = 0; msg = context.getString(R.string.voice_install_engine)
                    try {
                        val okEngine = SherpaEngine.ensureInstalled(context) { progress = it * 40 / 100 }
                        if (!okEngine) { msg = context.getString(R.string.voice_engine_fail_net); busy = false; return@launch }
                        msg = context.getString(R.string.voice_installing_vi)
                        val okVi = PiperTtsHelper.ensureVoice(context, "vi") { progress = 40 + it * 35 / 100 }
                        msg = context.getString(R.string.voice_installing_en)
                        val okEn = PiperTtsHelper.ensureVoice(context, "en") { progress = 75 + it * 25 / 100 }
                        msg = if (okVi && okEn) context.getString(R.string.voice_both_ok) else context.getString(R.string.voice_partial, okVi, okEn)
                    } catch (e: Exception) { msg = context.getString(R.string.voice_err_piper, e.message ?: "") }
                    busy = false
                }
            }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (busy) "$progress%" else stringResource(R.string.voice_piper_dl), fontSize = 12.sp)
            }
            OutlinedButton(onClick = onToggleAdvanced, enabled = !busy) {
                Text(if (showAdvanced) stringResource(R.string.common_hide_singles) else stringResource(R.string.common_install_file), fontSize = 12.sp)
            }
        }
        if (showAdvanced) {
            Text(
                stringResource(R.string.voice_pick_tip),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            PendingOnnxRow(
                langLabel = "VI",
                onnxUri = voiceViOnnxUri.value,
                onInstallWithoutJson = {
                    val onnxUri = voiceViOnnxUri.value ?: return@PendingOnnxRow
                    scope.launch {
                        busy = true; progress = 0; msg = context.getString(R.string.voice_installing_vi_file)
                        val ok = PiperTtsHelper.installVoiceFromFile(context, "vi", onnxUri, null) { progress = it }
                        msg = if (ok) context.getString(R.string.voice_vi_ok_json) else context.getString(R.string.voice_vi_fail)
                        voiceViOnnxUri.value = null
                        busy = false
                    }
                },
                onClear = { voiceViOnnxUri.value = null },
                busy = busy
            )
            PendingOnnxRow(
                langLabel = "EN",
                onnxUri = voiceEnOnnxUri.value,
                onInstallWithoutJson = {
                    val onnxUri = voiceEnOnnxUri.value ?: return@PendingOnnxRow
                    scope.launch {
                        busy = true; progress = 0; msg = context.getString(R.string.voice_installing_en_file)
                        val ok = PiperTtsHelper.installVoiceFromFile(context, "en", onnxUri, null) { progress = it }
                        msg = if (ok) context.getString(R.string.voice_en_ok_json) else context.getString(R.string.voice_en_fail)
                        voiceEnOnnxUri.value = null
                        busy = false
                    }
                },
                onClear = { voiceEnOnnxUri.value = null },
                busy = busy
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { enginePicker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.voice_pick_engine), fontSize = 11.sp)
                }
                OutlinedButton(onClick = { voiceViPicker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.voice_pick_vi), fontSize = 11.sp)
                }
                OutlinedButton(onClick = { voiceEnPicker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.voice_pick_en), fontSize = 11.sp)
                }
            }
            OutlinedButton(onClick = { espeakPicker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.voice_pick_espeak), fontSize = 11.sp)
            }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
    }
}

/**
 * Muc doi thuong QC (ban demo chinh thuc): xem rewarded 15-30s → mo 3 chuong giong Cloud.
 * Ban commercial VIP khong render muc nay (gate o SettingsScreen).
 */
@Composable
private fun AdsRewardContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adsPrefs = remember { com.bibot.app.data.ads.AdsPreferences(context) }
    val adsState by adsPrefs.adsStateFlow.collectAsState(initial = com.bibot.app.data.ads.AdsState())
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity

    LaunchedEffect(Unit) {
        try {
            if (activity != null) com.bibot.app.data.ads.AdManager.loadRewarded(activity)
        } catch (_: Exception) {}
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (com.bibot.app.data.ads.isVipActive(adsState)) {
            Text(
                stringResource(R.string.ads_vip_on),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            stringResource(R.string.ads_cloud_left, adsState.rewardedCloudChapters),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        status?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        FilledTonalButton(onClick = {
            if (busy) return@FilledTonalButton
            if (activity == null) { status = context.getString(R.string.ads_no_ad); return@FilledTonalButton }
            if (!com.bibot.app.ui.components.ads.canShowRewarded(adsState)) {
                status = context.getString(R.string.ads_cooldown)
                return@FilledTonalButton
            }
            busy = true
            com.bibot.app.data.ads.AdManager.onRewardedClaimed = {
                scope.launch {
                    try {
                        adsPrefs.claimRewardedCloudChapters(3)
                        status = context.getString(R.string.ads_reward_ok)
                    } catch (_: Exception) {
                        status = context.getString(R.string.ads_reward_fail)
                    } finally {
                        busy = false
                    }
                }
            }
            val shown = try {
                com.bibot.app.data.ads.AdManager.showRewarded(activity)
            } catch (_: Exception) { false }
            if (!shown) {
                try { com.bibot.app.data.ads.AdManager.loadRewarded(activity) } catch (_: Exception) {}
                status = context.getString(R.string.ads_loading)
                busy = false
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) stringResource(R.string.common_opening) else stringResource(R.string.ads_watch_btn), fontSize = 12.sp)
        }
    }
}

/**
 * Nhap ma VIP (ban demo chinh thuc): xac thuc offline qua VipCode, mo quyen VIP.
 * Thang: cong don toi da 12 thang tu hien tai. Vinh vien: khong han.
 * Het han: tu clear ve co ban (sweep luc mo man hinh + luc het han khi dang mo).
 */
@Composable
private fun VipContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adsPrefs = remember { com.bibot.app.data.ads.AdsPreferences(context) }
    val adsState by adsPrefs.adsStateFlow.collectAsState(initial = com.bibot.app.data.ads.AdsState())
    var code by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // now lay moi moi recomposition (khong chup 1 lan) de het han lật trạng thái ngay.
    val now = System.currentTimeMillis()
    val vip = com.bibot.app.data.ads.isVipActive(adsState, now)

    // Sweep 1: vao man hinh -> don VIP het han / tier rac ve co ban.
    LaunchedEffect(Unit) {
        try {
            if (adsPrefs.checkAndClearExpiredVip()) {
                status = context.getString(R.string.vip_expired_back)
            }
        } catch (_: Exception) {}
    }
    // Sweep 2: dang mo man hinh ma qua han -> clear ngay de quay ve co ban.
    LaunchedEffect(adsState.vipTier, adsState.vipUntil) {
        try {
            val t = adsState.vipTier
            val u = adsState.vipUntil
            if (t.isNotEmpty() && u != Long.MAX_VALUE && u > 0 && System.currentTimeMillis() > u) {
                adsPrefs.clearVip()
                status = context.getString(R.string.vip_expired_back)
            }
        } catch (_: Exception) {}
    }

    fun fmtDate(ms: Long): String = try {
        java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("vi", "VN"))
            .format(java.util.Date(ms))
    } catch (_: Exception) { "" }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (vip) {
            val daysLeft = com.bibot.app.data.ads.vipDaysLeft(adsState, now)
            Text(
                if (adsState.vipTier == com.bibot.app.data.ads.VIP_TIER_LIFETIME) stringResource(R.string.vip_life_on)
                else if (daysLeft == Int.MAX_VALUE) stringResource(R.string.vip_life_on)
                else stringResource(R.string.vip_month_on_days, fmtDate(adsState.vipUntil), daysLeft),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                stringResource(R.string.vip_free_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(40) },
            label = { Text(stringResource(R.string.common_vip_code)) },
            placeholder = { Text(stringResource(R.string.vip_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        FilledTonalButton(onClick = {
            if (busy || code.isBlank()) return@FilledTonalButton
            busy = true
            status = null
            scope.launch {
                try {
                    val input = code.trim()
                    // I2: verify qua relay + grace offline (VipVerifier). Server cho hạn
                    // tuyệt đối nên nhập lại cùng mã chỉ refresh, không cộng dồn oan.
                    when (val r = com.bibot.app.data.ads.VipVerifier.redeem(context, adsPrefs, input)) {
                        is com.bibot.app.data.ads.VipVerifier.RedeemResult.VerifiedOnline -> {
                            status = if (r.lifetime) {
                                context.getString(R.string.vip_life_ok)
                            } else {
                                context.getString(R.string.vip_month_ok, r.months, fmtDate(r.until))
                            }
                            if (r.sharedWarn) {
                                status = (status ?: "") + " " + context.getString(R.string.vip_shared_warn)
                            }
                            code = ""
                        }
                        is com.bibot.app.data.ads.VipVerifier.RedeemResult.GraceOffline -> {
                            status = if (r.lifetime) {
                                context.getString(R.string.vip_life_on)
                            } else {
                                context.getString(R.string.vip_grace_offline, fmtDate(r.until))
                            }
                        }
                        is com.bibot.app.data.ads.VipVerifier.RedeemResult.NeedOnline -> {
                            status = context.getString(R.string.vip_need_online)
                        }
                        is com.bibot.app.data.ads.VipVerifier.RedeemResult.Invalid -> {
                            status = context.getString(R.string.vip_invalid, r.reason)
                        }
                    }
                } catch (_: Exception) {
                    status = context.getString(R.string.vip_redeem_fail)
                } finally {
                    busy = false
                }
            }
        }, enabled = !busy && code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) stringResource(R.string.common_checking) else stringResource(R.string.common_redeem), fontSize = 12.sp)
        }
    }
}

/**
 * Huong dan & Tro giup: render cac chuoi help_* (CDATA, HTML-lite <b> + <a href>).
 */
/**
 * Lời tác giả (B6: chuyển từ nút Info ở Home thành About trong Settings).
 * Nội dung thư giữ nguyên tiếng Việt.
 */
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
private fun CreditsContent() {
    val uriHandler = LocalUriHandler.current
    val entries = listOf(
        stringResource(R.string.licenses_fluent) to "https://github.com/microsoft/fluentui-emoji",
        stringResource(R.string.licenses_material) to "https://fonts.google.com/icons"
    )
    val verName = try { BuildConfig.VERSION_NAME } catch (_: Exception) { "?" }
    val verCode = try { BuildConfig.VERSION_CODE } catch (_: Exception) { 0 }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.app_version_format, verName, verCode),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.licenses_intro),
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 22.sp
        )
        entries.forEach { (label, url) ->
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable {
                    try { uriHandler.openUri(url) } catch (_: Exception) {}
                }
            )
        }
    }
}

@Composable
private fun AuthorNoteContent() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
}
@Composable
private fun HelpGuideContent() {
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val topics = listOf(
        R.string.help_title_offline_stt to R.string.help_offline_stt,
        R.string.help_title_offline_tts to R.string.help_offline_tts,
        R.string.help_title_cloud_api to R.string.help_cloud_api,
        R.string.help_title_offline_model to R.string.help_offline_model,
        R.string.help_title_faq to R.string.help_faq,
        R.string.help_title_contact to R.string.help_contact
    )

    fun parse(raw: String): AnnotatedString = buildAnnotatedString {
        val s = raw.replace("\\n", "\n")
        val tagRe = Regex("""<b>(.*?)</b>|<a href="(.*?)">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        var pos = 0
        for (m in tagRe.findAll(s)) {
            if (m.range.first > pos) append(s.substring(pos, m.range.first))
            val bold = m.groups[1]?.value
            val url = m.groups[2]?.value
            val label = m.groups[3]?.value
            if (bold != null) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(bold)
                pop()
            } else if (url != null && label != null) {
                pushStringAnnotation("URL", url)
                pushStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Bold))
                append(label)
                pop()
                pop()
            }
            pos = m.range.last + 1
        }
        if (pos < s.length) append(s.substring(pos))
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        topics.forEach { (titleRes, bodyRes) ->
            ExpandableSection(
                title = stringResource(titleRes),
                subtitle = "",
                defaultExpanded = false
            ) {
                val body = parse(stringResource(bodyRes))
                ClickableText(
                    text = body,
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    onClick = { offset ->
                        body.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                            try { uriHandler.openUri(it.item) } catch (_: Exception) {}
                        }
                    }
                )
            }
        }
    }
}

/**
 * Lich su thong bao NPT (pull JSON tu raw GitHub, fallback jsDelivr, cache 24h nen
 * offline van xem duoc). Mo muc la danh dau tat ca da xem (tat badge Home).
 * Co nut Lam moi (force = bo qua cache) + dong "Cap nhat: <thoi diem cache>": truoc
 * day muc nay im lang tai lai va khong cho biet dang xem ban cu hay ban moi.
 */
@Composable
private fun AnnounceHistoryContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { BibotPreferences(context) }
    var items by remember { mutableStateOf<List<com.bibot.app.core.announce.AnnounceItem>>(emptyList()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var updatedAt by remember { mutableStateOf<Long?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var stale by remember { mutableStateOf(false) }

    suspend fun load(force: Boolean) {
        val url = try { BuildConfig.ANNOUNCE_URL } catch (_: Exception) { "" }
        val fallback = try { BuildConfig.ANNOUNCE_URL_FALLBACK } catch (_: Exception) { "" }
        val cur = try { BuildConfig.VERSION_NAME } catch (_: Exception) { "" }
        val fresh = com.bibot.app.core.announce.AnnounceApi.fetch(
            context, listOf(url, fallback), force = force
        )
        items = fresh.filter { com.bibot.app.core.announce.AnnounceApi.isApplicable(it, cur) }
        updatedAt = com.bibot.app.core.announce.AnnounceApi.cachedAt(context)
        // Tải lỗi nhưng còn cache: vẫn hiện bản đã lưu, nói rõ để user không tưởng tin cũ.
        stale = fresh.isEmpty() && items.isNotEmpty()
    }

    LaunchedEffect(Unit) {
        try {
            load(force = false)
            // Mở mục = đã đọc → tắt badge trên Home.
            for (it in items) { try { prefs.markAnnounceSeen(it.id) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }
    if (items.isEmpty()) {
        Text(
            stringResource(R.string.announce_empty),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        updatedAt?.let { ms ->
            Text(
                stringResource(
                    R.string.announce_updated,
                    java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(ms))
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
        }
        TextButton(
            enabled = !refreshing,
            onClick = {
                refreshing = true
                scope.launch {
                    try { load(force = true) } catch (_: Exception) {}
                    refreshing = false
                }
            }
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.announce_refresh), fontSize = 12.sp)
        }
    }
    if (stale) {
        Text(
            stringResource(R.string.announce_cache_only),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            val icon = when (item.type) {
                "upgrade" -> Icons.Filled.SystemUpdate
                "bookstore" -> Icons.Filled.Storefront
                "promo" -> Icons.Filled.CardGiftcard
                else -> Icons.Filled.Campaign
            }
            Card(
                modifier = Modifier.fillMaxWidth().clickable {
                    scope.launch {
                        if (expandedId == item.id) expandedId = null
                        else {
                            expandedId = item.id
                            try { prefs.markAnnounceSeen(item.id) } catch (_: Exception) {}
                            if (item.url.isNotBlank()) {
                                try {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(item.url)
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (expandedId == item.id && item.body.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(item.body, style = MaterialTheme.typography.bodySmall)
                    }
                    if (item.publishedAt.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            item.publishedAt,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}
