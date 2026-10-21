package com.bibot.app.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Android standard: Jetpack DataStore Preferences (thay .env / SharedPreferences)
// File: /data/data/com.bibot.app/files/datastore/bibot.preferences_pb  (via FilePaths)
private val Context.bibotDataStore: DataStore<Preferences> by preferencesDataStore(name = "bibot")

object PrefKeys {
    val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    val OPENROUTER_API_KEY = stringPreferencesKey("openrouter_api_key")
    // Cloud TTS (Google Text-to-Speech) cần key RIÊNG — Gemini key (AIza, restricted
    // generativelanguage) gửi sang texttospeech.googleapis.com là 400/403.
    val CLOUD_TTS_API_KEY = stringPreferencesKey("cloud_tts_api_key")
    val PERSONA = stringPreferencesKey("persona") // "Em là Bi"
    val BOT_PROMPT = stringPreferencesKey("bot_prompt") // template tuỳ biến, chứa {{persona}} {{datetime}}
    val BOT_STYLE = stringPreferencesKey("bot_style") // friendly/formal/humorous/concise
    val BOT_TONE = stringPreferencesKey("bot_tone") // warm/professional/playful/concise
    val BOT_MAX_WORDS = intPreferencesKey("bot_max_words") // 30
    val GEMINI_MODEL = stringPreferencesKey("gemini_model")
    val TTS_PRIORITY = stringPreferencesKey("tts_priority") // auto/offline/online
    val VOICE_SPEED = floatPreferencesKey("voice_speed") // 0.85
    val STORY_SPEED = floatPreferencesKey("story_speed") // 0.88
    val STT_OFFLINE_FIRST = booleanPreferencesKey("stt_offline_first")
    // Barge-in duplex thật (port Pi): mic mở song song loa + AEC NLMS lọc echo, ngắt lời real-talk
    val BARGE_IN_ENABLED = booleanPreferencesKey("barge_in_enabled")
    // STORY_PREFER_PIPER: đọc sách/truyện ưu tiên Piper on-device (sherpa) khi đã cài voice
    val STORY_PREFER_PIPER = booleanPreferencesKey("story_prefer_piper")
    val TTS_OFFLINE_FALLBACK = booleanPreferencesKey("tts_offline_fallback")
    val THEME = stringPreferencesKey("theme") // system/light/dark
    val LANGUAGE = stringPreferencesKey("language") // vi/en
    // 9.2: Drive kho sách — migrate từ SharedPreferences "bibot_drive" sang DataStore
    val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id") // BibotBooks folderId
    val DRIVE_PAGE_TOKEN = stringPreferencesKey("drive_page_token") // changes API token
    // 10.3: mốc push log lần cuối (chỉ push log mới, không re-push 500 row)
    val MEMORY_LAST_PUSH_TS = longPreferencesKey("memory_last_push_ts")
    // Thong bao NPT: id da xem (chuoi "id1,id2", giu 50 ma moi nhat)
    val SEEN_ANNOUNCE_IDS = stringPreferencesKey("seen_announce_ids")
    // B6.3: popup hint điều hướng chạm ở màn đọc — hiện 1 lần mỗi máy
    val READER_HINT_SEEN = booleanPreferencesKey("reader_hint_seen")
    // A2.3: Snap config — font size, interline, paragraph margin (ReadEra eraepub values)
    val READER_FONT_SIZE_SP = floatPreferencesKey("reader_font_size_sp") // 16sp default
    val READER_INTERLINE = floatPreferencesKey("reader_interline") // 1.2 default
    val READER_PARAGRAPH_MARGIN = floatPreferencesKey("reader_paragraph_margin") // 8dp default
    // B5.7: chia sẻ tóm tắt do máy sinh lên wiki công cộng (mặc định bật, tắt được)
    val SUMMARY_SHARE_ENABLED = booleanPreferencesKey("summary_share_enabled")
    // B6.19: nền màn đọc — "drum" (trống đồng, mặc định) | "solid" (đơn sắc cùng tông)
    val READER_BACKGROUND = stringPreferencesKey("reader_background")
    // E2.4: display modes PDF — fit "width"|"height"; lật trang
    // "continuous"|"single"|"dual"; màu trang "normal"|"sepia"|"night"; xoay "0"|"90"|"180"|"270"
    val READER_FIT_MODE = stringPreferencesKey("reader_fit_mode")
    val READER_PAGING_MODE = stringPreferencesKey("reader_paging_mode")
    val READER_PAGE_THEME = stringPreferencesKey("reader_page_theme")
    val READER_PAGE_ROTATION = stringPreferencesKey("reader_page_rotation")
}

