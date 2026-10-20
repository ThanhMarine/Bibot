package com.bibot.app.core.text

import android.content.Context

/**
 * H0/H1/H2 Reading Rules — rulepack `tools/rules/reading-rules.json` (sinh từ `.ruler`,
 * KHÔNG sửa tay). Engine chạy rewrite theo priority + drop_line/start_marker theo dòng
 * + split_guard params cho chunker. Entry reuse duy nhất: `init()` 1 lần ở Application,
 * rồi `applyIfReady/isDropLineReady/isStartMarkerReady/chunkGuard()` ở mọi caller.
 *
 * 0 lib mới (org.json có sẵn). Ngữ nghĩa hook/template Y HỆT `compile_rules.py`:
 * `{N}` nguyên văn | `{day:N}` (1-10 có "mùng") | `{month:N}` | `{year:N}` |
 * `{num:N}` | `{roman:N}` — hook sai → bỏ cả match, giữ nguyên chữ.
 */
object ReadingRules {

    data class Rule(
        val id: String,
        val kind: String,
        val scope: List<String>,
        val lang: List<String>,
        val priority: Int,
        val patterns: List<Regex>,
        val template: String = "",
        /** Tham số flat string cho kind split_guard (không regex) — 0 dep. */
        val params: Map<String, String> = emptyMap()
    )

    /** Guard chunker đọc từ rule split_guard (fallback = hành vi cứng VietnameseChunker). */
    data class ChunkGuard(
        val bracketsOpen: String = "([{",
        val bracketsClose: String = ")]}",
        val quotes: String = "\"“”",
        val citeAbbrev: Set<String> = emptySet(),
        val overrunWords: Int = 8,
        val overrunCharsMult: Int = 2
    )

    @Volatile
    private var cached: List<Rule>? = null

    /** version pack đang dùng (assets hoặc file OTA) — mốc so cho RulesOta.sync. */
    @Volatile
    var loadedVersion: Long = 0
        private set

    /** Nạp 1 lần, cache RAM (JSON v2 ~5KB, trần 30KB). Ưu tiên file OTA trên
     * filesDir (H3.2) nếu version lớn hơn + checksum đúng; hỏng/cũ hơn → xoá, dùng nhúng. */
    fun load(context: Context): List<Rule> {
        cached?.let { return it }
        val rules = try {
            val assets = context.assets.open("reading-rules.json").bufferedReader().use { it.readText() }
            val disk = RulesOta.diskFile(context)
            val diskText = try {
                if (disk.exists()) disk.readText(Charsets.UTF_8) else null
            } catch (_: Exception) { null }
            val text = RulesOta.pickPack(assets, diskText)
            if (diskText != null && text != diskText) {
                try { disk.delete() } catch (_: Exception) {}
            }
            loadedVersion = RulesOta.versionOf(text) ?: 0L
            parse(text)
        } catch (_: Exception) {
            emptyList()
        }
        cached = rules
        return rules
    }

    /** Entry reuse duy nhất: gọi 1 lần ở Application.onCreate (rẻ, ~ms, try/catch). */
    fun init(context: Context): List<Rule> = load(context)

    /** True nếu rulepack đã nạp xong (sẵn sàng reuse). */
    fun isReady(): Boolean = cached != null

    /** Test-only (H2.1): nạp rulepack đã parse (đọc JSON thật, không Context) để
     * cleanForTts/applyIfReady/isDropLineReady chạy được trên JVM thường. */
    fun initForTest(rules: List<Rule>): List<Rule> {
        cached = rules
        return rules
    }

    /** Test-only: trả cache về chưa-init để không rò rỉ giữa các test class. */
    fun resetForTest() {
        cached = null
    }

    /** Rewrite qua ruler nếu đã init, chưa thì trả nguyên (caller chạy code cứng tiếp). */
    fun applyIfReady(text: String, lang: String = "vi"): String {
        val rules = cached ?: return text
        if (text.isEmpty()) return text
        return try { apply(text, lang, rules) } catch (_: Exception) { text }
    }

