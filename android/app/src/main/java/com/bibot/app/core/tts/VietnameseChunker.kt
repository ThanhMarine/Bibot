package com.bibot.app.core.tts

/**
 * Vietnamese chunker — yields chunks sized ~35-40 words, rounded to nearest . or ,.
 * Only ends audio chunk when encountering . or ./n for natural pausing.
 */
class VietnameseChunker(
    private val minWords: Int = 4,
    private val maxWords: Int = 40,
    private val maxChars: Int = 200,
    // P1 chat (blueprint TASK 2): ngắt cả ở , ; : để chunk ngắn → audio ra sớm.
    // Story/Book giữ false (chỉ ngắt câu . ! ?) — ngữ điệu kể chuyện mượt hơn.
    private val yieldOnClause: Boolean = false
) {
    private val buffer = StringBuilder()

    private val singleWords = setOf(
        "là","của","cho","với","tại","từ","đến","về","trên","dưới",
        "trong","ngoài","sau","trước","giữa","bên","cạnh","gần","xa",
        "một","hai","ba","bốn","năm","sáu","bảy","tám","chín","mười",
        "cái","con","chiếc","người","này","kia","đó","nọ",
        "tôi","bạn","anh","chị","em","chúng","họ","ta","mình"
    )

    private fun wordCount(s: String) = s.trim().split(WS).filter { it.isNotEmpty() }.size

    private fun shouldYield(chunk: String, wc: Int): Boolean {
        val t = chunk.trim()
        if (t.isEmpty()) return false
        val words = t.split(WS)
        val lastW = words.lastOrNull()?.trimEnd('.', ',', '!', '?', ';', ':')?.lowercase() ?: ""
        val endsSentence = SENT_END.containsMatchIn(t)
        if (DECIMAL_END.containsMatchIn(t)) {
            if (wc < maxWords + 4) return false
        }
        if ((lastW in singleWords || lastW.all { it.isDigit() }) && !endsSentence) {
            if (wc < maxWords + 4 && t.length < (maxChars * 1.5).toInt()) return false
        }
        // P1: yield ở dấu phẩy/chấm phẩy (chỉ khi bật yieldOnClause — voice chat).
        // Không cắt ngay sau chữ số ("có 3," → chờ "3 người,")
        if (yieldOnClause && !endsSentence && CLAUSE_END.containsMatchIn(t) &&
            !DIGIT_CLAUSE_END.containsMatchIn(t) && wc >= minWords
        ) return true
        // Only yield at sentence end (.) for natural pausing
        if (endsSentence) return wc >= minWords
        // Yield at newline for chapter/section breaks
        if (t.endsWith("\\n") || t.endsWith("\n")) return wc >= minWords
        if (wc >= minWords) {
            if (wc >= maxWords || t.length >= maxChars) return true
        }
        return false
    }

    fun feed(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        buffer.append(text)
        val full = buffer.toString()
        // split by delimiters keeping them — DELIM cache sẵn (P0: tránh Pattern.compile mỗi feed)
        val pattern = DELIM
        val parts = mutableListOf<Pair<Boolean,String>>()
        var pos = 0
        for (m in pattern.findAll(full)) {
            val s = m.range.first
            val e = m.range.last + 1
            if (s > pos) parts += false to full.substring(pos, s)
            parts += true to full.substring(s, e)
            pos = e
        }
        if (pos < full.length) parts += false to full.substring(pos)

        val out = mutableListOf<String>()
        var cur = ""
        for ((isDel, txt) in parts) {
            if (txt.isEmpty()) continue
            if (isDel) {
                cur += txt
                if (shouldYield(cur, wordCount(cur))) {
                    out += cur.trim()
                    cur = ""
                }
            } else {
                val test = cur + txt
                if (cur.isNotEmpty() && wordCount(test) > maxWords) {
                    out += cur.trim()
                    cur = txt
                } else {
                    cur = test
                }
            }
        }
        buffer.clear()
        buffer.append(cur)
        return out
    }

    fun flush(): List<String> {
        val tail = buffer.toString().trim()
        buffer.clear()
        if (tail.isEmpty() || !WORD_CHAR.containsMatchIn(tail)) return emptyList()
        return listOf(tail)
    }

    fun reset() { buffer.clear() }

    companion object {
        // P0: cache Pattern — trước đây mỗi wordCount/shouldYield compile Regex MỚI
        // (file 3.5MB → hàng trăm nghìn lần compile → 10-20s CPU).
        private val WS = Regex("\\s+")
        private val SENT_END = Regex("[.!?…]\\s*$")
        private val DECIMAL_END = Regex("""\d\.\s*$""")
        private val WORD_CHAR = Regex("""\w""")
        private val CLAUSE_END = Regex("""[,;:、]\s*$""")
        private val DIGIT_CLAUSE_END = Regex("""\d[,;:]\s*$""")
        private val DELIM = Regex("([.!?…]|[;:,、]|\\s+(?:và|hoặc|nhưng|mà|tuy nhiên|do đó|vì vậy|nên|khi|nếu|khi mà)\\s+)")

        fun split(text: String): List<String> {
            val c = VietnameseChunker()
            val r = mutableListOf<String>()
            r += c.feed(text)
            r += c.flush()
            return r
        }
    }
}
