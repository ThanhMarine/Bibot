package com.bibot.app.core.text

import android.content.Context
import org.json.JSONObject

/**
 * H0/H1 Reading Rules — rulepack `tools/rules/reading-rules.json` (sinh từ `.ruler`,
 * KHÔNG sửa tay). Engine chạy rewrite theo priority + drop_line/start_marker theo dòng.
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
        val template: String = ""
    )

    @Volatile
    private var cached: List<Rule>? = null

    /** Nạp 1 lần từ assets, cache RAM (JSON pilot ~1KB, trần 30KB). */
    fun load(context: Context): List<Rule> {
        cached?.let { return it }
        val rules = try {
            val text = context.assets.open("reading-rules.json").bufferedReader().use { it.readText() }
            parse(text)
        } catch (_: Exception) {
            emptyList()
        }
        cached = rules
        return rules
    }

    /** Parse thuần (không cần Context) — để test + preview. */
    fun parse(json: String): List<Rule> {
        val out = ArrayList<Rule>()
        val arr = JSONObject(json).optJSONArray("rules") ?: return emptyList()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val patterns = ArrayList<Regex>()
            o.optString("pattern", "").ifBlank { null }?.let {
                try { patterns.add(Regex(it)) } catch (_: Exception) { /* pattern hỏng → bỏ rule */ }
            }
            val pa = o.optJSONArray("patterns")
            if (pa != null) {
                for (j in 0 until pa.length()) {
                    try { patterns.add(Regex(pa.getString(j))) } catch (_: Exception) { }
                }
            }
            if (patterns.isEmpty()) continue
            fun strs(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return (0 until a.length()).mapNotNull { runCatching { a.getString(it) }.getOrNull() }
            }
            out.add(
                Rule(
                    id = o.optString("id", "rule-$i"),
                    kind = o.optString("kind", ""),
                    scope = strs("scope"),
                    lang = strs("lang"),
                    priority = o.optInt("priority", 0),
                    patterns = patterns,
                    template = o.optString("template", "")
                )
            )
        }
        return out.sortedWith(compareBy({ it.priority }, { it.id }))
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