    /** Bỏ dòng qua ruler nếu đã init, chưa thì null (caller fallback code cứng). */
    fun isDropLineReady(line: String, lang: String = "vi"): Boolean? {
        val rules = cached ?: return null
        return try { isDropLine(line, lang, rules) } catch (_: Exception) { null }
    }

    /** Mốc vào sách qua ruler nếu đã init, chưa thì null (caller fallback code cứng). */
    fun isStartMarkerReady(text: String, lang: String = "vi"): Boolean? {
        val rules = cached ?: return null
        return try { isStartMarker(text, lang, rules) } catch (_: Exception) { null }
    }

    /** Guard chunker từ rule split_guard (chưa init → default hành vi cứng). */
    fun chunkGuard(): ChunkGuard {
        val rules = cached ?: return ChunkGuard()
        val r = rules.firstOrNull { it.kind == "split_guard" } ?: return ChunkGuard()
        val p = r.params
        return ChunkGuard(
            bracketsOpen = p["brackets_open"] ?: "([{",
            bracketsClose = p["brackets_close"] ?: ")]}",
            quotes = p["quotes"] ?: "\"“”",
            citeAbbrev = p["cite_abbrev"]?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
            overrunWords = p["overrun_words"]?.toIntOrNull() ?: 8,
            overrunCharsMult = p["overrun_chars_mult"]?.toIntOrNull() ?: 2
        )
    }

    /**
     * Parse thuần (không cần Context) — để test + preview. KHÔNG dùng org.json
     * (stub Android crash trên JVM thường) mà parser tối thiểu dưới đây — đủ cho
     * schema cố định do compile_rules.py sinh (object/array/string/int), 0 dep.
     */
    fun parse(json: String): List<Rule> {
        val out = ArrayList<Rule>()
        val root = try { MiniJson.parse(json) as? Map<*, *> } catch (_: Exception) { return emptyList() }
            ?: return emptyList()
        val arr = root["rules"] as? List<*> ?: return emptyList()
        for ((i, item) in arr.withIndex()) {
            val o = item as? Map<*, *> ?: continue
            fun str(key: String): String = o[key] as? String ?: ""
            val patterns = ArrayList<Regex>()
            str("pattern").ifBlank { null }?.let {
                try { patterns.add(Regex(it)) } catch (_: Exception) { /* pattern hỏng → bỏ rule */ }
            }
            (o["patterns"] as? List<*>)?.forEach { p ->
                (p as? String)?.let {
                    try { patterns.add(Regex(it)) } catch (_: Exception) { }
                }
            }
            val kind = str("kind")
            // split_guard không có pattern (chỉ params) — vẫn giữ rule.
            if (patterns.isEmpty() && kind != "split_guard") continue
            fun strs(key: String): List<String> =
                ((o[key] as? List<*>) ?: emptyList<Any>())
                    .mapNotNull { it as? String }
            val params = try {
                ((o["params"] as? Map<*, *>) ?: emptyMap<Any, Any>())
                    .mapNotNull { (k, v) ->
                        val ks = k as? String ?: return@mapNotNull null
                        val vs = v as? String ?: return@mapNotNull null
                        ks to vs
                    }.toMap()
            } catch (_: Exception) { emptyMap<String, String>() }
            out.add(
                Rule(
                    id = str("id").ifBlank { "rule-$i" },
                    kind = kind,
                    scope = strs("scope"),
                    lang = strs("lang"),
                    priority = (o["priority"] as? Long)?.toInt() ?: 0,
                    patterns = patterns,
                    template = str("template"),
                    params = params
                )
            )
        }
        return out.sortedWith(compareBy({ it.priority }, { it.id }))
    }

