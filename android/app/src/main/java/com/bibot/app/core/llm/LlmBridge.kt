package com.bibot.app.core.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

interface LlmStreamListener {
    fun onToken(token: String)
    fun onComplete()
    fun onError(errorMessage: String)
}

data class LlmUiState(
    val isStreaming: Boolean = false,
    val fullText: String = "",
    val error: String? = null
)

object LlmBridge {
    // Direct REST streaming to Gemini (primary) → Proxy (free for normal users) → OpenRouter fallback.
    // Mirrors internal/llm/llm.go 2.6 so Kotlin can work without Go AAR.
    suspend fun generateStreamRest(
        prompt: String,
        geminiKey: String?,
        systemPrompt: String,
        geminiModel: String = "gemini-3.5-flash-lite",
        openRouterKey: String? = null,
        proxyUrl: String? = null,
        // Guardrail: voice chat giới hạn 100-150 token/turn (ngắn gọn, đỡ tốn RAM/độ trễ)
        maxOutputTokens: Int = 1024,
        // Ngữ cảnh hội thoại (TASK chatHistory — port aiEngine.js sang Android):
        // sliding window oldest-first, role "user"|"assistant", đã truncate ở MemoryRepository.
        history: List<Pair<String, String>> = emptyList(),
        listener: LlmStreamListener
    ) = withContext(Dispatchers.IO) {
        try {
            var lastErr: String = "no source configured"
            if (history.isNotEmpty()) {
                android.util.Log.d("LlmBridge", "history=${history.size}msgs ${history.sumOf { it.second.length }}chars")
            }
            if (!geminiKey.isNullOrBlank()) {
                var attempts = 0
                while (true) {
                    attempts++
                    var emitted = 0
                    val counted = object : LlmStreamListener {
                        override fun onToken(token: String) { emitted++; listener.onToken(token) }
                        override fun onComplete() { listener.onComplete() }
                        override fun onError(errorMessage: String) { listener.onError(errorMessage) }
                    }
                    val err = streamGemini(geminiKey, geminiModel, systemPrompt, prompt, maxOutputTokens, history, counted)
                    if (err == null) return@withContext
                    lastErr = "gemini[$geminiModel]: ${err.message}"
                    // Retry only transient errors (503/429/network) before any token emitted; HTTP 4xx = permanent
                    val permanent = err.message?.contains("HTTP 4") == true
                    if (attempts >= 3 || emitted > 0 || permanent) break
                    kotlinx.coroutines.delay(1500)
                }
            }
            // Proxy relay for users without key — 2.6 (BIBOT_LLM_PROXY_URL from BuildConfig)
            val pUrl = proxyUrl ?: try { com.bibot.app.BuildConfig.BIBOT_LLM_PROXY_URL } catch (_: Exception) { "" }
            if (!pUrl.isNullOrBlank()) {
                val errP = streamProxy(pUrl, systemPrompt, prompt, maxOutputTokens, history, listener)
                if (errP == null) return@withContext
                lastErr = "proxy: ${errP.message}"
            }
            if (!openRouterKey.isNullOrBlank()) {
                val err2 = streamOpenRouter(openRouterKey, systemPrompt, prompt, maxOutputTokens, history, listener)
                if (err2 == null) return@withContext
                lastErr = "openrouter: ${err2.message}"
            }
            android.util.Log.w("LlmBridge", "all LLM sources failed, using fallback persona: $lastErr")
            // fallback persona (không lộ key)
            listener.onToken("Em là Bi. Hiện tại em chưa kết nối được AI, nhưng em vẫn ở đây ạ!")
            listener.onComplete()
        } catch (e: Exception) {
            listener.onError(e.message ?: "Lỗi không xác định")
        }
    }

    // Legacy bridge kept for Go AAR path (gomobile mobile.LLMStream)
    fun generateStream(prompt: String, listener: LlmStreamListener) {
        try {
            // Try Go Mobile if available via reflection (avoids hard compile dep before AAR built)
            val cls = Class.forName("mobile.Mobile")
            val method = cls.getMethod("LLMStream", String::class.java, Class.forName("mobile.StreamCallback"))
            val cb = object : Any() {
                @Suppress("unused") fun OnToken(t: String) = listener.onToken(t)
                @Suppress("unused") fun OnComplete() = listener.onComplete()
                @Suppress("unused") fun OnError(e: String) = listener.onError(e)
            }
            method.invoke(null, prompt, cb)
        } catch (_: Exception) {
            // Fallback mock keeping persona
            listener.onToken("Em là Bi. ")
            listener.onToken("Em đã nhận được câu hỏi: $prompt")
            listener.onComplete()
        }
    }