// A2.3: Allowed values from ReadEra eraepub
val ALLOWED_FONT_SIZES = floatArrayOf(10f, 11f, 12f, 13f, 14f, 15f, 16f, 17f, 18f, 19f, 20f, 22f, 24f, 26f, 28f, 30f)
val ALLOWED_INTERLINES = floatArrayOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.8f, 2.0f)
val ALLOWED_PARAGRAPH_MARGINS = floatArrayOf(0f, 2f, 4f, 6f, 8f, 10f, 12f, 14f, 16f, 18f, 20f, 24f)

data class BibotPrefs(
    val persona: String = "Em là Bi",
    val botPrompt: String = BotPromptManager.DEFAULT_TEMPLATE,
    val botStyle: String = "friendly",
    val botTone: String = "warm",
    val botMaxWords: Int = 30,
    val geminiModel: String = "gemini-3.5-flash-lite",
    val geminiApiKey: String = "",
    val openRouterKey: String = "",
    val cloudTtsApiKey: String = "",
    val ttsPriority: String = "auto",
    val voiceSpeed: Float = 0.85f,
    val storySpeed: Float = 0.88f,
    val sttOfflineFirst: Boolean = false,
    val bargeInEnabled: Boolean = true,
    val storyPreferPiper: Boolean = true,
    val ttsOfflineFallback: Boolean = true,
    val theme: String = "system",
    val language: String = "system",
    val readerHintSeen: Boolean = false,
    // A2.3: Snap config
    val readerFontSizeSp: Float = 16f,
    val readerInterline: Float = 1.2f,
    val readerParagraphMargin: Float = 8f,
    // B5.7: chia sẻ tóm tắt lên wiki công cộng
    val summaryShareEnabled: Boolean = true,
    // B6.19: nền màn đọc — "drum" (trống đồng) | "solid" (đơn sắc)
    val readerBackground: String = "drum",
    // E2.4: display modes PDF (khớp hằng ở PdfDisplayMode.kt)
    val readerFitMode: String = "width",
    val readerPagingMode: String = "continuous",
    val readerPageTheme: String = "normal",
    val readerPageRotation: String = "0"
)