    /**
     * Parser JSON tối thiểu cho schema rulepack (object/array/string/số nguyên/
     * true/false/null). Không thay thế parser tổng quát — chỉ đủ cho file do
     * compile_rules.py sinh. Lỗi cú pháp → IllegalArgumentException.
     */
    internal object MiniJson {
        fun parse(s: String): Any? {
            val p = Parser(s)
            p.ws()
            val v = p.value()
            p.ws()
            if (p.pos != s.length) throw IllegalArgumentException("trailing at ${p.pos}")
            return v
        }

        private class Parser(val s: String) {
            var pos = 0
            fun ws() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }
            fun value(): Any? {
                if (pos >= s.length) throw IllegalArgumentException("eof")
                return when (s[pos]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> str()
                    't' -> lit("true", true)
                    'f' -> lit("false", false)
                    'n' -> lit("null", null)
                    else -> num()
                }
            }
            fun lit(word: String, v: Any?): Any? {
                if (!s.startsWith(word, pos)) throw IllegalArgumentException("bad literal at $pos")
                pos += word.length
                return v
            }
            fun obj(): Map<String, Any?> {
                pos++ // {
                val m = LinkedHashMap<String, Any?>()
                ws()
                if (pos < s.length && s[pos] == '}') { pos++; return m }
                while (true) {
                    ws()
                    if (pos >= s.length || s[pos] != '"') throw IllegalArgumentException("key at $pos")
                    val k = str()
                    ws()
                    if (pos >= s.length || s[pos] != ':') throw IllegalArgumentException("colon at $pos")
                    pos++
                    ws()
                    m[k] = value()
                    ws()
                    if (pos >= s.length) throw IllegalArgumentException("eof in obj")
                    if (s[pos] == '}') { pos++; return m }
                    if (s[pos] != ',') throw IllegalArgumentException("comma at $pos")
                    pos++
                }
            }
            fun arr(): List<Any?> {
                pos++ // [
                val l = ArrayList<Any?>()
                ws()
                if (pos < s.length && s[pos] == ']') { pos++; return l }
                while (true) {
                    ws()
                    l.add(value())
                    ws()
                    if (pos >= s.length) throw IllegalArgumentException("eof in arr")
                    if (s[pos] == ']') { pos++; return l }
                    if (s[pos] != ',') throw IllegalArgumentException("comma at $pos")
                    pos++
                }
            }
            fun str(): String {
                pos++ // "
                val sb = StringBuilder()
                while (true) {
                    if (pos >= s.length) throw IllegalArgumentException("eof in str")
                    val c = s[pos++]
                    if (c == '"') return sb.toString()
                    if (c != '\\') { sb.append(c); continue }
                    if (pos >= s.length) throw IllegalArgumentException("eof in esc")
                    when (val e = s[pos++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) throw IllegalArgumentException("bad \\u at $pos")
                            var cp = s.substring(pos, pos + 4).toInt(16)
                            pos += 4
                            // Cặp surrogate UTF-16 → 1 codepoint.
                            if (cp in 0xD800..0xDBFF && pos + 6 <= s.length &&
                                s[pos] == '\\' && s[pos + 1] == 'u'
                            ) {
                                val lo = s.substring(pos + 2, pos + 6).toInt(16)
                                if (lo in 0xDC00..0xDFFF) {
                                    cp = 0x10000 + ((cp - 0xD800) shl 10) + (lo - 0xDC00)
                                    pos += 6
                                }
                            }
                            sb.appendCodePoint(cp)
                        }
                        else -> throw IllegalArgumentException("bad escape \\$e")
                    }
                }
            }
            fun num(): Long {
                val start = pos
                if (pos < s.length && s[pos] == '-') pos++
                while (pos < s.length && s[pos].isDigit()) pos++
                if (pos < s.length && s[pos] == '.') {
                    pos++
                    while (pos < s.length && s[pos].isDigit()) pos++
                }
                if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                    pos++
                    if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                    while (pos < s.length && s[pos].isDigit()) pos++
                }
                val raw = s.substring(start, pos)
                // Schema chỉ dùng số nguyên (version/priority) — phần thập phân cắt bỏ.
                return raw.substringBefore('.').substringBefore('e').substringBefore('E')
                    .toLongOrNull() ?: throw IllegalArgumentException("bad num $raw")
            }
        }

        private fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
            if (cp < 0x10000) { append(cp.toChar()); return this }
            var v = cp - 0x10000
            val lo = (v and 0x3FF) + 0xDC00
            val hi = (v ushr 10) + 0xD800
            append(hi.toChar())
            append(lo.toChar())
            return this
        }
    }

    private fun matchLang(r: Rule, lang: String) = r.lang.isEmpty() || lang in r.lang

    /** Chạy rewrite rules (scope tts) theo priority — thay `cleanForTts` lẻ tẻ (H2). */
    fun apply(text: String, lang: String = "vi", rules: List<Rule>): String {
        var t = text
        for (r in rules) {
            if (r.kind != "rewrite" || !matchLang(r, lang)) continue
            val rx = r.patterns.firstOrNull() ?: continue
            t = rx.replace(t) { m -> applyTemplate(r.template, m) ?: m.value }
        }
        return t
    }

    /** True nếu dòng bị bỏ (scope segment) — thay `isDroppableLine` lẻ tẻ (H2). */
    fun isDropLine(line: String, lang: String = "vi", rules: List<Rule>): Boolean {
        for (r in rules) {
            if (r.kind != "drop_line" || !matchLang(r, lang)) continue
            if (r.patterns.any { it.containsMatchIn(line) }) return true
        }
        return false
    }

    /** True nếu là mốc bắt đầu đọc (H2: thay `isReadingStartMarker`). */
    fun isStartMarker(text: String, lang: String = "vi", rules: List<Rule>): Boolean {
        for (r in rules) {
            if (r.kind != "start_marker" || !matchLang(r, lang)) continue
            if (r.patterns.any { it.containsMatchIn(text) }) return true
        }
        return false
    }

    private val phRegex = Regex("""\{([a-z]+:)?(\d+)\}""")

    internal fun applyTemplate(template: String, m: MatchResult): String? {
        val sb = StringBuilder()
        var pos = 0
        for (ph in phRegex.findAll(template)) {
            sb.append(template, pos, ph.range.first)
            val idx = ph.groupValues[2].toIntOrNull() ?: return null
            if (idx < 1 || idx > m.groupValues.size - 1) return null
            val raw = m.groupValues[idx]
            val name = ph.groupValues[1].removeSuffix(":").ifBlank { null }
            sb.append(hook(name, raw) ?: return null)
            pos = ph.range.last + 1
        }
        sb.append(template, pos, template.length)
        return sb.toString()
    }

    /** Y hệt hook Python/Go — null = bỏ match. Dùng TextCleaner sẵn có để khỏi lệch. */
    internal fun hook(name: String?, raw: String): String? {
        if (name == null) return raw
        val t = raw.trim()
        return when (name) {
            "day" -> {
                val d = t.toIntOrNull() ?: return null
                if (d !in 1..31) return null
                val w = TextCleaner.numberToVietnameseWords(d.toString())
                if (d in 1..10) "ngày mùng $w" else "ngày $w"
            }
            "month" -> {
                val v = t.toIntOrNull() ?: return null
                if (v !in 1..12) return null
                TextCleaner.numberToVietnameseWords(v.toString())
            }
            "year" -> {
                val v = t.toIntOrNull() ?: return null
                val y = when (t.length) {
                    2 -> 2000 + v
                    4 -> if (v in 1000..2999) v else return null
                    else -> return null
                }
                TextCleaner.numberToVietnameseWords(y.toString())
            }
            "num" -> {
                val v = t.toIntOrNull() ?: return null
                TextCleaner.numberToVietnameseWords(v.toString())
            }
            "roman" -> {
                val v = TextCleaner.romanToInt(t) ?: return null
                TextCleaner.numberToVietnameseWords(v.toString())
            }
            else -> null
        }
    }
}