    private fun streamGemini(
        apiKey: String, model: String, systemPrompt: String, prompt: String, maxOutputTokens: Int,
        history: List<Pair<String, String>>, listener: LlmStreamListener
    ): Exception? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?key=$apiKey&alt=sse")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000; conn.readTimeout = 90000
            val body = JSONObject().apply {
                put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                // Ngữ cảnh hội thoại: history (user/model) + câu hiện tại cuối cùng (native contents API)
                put("contents", JSONArray().apply {
                    for ((role, text) in history) {
                        put(JSONObject().apply {
                            put("role", if (role == "assistant") "model" else "user")
                            put("parts", JSONArray().put(JSONObject().put("text", text)))
                        })
                    }
                    put(JSONObject().apply {
                        put("role", "user"); put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7); put("maxOutputTokens", maxOutputTokens)
                })
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode >= 400) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return Exception("Gemini HTTP ${conn.responseCode}: ${err.take(300)}")
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var yielded = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                var l = line!!.trim()
                if (l.isEmpty() || l.startsWith(":")) continue
                if (l.startsWith("data: ")) l = l.removePrefix("data: ")
                if (l == "[DONE]") break
                try {
                    val evt = JSONObject(l)
                    val cands = evt.optJSONArray("candidates") ?: continue
                    if (cands.length() == 0) continue
                    val c0 = cands.getJSONObject(0)
                    val content = c0.optJSONObject("content") ?: continue
                    val parts = content.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val txt = parts.getJSONObject(i).optString("text", "")
                        // Raw: fragment là mảnh LIÊN TỤC của response — thêm space sẽ
                        // tạo double-space (display) và chẻ giữa từ ("m"+"ọc" → "m ọc")
                        if (txt.isNotEmpty()) { yielded++; listener.onToken(txt) }
                    }
                } catch (_: Exception) { }
            }
            if (yielded == 0) return Exception("Gemini stream empty")
            listener.onComplete()
            null
        } catch (e: Exception) { e } finally { conn?.disconnect() }
    }

    private fun streamProxy(
        proxyUrl: String, systemPrompt: String, prompt: String, maxOutputTokens: Int,
        history: List<Pair<String, String>>, listener: LlmStreamListener
    ): Exception? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(proxyUrl.trimEnd('/') + "/v1/chat")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.doOutput = true
            conn.connectTimeout = 15000; conn.readTimeout = 90000
            // Proxy chỉ nhận prompt/system_prompt → ghép transcript gọn vào đầu prompt
            val fullPrompt = if (history.isEmpty()) prompt else buildString {
                append("[Hội thoại trước đó — ngữ cảnh để hiểu câu hiện tại]\n")
                for ((role, text) in history) {
                    append(if (role == "assistant") "Bi: " else "User: ").append(text).append("\n")
                }
                append("Câu hiện tại: ").append(prompt)
            }
            val body = JSONObject().apply {
                put("prompt", fullPrompt); put("system_prompt", systemPrompt); put("model", "gemini-2.5-flash-lite")
                put("max_output_tokens", maxOutputTokens)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode >= 400) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return Exception("Proxy HTTP ${conn.responseCode}: ${err.take(300)}")
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var yielded = 0; var line: String?
            while (reader.readLine().also { line = it } != null) {
                var l = line!!.trim()
                if (l.isEmpty() || l.startsWith(":")) continue
                if (l == "data: [DONE]" || l == "[DONE]") break
                if (l.startsWith("data: ")) l = l.removePrefix("data: ")
                try {
                    val evt = JSONObject(l)
                    // Gemini-style
                    val cands = evt.optJSONArray("candidates")
                    if (cands != null && cands.length() > 0) {
                        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                        if (parts != null) for (i in 0 until parts.length()) {
                            val txt = parts.getJSONObject(i).optString("text","")
                            if (txt.isNotEmpty()) { yielded++; listener.onToken(txt) }
                        }
                        continue
                    }
                    // OpenAI-style
                    val choices = evt.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val txt = choices.getJSONObject(0).optJSONObject("delta")?.optString("content","") ?: ""
                        if (txt.isNotEmpty()) { yielded++; listener.onToken(txt) ; continue }
                    }
                    // Plain {"text":"…"}
                    val t = evt.optString("text","")
                    if (t.isNotEmpty()) { yielded++; listener.onToken(t) }
                } catch (_: Exception) {
                    if (l.isNotEmpty()) { yielded++; listener.onToken(l) }
                }
            }
            if (yielded == 0) return Exception("Proxy stream empty")
            listener.onComplete(); null
        } catch (e: Exception) { e } finally { conn?.disconnect() }
    }

    private fun streamOpenRouter(
        apiKey: String, systemPrompt: String, prompt: String, maxOutputTokens: Int,
        history: List<Pair<String, String>>, listener: LlmStreamListener
    ): Exception? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("https://openrouter.ai/api/v1/chat/completions")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("HTTP-Referer", "https://github.com/bibot")
            conn.setRequestProperty("X-Title", "Bibot")
            conn.doOutput = true
            conn.connectTimeout = 15000; conn.readTimeout = 90000
            val payload = JSONObject().apply {
                put("model", "nex-agi/nex-n2.5-mini:free")
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    for ((role, text) in history) {
                        put(JSONObject().put("role", role).put("content", text))
                    }
                    put(JSONObject().put("role", "user").put("content", prompt))
                })
                put("temperature", 0.7); put("max_tokens", maxOutputTokens); put("stream", true)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            if (conn.responseCode >= 400) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP ${conn.responseCode}"
                return Exception("OpenRouter HTTP ${conn.responseCode}: ${err.take(300)}")
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            var yielded = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                var l = line!!.trim()
                if (l.isEmpty() || l.startsWith(":")) continue
                if (l == "data: [DONE]") break
                if (l.startsWith("data: ")) {
                    val data = l.removePrefix("data: ")
                    try {
                        val evt = JSONObject(data)
                        val choices = evt.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                        val txt = delta.optString("content", "")
                        if (txt.isNotEmpty()) { yielded++; listener.onToken(txt) }
                    } catch (_: Exception) {}
                }
            }
            if (yielded == 0) return Exception("OpenRouter stream empty")
            listener.onComplete()
            null
        } catch (e: Exception) { e } finally { conn?.disconnect() }
    }
}