class BibotPreferences(private val context: Context) {
    val prefsFlow: Flow<BibotPrefs> = context.bibotDataStore.data.map { p ->
        BibotPrefs(
            persona = p[PrefKeys.PERSONA] ?: "Em là Bi",
            botPrompt = p[PrefKeys.BOT_PROMPT] ?: BotPromptManager.DEFAULT_TEMPLATE,
            botStyle = p[PrefKeys.BOT_STYLE] ?: "friendly",
            botTone = p[PrefKeys.BOT_TONE] ?: "warm",
            botMaxWords = p[PrefKeys.BOT_MAX_WORDS] ?: 30,
            geminiModel = p[PrefKeys.GEMINI_MODEL] ?: "gemini-3.5-flash-lite",
            geminiApiKey = p[PrefKeys.GEMINI_API_KEY] ?: "",
            openRouterKey = p[PrefKeys.OPENROUTER_API_KEY] ?: "",
            cloudTtsApiKey = p[PrefKeys.CLOUD_TTS_API_KEY] ?: "",
            ttsPriority = p[PrefKeys.TTS_PRIORITY] ?: "auto",
            voiceSpeed = p[PrefKeys.VOICE_SPEED] ?: 0.85f,
            storySpeed = p[PrefKeys.STORY_SPEED] ?: 0.88f,
            sttOfflineFirst = p[PrefKeys.STT_OFFLINE_FIRST] ?: false,
            bargeInEnabled = p[PrefKeys.BARGE_IN_ENABLED] ?: true,
            storyPreferPiper = p[PrefKeys.STORY_PREFER_PIPER] ?: true,
            ttsOfflineFallback = p[PrefKeys.TTS_OFFLINE_FALLBACK] ?: true,
            theme = p[PrefKeys.THEME] ?: "system",
            language = p[PrefKeys.LANGUAGE] ?: "system",
            readerHintSeen = p[PrefKeys.READER_HINT_SEEN] ?: false,
            // A2.3: Snap config
            readerFontSizeSp = p[PrefKeys.READER_FONT_SIZE_SP] ?: 16f,
            readerInterline = p[PrefKeys.READER_INTERLINE] ?: 1.2f,
            readerParagraphMargin = p[PrefKeys.READER_PARAGRAPH_MARGIN] ?: 8f,
            summaryShareEnabled = p[PrefKeys.SUMMARY_SHARE_ENABLED] ?: true,
            readerBackground = p[PrefKeys.READER_BACKGROUND] ?: "drum",
            readerFitMode = p[PrefKeys.READER_FIT_MODE] ?: "width",
            readerPagingMode = p[PrefKeys.READER_PAGING_MODE] ?: "continuous",
            readerPageTheme = p[PrefKeys.READER_PAGE_THEME] ?: "normal",
            readerPageRotation = p[PrefKeys.READER_PAGE_ROTATION] ?: "0"
        )
    }
    val geminiKeyFlow: Flow<String> = context.bibotDataStore.data.map { it[PrefKeys.GEMINI_API_KEY] ?: "" }
    // 9.2: Drive folder/pageToken flows (thay SharedPreferences "bibot_drive")
    val driveFolderIdFlow: Flow<String?> = context.bibotDataStore.data.map { it[PrefKeys.DRIVE_FOLDER_ID] }
    val drivePageTokenFlow: Flow<String?> = context.bibotDataStore.data.map { it[PrefKeys.DRIVE_PAGE_TOKEN] }

    suspend fun setPersona(v: String) { context.bibotDataStore.edit { it[PrefKeys.PERSONA] = v } }
    suspend fun setBotPrompt(v: String) { context.bibotDataStore.edit { it[PrefKeys.BOT_PROMPT] = v } }
    suspend fun setBotStyle(v: String) { context.bibotDataStore.edit { it[PrefKeys.BOT_STYLE] = v } }
    suspend fun setBotTone(v: String) { context.bibotDataStore.edit { it[PrefKeys.BOT_TONE] = v } }
    suspend fun setBotMaxWords(v: Int) { context.bibotDataStore.edit { it[PrefKeys.BOT_MAX_WORDS] = v } }
    suspend fun setVoiceSpeed(v: Float) { context.bibotDataStore.edit { it[PrefKeys.VOICE_SPEED] = v } }
    suspend fun setSttOfflineFirst(v: Boolean) { context.bibotDataStore.edit { it[PrefKeys.STT_OFFLINE_FIRST] = v } }
    suspend fun setBargeInEnabled(v: Boolean) { context.bibotDataStore.edit { it[PrefKeys.BARGE_IN_ENABLED] = v } }
    suspend fun setStoryPreferPiper(v: Boolean) { context.bibotDataStore.edit { it[PrefKeys.STORY_PREFER_PIPER] = v } }
    suspend fun setTtsOfflineFallback(v: Boolean) { context.bibotDataStore.edit { it[PrefKeys.TTS_OFFLINE_FALLBACK] = v } }
    suspend fun setLanguage(v: String) { context.bibotDataStore.edit { it[PrefKeys.LANGUAGE] = v } }
    // B6.3: đã hiện popup hint điều hướng chạm (1 lần mỗi máy)
    suspend fun setReaderHintSeen() { context.bibotDataStore.edit { it[PrefKeys.READER_HINT_SEEN] = true } }
    suspend fun setReaderFontSizeSp(v: Float) { context.bibotDataStore.edit { it[PrefKeys.READER_FONT_SIZE_SP] = v } }
    suspend fun setReaderInterline(v: Float) { context.bibotDataStore.edit { it[PrefKeys.READER_INTERLINE] = v } }
    suspend fun setReaderParagraphMargin(v: Float) { context.bibotDataStore.edit { it[PrefKeys.READER_PARAGRAPH_MARGIN] = v } }
    // B5.7: tắt/mở chia sẻ tóm tắt lên wiki công cộng
    suspend fun setSummaryShareEnabled(v: Boolean) { context.bibotDataStore.edit { it[PrefKeys.SUMMARY_SHARE_ENABLED] = v } }
    // B6.19: nền màn đọc "drum"/"solid"
    suspend fun setReaderBackground(v: String) { context.bibotDataStore.edit { it[PrefKeys.READER_BACKGROUND] = v } }
    // E2.4: display modes PDF (fit/paging/theme/rotation)
    suspend fun setReaderFitMode(v: String) { context.bibotDataStore.edit { it[PrefKeys.READER_FIT_MODE] = v } }
    suspend fun setReaderPagingMode(v: String) { context.bibotDataStore.edit { it[PrefKeys.READER_PAGING_MODE] = v } }
    suspend fun setReaderPageTheme(v: String) { context.bibotDataStore.edit { it[PrefKeys.READER_PAGE_THEME] = v } }
    suspend fun setReaderPageRotation(v: Int) { context.bibotDataStore.edit { it[PrefKeys.READER_PAGE_ROTATION] = v.toString() } }

