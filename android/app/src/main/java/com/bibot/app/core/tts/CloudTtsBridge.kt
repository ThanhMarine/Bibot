package com.bibot.app.core.tts

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud TTS REST bridge — Google Cloud Text-to-Speech v1
 * Voice mặc định: vi-VN-Neural2-A (22050Hz) fallback vi-VN-Wavenet-A
 * Nếu không có apiKey → return null để HybridTtsManager fallback offline (Android TTS).
 */
object CloudTtsBridge {
    const val DEFAULT_VI_VOICE = "vi-VN-Neural2-A"
    const val FALLBACK_VI_VOICE = "vi-VN-Wavenet-A"
    const val DEFAULT_EN_VOICE = "en-US-Neural2-A"

    suspend fun synthesize(
        text: String,
        apiKey: String?,
        voice: String = DEFAULT_VI_VOICE,
        speakingRate: Float = 0.95f
    ): ByteArray? = withContext(Dispatchers.IO) {
        val clean = cleanForTts(text)
        if (clean.isBlank() || apiKey.isNullOrBlank()) return@withContext null
        val lang = when {
            voice.startsWith("en-") -> "en-US"
            voice.startsWith("vi-") -> "vi-VN"
            else -> "vi-VN"
        }
        var lastErr: Exception? = null
        val voicesToTry = listOf(voice, if (lang == "vi-VN") FALLBACK_VI_VOICE else DEFAULT_EN_VOICE).distinct()
        for (v in voicesToTry) {
            try {
                val b = synthesizeOnce(clean, apiKey, lang, v, speakingRate)
                if (b != null && b.isNotEmpty()) return@withContext b
            } catch (e: Exception) {
                lastErr = e
            }
        }
        // log last error for debugging
        lastErr?.printStackTrace()
        return@withContext null
    }

    private fun synthesizeOnce(clean: String, apiKey: String, lang: String, voice: String, speakingRate: Float): ByteArray? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            val body = JSONObject().apply {
                put("input", JSONObject().put("text", clean))
                put("voice", JSONObject().apply {
                    put("languageCode", lang)
                    put("name", voice)
                })
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "MP3")
                    put("speakingRate", speakingRate.coerceIn(0.5f, 2.0f))
                    put("sampleRateHertz", 24000)
                })
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode >= 400) {
                val err = try { conn.errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } } catch (_: Exception) { null }
                throw RuntimeException("Cloud TTS HTTP ${conn.responseCode}: ${err?.take(300)}")
            }
            val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            val json = JSONObject(resp)
            val b64 = json.optString("audioContent", "")
            if (b64.isBlank()) return null
            return Base64.decode(b64, Base64.DEFAULT)
        } finally {
            conn?.disconnect()
        }
    }

    // Light cleaner mirroring Go tts.CleanTTSText for Kotlin offline path
    private fun cleanForTts(s: String): String {
        var t = s
        // 2.5: HTML/markup thừa (sách convert): `<!-- CHAPTER-BREAK -->`, `<a id=…>`, `&nbsp;`,
        // bảng `|---|`, `# heading` — chạy TRƯỚC để `=` trong comment không thành "bằng".
        t = com.bibot.app.core.text.TextCleaner.stripHtml(t)
        t = com.bibot.app.core.text.TextCleaner.stripMarkup(t)
        // Marker pause là riêng tư producer/consumer — không gửi lên Google TTS
        t = t.replace(com.bibot.app.core.text.TextCleaner.PAUSE_MARKER, " ")
        t = t.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), " ")
        t = t.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        t = t.replace(Regex("https?://\\S+|www\\.\\S+"), " ")
        t = t.replace(Regex("\\*\\*|\\*|__|```|`"), "")
        t = t.replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
        t = t.replace(Regex("^[\\-*+]\\s+", RegexOption.MULTILINE), "")
        t = t.replace(Regex("\\(\\s*\\)"), "")
        // emoji strip (basic)
        t = t.replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
        t = t.replace(Regex("\\s+"), " ")
        return t.trim()
    }
}
