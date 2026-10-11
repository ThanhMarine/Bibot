package com.bibot.app.data.memory

import android.content.Context
import com.bibot.app.data.db.BibotDatabase
import com.bibot.app.data.db.MemoryCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 10.1-lite + 10.2 — Memory 3 tầng (port gọn `conversation_memory.py`):
 * observe_user regex (NAME/LIKE/DISLIKE/HOME/JOB/BIRTHDAY/REMEMBER) → Room `memory_core`,
 * recallAnswer() trả lời trực tiếp trước LLM (không tốn token, cùng mẫu lệnh nhạc local-first).
 * Chưa làm: CAT_CAPS/fact TTL/pin (T1), JSONL xoay + harvest (T2), Firestore sync (10.3).
 */

// key → regex bắt giá trị (group 1), chạy trên câu đã lowercase
private val OBSERVE_RES: List<Pair<String, Regex>> = listOf(
    "user_name" to Regex("(?:tôi|mình|em|anh|chị|tớ)\\s+tên\\s+(?:là\\s+)?(.+)|tên\\s+(?:tôi|mình)\\s+là\\s+(.+)|gọi\\s+(?:tôi|mình)\\s+là\\s+(.+)"),
    "user_like" to Regex("(?:tôi|mình)\\s+thích\\s+(?!nhất\\b)(.+)"),
    "user_dislike" to Regex("(?:tôi|mình)\\s+(?:không\\s+thích|ghét)\\s+(.+)"),
    "user_home" to Regex("(?:nhà\\s+(?:tôi|mình)\\s+ở|(?:tôi|mình)\\s+(?:ở|sống\\s+ở)\\s+)(.+)|quê\\s+(?:tôi|mình)\\s+(?:ở\\s+)?(.+)"),
    "user_job" to Regex("(?:tôi|mình)\\s+làm\\s+(?:nghề\\s+)?(.+)|công\\s+việc\\s+của\\s+(?:tôi|mình)\\s+là\\s+(.+)"),
    "user_birthday" to Regex("sinh\\s+nhật\\s+(?:tôi|mình)\\s+(?:là|vào|ngày\\s+)?(.+)|(?:tôi|mình)\\s+sinh\\s+(?:ngày\\s+)?(.+)"),
    "user_note" to Regex("(?:hãy\\s+)?nhớ\\s+(?:rằng|giúp\\s+(?:tôi|mình)|kỹ\\s+)?(.+)")
)

private fun firstGroup(m: MatchResult): String? =
    m.groupValues.drop(1).firstOrNull { it.isNotBlank() }
        ?.trim()?.trimEnd('.', '!', '?', ',', ';')?.trim()?.ifBlank { null }

/**
 * Chống ảo giác memory (fix 2026-09-30): câu hỏi "tôi tên gì / ở đâu / làm gì /
 * sinh ngày nào / bạn nhớ gì" KHỚP regex observe (tên\s+(.+), ở\s+(.+), ...) nên
 * từng lưu "gì/đâu/nào" vào DB, rồi recallAnswer nhại lại "Bạn tên là gì." —
 * vòng lặp tự đầu độc. Chặn: câu hỏi thì KHÔNG observe slot đó.
 */
private val QUESTION_TAILS = listOf(
    " gì", " gì vậy", " gì thế", " nào", " nào vậy", " đâu", " đâu vậy",
    " không", " chưa", " phải không", " hay không", " hay", " à", " ư",
    " chứ", " nhỉ", " sao", " thế nào", " bao giờ", " khi nào", " mấy giờ",
    " ở đâu", " là ai", " là gì", " thế", " hả", " đúng không", " được không",
    " không bạn", " không em", " không anh", " không chị", " à bạn", " vậy"
)

private fun looksLikeQuestion(t: String): Boolean {
    if ("?" in t) return true
    val tail = t.trimEnd(' ', '.', '!', '…')
    return QUESTION_TAILS.any { tail.endsWith(it) }
}

/**
 * Gating ngữ cảnh (tiết kiệm token): chỉ gửi history khi câu hiện tại CẦN nó.
 * Câu độc lập ("thời tiết hôm nay thế nào", "kể một câu đố vui") → skip, 0 token thêm.
 * Dấu hiệu câu nối: đại từ/từ nối (nó/đó/còn/nữa/tiếp/thế còn...) hoặc câu quá ngắn
 * thiếu chủ ngữ (≤3 từ: "gợi ý", "đáp án là gì"). Đổi chủ đề → skip.
 * Lưu ý: \b ASCII bẻ với tiếng Việt có dấu → dùng phân tách tường minh.
 */