    // API keys lưu EncryptedSharedPreferences nếu cần; ở đây lưu DataStore + backupRules exclude
    suspend fun setGeminiKey(v: String) { context.bibotDataStore.edit { it[PrefKeys.GEMINI_API_KEY] = v } }
    suspend fun setOpenRouterKey(v: String) { context.bibotDataStore.edit { it[PrefKeys.OPENROUTER_API_KEY] = v } }
    suspend fun setCloudTtsKey(v: String) { context.bibotDataStore.edit { it[PrefKeys.CLOUD_TTS_API_KEY] = v } }
    suspend fun setGeminiModel(v: String) { context.bibotDataStore.edit { it[PrefKeys.GEMINI_MODEL] = v } }
    suspend fun setTtsPriority(v: String) { context.bibotDataStore.edit { it[PrefKeys.TTS_PRIORITY] = v } }
    // 9.2: Drive kho sách
    suspend fun setDriveFolderId(v: String) { context.bibotDataStore.edit { it[PrefKeys.DRIVE_FOLDER_ID] = v } }
    suspend fun setDrivePageToken(v: String) { context.bibotDataStore.edit { it[PrefKeys.DRIVE_PAGE_TOKEN] = v } }
    // 10.3: mốc push memory
    suspend fun getMemoryLastPushTs(): Long = context.bibotDataStore.data.map { it[PrefKeys.MEMORY_LAST_PUSH_TS] ?: 0L }.first()
    suspend fun setMemoryLastPushTs(v: Long) { context.bibotDataStore.edit { it[PrefKeys.MEMORY_LAST_PUSH_TS] = v } }
    // Thong bao NPT: tap id da xem
    val seenAnnounceIdsFlow: Flow<Set<String>> = context.bibotDataStore.data.map { p ->
        (p[PrefKeys.SEEN_ANNOUNCE_IDS] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    suspend fun markAnnounceSeen(id: String) {
        if (id.isBlank()) return
        context.bibotDataStore.edit { p ->
            val cur = ((p[PrefKeys.SEEN_ANNOUNCE_IDS] ?: "").split(",") + id)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().takeLast(50)
            p[PrefKeys.SEEN_ANNOUNCE_IDS] = cur.joinToString(",")
        }
    }

    /** Đặt lại toàn bộ cài đặt (API key, prompt, model, folder Drive…) về mặc định. */
    suspend fun clearAll() { context.bibotDataStore.edit { it.clear() } }
}