private const val SEP_CLS = "[\\s,.!?;:…\"]"
private fun wordRe(vararg ws: String) =
    Regex("(^|$SEP_CLS)(${ws.joinToString("|")})($|$SEP_CLS)")

private val FOLLOWUP_RES = listOf(
    wordRe("nó", "chúng nó", "đó", "kia", "này", "đấy", "ấy", "đây", "đó đi"),
    wordRe("còn", "nữa", "tiếp", "tiếp theo", "thế còn", "vậy còn", "còn gì",
        "sao nữa", "rồi sao", "kế tiếp", "thêm", "thế nào", "tại sao", "vì sao",
        "sao vậy", "như nào", "như thế nào")
)
private val NEW_TOPIC_MARKERS = listOf(
    "chuyển sang", "chuyện khác", "chủ đề khác", "bỏ qua đi",
    "thôi đủ rồi", "à mà", "nhân tiện"
)

fun needsHistory(rawText: String): Boolean {
    val t = rawText.trim().lowercase()
    if (t.isEmpty()) return false
    if (NEW_TOPIC_MARKERS.any { it in t }) return false
    if (FOLLOWUP_RES.any { it.containsMatchIn(t) }) return true
    val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.size <= 3 // quá ngắn, thiếu chủ ngữ → nhiều khả năng là câu nối
}

/** Giá trị rác từng lọt DB ("gì/đâu/nào/nhé...") — chặn ghi mới + purge cũ + recall bỏ qua. */
private val VALUE_STOPLIST = setOf(
    "gì", "gi", "đâu", "dau", "nào", "nao", "ai", "không", "ko", "k",
    "ạ", "nhé", "nha", "nhỉ", "ơi", "à", "ừ", "vâng", "dạ", "ừm",
    "không biết", "chưa biết", "không rõ", "ai vậy", "gì vậy", "đâu vậy",
    "nào vậy", "thế", "vậy", "kia", "này", "đây", "thế nào", "sao vậy"
)

private fun cleanStored(v: String?): String? {
    val t = v?.trim()?.lowercase() ?: return null
    if (t.length < 2 || t in VALUE_STOPLIST) return null
    return v!!.trim()
}

/** Tỉa giá trị observe: cắt mệnh đề phụ + giới hạn số từ (tránh "tôi tên là An, rất vui..." → cả câu). */
private fun trimValue(key: String, raw: String): String? {
    var v = raw.split(Regex("[,;.!?\\n]")).firstOrNull()?.trim() ?: return null
    if (key == "user_name") {
        v = v.split(Regex("\\s+(sinh|năm|quê|ở|sống)\\s+")).firstOrNull()?.trim() ?: v
    } else {
        v = v.split(" nhưng ").firstOrNull()?.trim() ?: v
    }
    val maxWords = when (key) {
        "user_name" -> 5
        "user_note" -> 30
        else -> 12
    }
    val words = v.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    v = words.take(maxWords).joinToString(" ")
    if (v.length > 80) return null
    return cleanStored(v)
}

class MemoryRepository(private val context: Context) {
    private val db by lazy { BibotDatabase.get(context) }

    /** Ghi nhớ từ câu user — gọi fire-and-forget mỗi lượt (IO, regex rẻ). */
    suspend fun observeUser(rawText: String): Unit = withContext(Dispatchers.IO) {
        val text = rawText.trim().lowercase()
        if (text.isEmpty()) return@withContext
        try {
            // Câu hỏi ("tôi tên gì?", "nhà mình ở đâu") → bỏ qua TẤT CẢ slot,
            // tránh lưu "gì/đâu/nào" rồi nhại lại thành "sự thật".
            if (looksLikeQuestion(text)) return@withContext
            for ((key, re) in OBSERVE_RES) {
                val raw = firstGroup(re.find(text) ?: continue) ?: continue
                val value = trimValue(key, raw) ?: continue
                db.memoryCore().upsert(MemoryCore(key, value))
            }
        } catch (_: Exception) {}
    }

    /** Xoá 1 lần các row rác đã lọt DB trước bản fix ("gì/đâu/nào", 1 ký tự). */
    private suspend fun purgeJunk() {
        try {
            val dao = db.memoryCore()
            for (row in dao.all()) {
                if (cleanStored(row.value) == null) {
                    try { dao.delete(row.key) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 10.2: trả lời trực tiếp từ memory, null nếu không biết → caller mới gọi LLM.
     * Ví dụ "tôi tên gì" → "Bạn tên là An." (không tốn token).
     */
    suspend fun recallAnswer(rawText: String): String? = withContext(Dispatchers.IO) {
        val text = rawText.trim().lowercase().trimEnd('?', '.', '!', ' ').trim()
        if (text.isEmpty()) return@withContext null
        try {
            purgeJunk()
            val core = db.memoryCore().all().associate { it.key to it.value }
            if (core.isEmpty()) return@withContext null
            // Defense in depth: rác lọt lưới ("gì/đâu/nào") → coi như chưa biết, để LLM
            // nói "chưa rõ" theo luật cứng thay vì nhại "Bạn tên là gì."
            fun v(vararg keys: String): String? =
                keys.firstNotNullOfOrNull { cleanStored(core[it]) }
            when {
                Regex("(tên\\s+(tôi|mình)|tôi\\s+tên|mình\\s+tên|tên\\s+của\\s+(tôi|mình))\\s*(là\\s+gì|gì\\s+vậy|gì\\??$)").containsMatchIn(text)
                    || text in listOf("tôi tên gì", "mình tên gì", "tên tôi là gì") ->
                    v("user_name")?.let { "Bạn tên là $it." }
                Regex("(tôi|mình)\\s+thích\\s+(gì|món\\s+gì|cái\\s+gì)").containsMatchIn(text) ->
                    v("user_like")?.let { "Bạn thích $it." }
                Regex("(nhà\\s+(tôi|mình)\\s+ở\\s+đâu|(tôi|mình)\\s+ở\\s+đâu|quê\\s+(tôi|mình)\\s+ở\\s+đâu)").containsMatchIn(text) ->
                    v("user_home")?.let { "Bạn ở $it." }
                Regex("(sinh\\s+nhật.*(khi\\s+nào|ngày\\s+nào|mấy)|ngày\\s+sinh.*(tôi|mình))").containsMatchIn(text) ->
                    v("user_birthday")?.let { "Sinh nhật bạn là $it." }
                Regex("(tôi|mình)\\s+làm\\s+(nghề\\s+)?gì|nghề\\s+của\\s+(tôi|mình)").containsMatchIn(text) ->
                    v("user_job")?.let { "Bạn làm $it." }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /**
     * Lịch sử hội thoại cho LLM (TASK ngữ cảnh cuộc trò chuyện — bản Android của
     * `chatHistory[]` trong aiEngine.js; file JS đó không tồn tại ở repo này).
     * Sliding window từ bảng chat_log: 6 lượt gần nhất, mỗi tin ≤400 ký tự,
     * tổng ≤2500 ký tự — đủ hiểu câu nối ("bài đó", "còn món kia?") mà không
     * phá guardrail token/latency của voice chat. Trả về oldest-first.
     * Caller lấy TRƯỚC khi logTurn("user") lượt hiện tại để khỏi lặp câu hỏi.
     *
     * @return List(role to text), role = "user" | "assistant"
     */
    suspend fun recentHistory(
        limitTurns: Int = 6,
        perMsgChars: Int = 400,
        totalChars: Int = 2500
    ): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val rows = db.chatLog().recent(limitTurns * 2).asReversed()
            val out = mutableListOf<Pair<String, String>>()
            var total = 0
            for (row in rows) {
                val role = if (row.role == "user") "user" else "assistant"
                val text = row.content.trim().take(perMsgChars).trim()
                if (text.isEmpty()) continue
                if (total + text.length > totalChars) break
                total += text.length
                out += role to text
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    /** Ghi lượt chat (dùng cho 10.3 sync; prune 500 đã có ở BibotApp). */
    suspend fun logTurn(role: String, content: String): Unit = withContext(Dispatchers.IO) {
        try {
            if (content.isNotBlank()) db.chatLog().insert(
                com.bibot.app.data.db.ChatLog(role = role, content = content.take(2000))
            )
        } catch (_: Exception) {}
    }

    /** Dòng context để ghép trước system prompt (dùng khi nối 10.x hoàn chỉnh). */
    suspend fun memoryContextLine(): String = withContext(Dispatchers.IO) {
        try {
            purgeJunk()
            val core = db.memoryCore().all().associate { it.key to it.value }
            if (core.isEmpty()) return@withContext ""
            // Chỉ ghép giá trị sạch; rác → bỏ (kèm luật cứng trong prompt: chỉ dùng khi hỏi trực tiếp).
            val parts = core.entries.mapNotNull { (k, v) ->
                val cv = cleanStored(v) ?: return@mapNotNull null
                when (k) {
                    "user_name" -> "tên=$cv"; "user_like" -> "thích=$cv"
                    "user_dislike" -> "không thích=$cv"; "user_home" -> "ở=$cv"
                    "user_job" -> "nghề=$cv"; "user_birthday" -> "sinh nhật=$cv"
                    else -> "$k=$cv"
                }
            }
            if (parts.isEmpty()) return@withContext ""
            "Ghi nhớ về user: " + parts.joinToString("; ") + "."
        } catch (_: Exception) { "" }
    }
}
