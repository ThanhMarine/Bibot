package com.bibot.app.core.text

import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

/**
 * Port 1-1 Pi streaming_llm.py:143 clean_tts_text + text_cleaner.py
 * Dùng TRƯỚC mọi chunker/TTS (2.5). Đã có Go tts.CleanTTSText nhưng Kotlin cần bản native để không phụ thuộc AAR.
 */
object TextCleaner {
    private val thinkingRegex = Regex("""(?s)<think>.*?</think>""")
    private val mdLinkRegex = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val urlRegex = Regex("""(?i)(?:https?|ftp)://[^\s/$.?#].[^\s]*|www\.[^\s/$.?#].[^\s]*|[a-z0-9-]+\.(?:com|vn|org|net|edu|gov|io|co|info|biz|me|xyz|top|site|online|tech|app|dev|ai)[^\s]*""")
    private val danglingOpen = Regex("""\[[^\]]*$""")
    private val danglingMid = Regex("""\]\([^)]*$""")
    private val danglingParen = Regex("""\([^)]*https?[^)]*$""")
    private val markdownBold = Regex("""\*\*|\*|__|```|`""")
    private val mdImageRegex = Regex("""!\[[^\]]*\]\([^)]*\)|!\[[^\]]*\]""")
    private val headerRegex = Regex("""(?m)^[ \t]*#+[ \t]*""")
    // Sau khi cleanPdfText gộp dòng → "#" nằm giữa câu, không còn ở ^ => cần bản inline
    private val headerInlineRegex = Regex("""(?<=\s)#+[ \t]*""")
    private val bulletRegex = Regex("""(?m)^[ \t]*[-*+][ \t]+""")
    private val quoteRegex = Regex("""(?m)^[ \t]*>[ \t]*""")
    // Dòng phân cách bảng `|---|---|` → bỏ hẳn (nếu không `---` thành PAUSE2SEC oan)
    private val tableSepRegex = Regex("""(?m)^[ \t]*\|(?:[ \t]*:?-{3,}:?[ \t]*\|)+[ \t]*$""")
    private val spacedHyphenRegex = Regex("""(?<=\s)-(?=\s)""")
    private val emptyParen = Regex("""\(\s*\)""")
    private val emojiRegex = Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]+|[\u2600-\u27BF]|[\u2300-\u23FF]""")
    private val blankRegex = Regex("""([_]{3,}|[-–—]{3,}|\.{3,}|_{3,})""")
    // Đổi từ __PAUSE2S__ (Pi) sang PAUSE2SEC: "__" bị stripMarkdown ăn mất (markdownBold),
    // còn token chữ thường sống sót qua emoji/space-compress để consumer nhận biết mà pause.
    const val PAUSE_MARKER = "PAUSE2SEC"

    // Port Go isThinkingLeak (llm.go:377) — leaders của reasoning leak
    private val thinkingLeaders = listOf(
        "need under", "need maybe", "under 30", "too long", "shorten",
        "no markdown", "use punctuation", "great.", "count "
    )

    /** true nếu chunk là reasoning leak ("Need under 30.", "Count Giao1…", "<think>…") → drop trước TTS. */
    fun looksLikeThinking(s: String): Boolean {
        val low = s.trim().lowercase()
        if (low.isEmpty()) return false
        if (low.startsWith("<think")) return true
        if (thinkingLeaders.any { low.startsWith(it) }) return true
        // Kiểu "Giao1 thông2": chữ dính số lặp ≥2 lần trong chunk ngắn
        if (low.length < 80 && Regex("[a-zà-ỹ]+\\d+").findAll(low).count() >= 2) return true
        return false
    }

    /** Tách marker pause ra khỏi câu đã clean → (text sạch, có cần pause 2s không). */
    fun splitPause(cleanText: String): Pair<String, Boolean> {
        if (!cleanText.contains(PAUSE_MARKER)) return cleanText to false
        return cleanText.replace(PAUSE_MARKER, " ")
            .replace(Regex("""\s+"""), " ").trim() to true
    }

    /**
     * Xóa marker PAUSE2SEC — lớp phòng thủ cuối trước mọi engine TTS
     * (Android TTS/Piper sẽ đọc nguyên "PAUSE 2 SEC", Cloud đọc "PAUSE2SEC").
     * Khoảng lặng 2s do caller lo qua `splitPause(...).second` → delay.
     */
    fun stripPauseMarker(s: String): String {
        if (s.indexOf(PAUSE_MARKER) < 0) return s
        return s.replace(PAUSE_MARKER, " ").replace(Regex("""\s+"""), " ").trim()
    }

    private val abbrevDotRegex = Regex("""(?<=[A-Za-zÀ-ỹà-ỹ])\.(?=[A-Za-zÀ-ỹà-ỹ])""")
    private val numDotRegex = Regex("""(?<=\d)\.(?=\d)""")
    private val thousandCommaRegex = Regex("""(?<=\d),(?=\d{3}(?!\d))""")
    private val numCommaRegex = Regex("""(?<=\d),(?=\d)""")
    private val dashBetweenNumRegex = Regex("""(?<=\d)\s*[–—-]\s*(?=\d)""")

    private val VI_U = "A-ZÀÁẠẢÃĂẰẮẶẲẴẦẤẬẨẪÈÉẸẺẼỀẾỆỂỄÌÍỊỈĨÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠÙÚỤỦŨỪỨỰỬỮỲÝỴỶỸĐ"
    private val VI_L = "a-zàáạảãăằắặẳẵầấậẩẫèéẹẻẽềếệểễìíịỉĩòóọỏõồốộổỗơờớợởỡùúụủũừứựửữỳýỵỷỹđ"
    // Chữ đơn đứng riêng (điểm/vitamin/nhóm máu/tia): không dán với từ sau
    private const val SOLO_TOKEN_LETTERS = "ABCDEFKOXY"
    private val splitUpperLowerRegex = Regex("""\b([$VI_U]) ([$VI_L])""")

    fun isVietnameseLetter(c: Char): Boolean {
        if (c in 'A'..'Z' || c in 'a'..'z') return true
        if (c == 'Đ' || c == 'đ') return true
        val code = c.code
        if (code in 0xC0..0x1EF9) return true
        if (code in 0x100..0x17F) return true
        return false
    }

    fun removeDigitGluedToLetter(s: String): String {
        val runes = s.toCharArray()
        val sb = StringBuilder()
        var i = 0
        while (i < runes.size) {
            val ch = runes[i]
            if (ch.isDigit() && i > 0 && isVietnameseLetter(runes[i - 1])) {
                if ((runes[i - 1] == 'x' || runes[i - 1] == 'X') && i >= 2 && runes[i - 2].isDigit()) {
                    sb.append(ch); i++; continue
                }
                var j = i
                while (j < runes.size && runes[j].isDigit()) j++
                val next = if (j < runes.size) runes[j] else '\u0000'
                if (j >= runes.size || next.isWhitespace() || next in ".,!?;:") {
                    i = j; continue
                }
            }
            sb.append(ch); i++
        }
        return sb.toString()
    }

    fun stripMarkdownLink(s: String): String {
        return mdLinkRegex.replace(s) { m ->
            val label = m.groupValues[1]
            if (urlRegex.containsMatchIn(label)) return@replace ""
            if ('.' in label && ' ' !in label.trim()) return@replace ""
            label
        }
    }

    fun normalizeBlanks(s: String): String {
        if (s.isEmpty()) return s
        return blankRegex.replace(s, " $PAUSE_MARKER ")
    }

    // ------------------------------------------------------------------
    // HTML / markup thừa trong sách convert (EPUB → MD, PDF → MD)
    // VD rác thật: `<!-- CHAPTER-BREAK index=6 title="..." pages=8-10 -->`,
    // `<a id="page-8"></a>`, `&nbsp;`, `|---|---|`.
    // ------------------------------------------------------------------
    private val htmlEntityRegex = Regex("""&(#[xX][0-9a-fA-F]{1,6}|#\d{1,7}|[a-zA-Z][a-zA-Z0-9]{1,12});""")
    private val htmlCommentRegex = Regex("""(?s)<!--.*?-->""")
    // Comment/tag bị CẮT giữa khối 16KB (không thấy `-->`/`>`) → bỏ nốt phần còn lại,
    // nếu không TTS sẽ đọc nguyên `<!-- CHAPTER-BREAK index bằng 6 ...`
    private val htmlCommentOpenRegex = Regex("""(?s)<!--.*$""")
    private val htmlTagRegex = Regex("""</?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?/?>""")
    private val htmlTagOpenRegex = Regex("""(?s)</?[A-Za-z][A-Za-z0-9-]*(?:\s[^<>]*)?$""")
    private val htmlEntities = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"",
        "apos" to "'", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "lsquo" to "'", "rsquo" to "'", "ldquo" to "\"", "rdquo" to "\"",
        "copy" to "©", "reg" to "®", "trade" to "™", "middot" to "·", "bull" to " "
    )

    private fun decodeEntity(name: String): String {
        if (name.startsWith("#")) {
            val cp = if (name.length > 1 && (name[1] == 'x' || name[1] == 'X')) name.substring(2).toIntOrNull(16)
            else name.substring(1).toIntOrNull()
            if (cp != null && cp in 32..0x10FFFF && cp !in 0xD800..0xDFFF) return String(Character.toChars(cp))
            return " "
        }
        return htmlEntities[name.lowercase()] ?: " "
    }

    /**
     * Bỏ HTML/markup thừa trước mọi regex khác. Idempotent.
     * Thứ tự: entity → comment → tag (entity trước để `&lt;a&gt;` cũng bị tẩy).
     */
    fun stripHtml(s: String): String {
        if (s.isEmpty()) return s
        if ('<' !in s && '&' !in s) return s
        var t = htmlEntityRegex.replace(s) { m -> decodeEntity(m.groupValues[1]) }
        t = htmlCommentRegex.replace(t, " ")
        t = htmlCommentOpenRegex.replace(t, " ")
        t = htmlTagRegex.replace(t, " ")
        t = htmlTagOpenRegex.replace(t, " ")
        return t
    }

    /**
     * Bỏ markup theo DÒNG (chạy TRƯỚC khi `cleanPdfText` gộp dòng, nên `^` còn tác dụng):
     * bảng `| a | b |` (kể cả `|---|---|` → tránh thành pause oan), blockquote `>`,
     * bullet `- `, heading `# `. Idempotent, chạy lại trong `cleanForTts` cho lời gọi trực tiếp.
     */
    fun stripMarkup(s: String): String {
        if (s.isEmpty()) return s
        if ('|' !in s && '>' !in s && '#' !in s && '\n' !in s &&
            '-' !in s && '+' !in s && '*' !in s
        ) return s
        var t = tableSepRegex.replace(s, " ")   // bỏ dòng `|---|---|` (giữ nguyên ô khác)
        t = t.replace("|", " ")                  // cột → khoảng trắng, GIỮ nội dung ô
        t = quoteRegex.replace(t, "")
        t = bulletRegex.replace(t, "")
        t = headerRegex.replace(t, "")
        // "#" giữa câu (sau khi gộp dòng): "…page-8"></a> # Truyện Cây tỉnh" → "… Truyện Cây tỉnh"
        t = headerInlineRegex.replace(t, " ")
        return t
    }

    fun stripMarkdown(s: String): String {
        var t = s
        t = mdImageRegex.replace(t, "")
        t = markdownBold.replace(t, "")
        t = headerRegex.replace(t, "")
        t = headerInlineRegex.replace(t, " ")
        t = bulletRegex.replace(t, "")
        // Gạch nối đứng riêng giữa 2 từ (bullet đã gộp dòng) — số–số đã đổi "đến" ở bước trên
        t = spacedHyphenRegex.replace(t, " ")
        t = emptyParen.replace(t, "")
        return t
    }

    /**
     * Làm sạch cho HIỂN THỊ nguyên bản (chế độ đọc gốc .pdf/.epub + .md/.txt):
     * giữ nguyên chữ/số/dấu câu (—, …, dấu chấm TOC, số trang), chỉ bỏ thứ không
     * phải nội dung: tag/comment HTML, entities decode, ký tự điều khiển, TCVN3
     * (PDF cũ) và chuẩn hóa khoảng trắng (giữ \n cấu trúc dòng).
     * KHÔNG drop dòng, KHÔNG verbalize (ngày/số/La Mã/Fig), KHÔNG dash→phẩy —
     * những cái đó thuộc đường TTS ([cleanForTts], speak-time).
     */
    fun cleanForDisplay(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw
        t = PdfTextCleaner.convertTcvn3VniToUtf8(t)
        t = stripHtml(t)
        // Ký tự điều khiển (trừ \n \t) — rác giải mã font PDF.
        t = t.filter { c -> c == '\n' || c == '\t' || c >= ' ' }
        // Gộp khoảng trắng trong dòng (giữ \n), gộp 3+ xuống dòng → 2, trim.
        t = Regex("""[ \t\u00A0\u2000-\u200B\u3000]+""").replace(t, " ")
        t = Regex("""\n[ \t]*\n[ \t]*\n+""").replace(t, "\n\n")
        t = t.lines().joinToString("\n") { it.trimEnd() }.trim()
        return t
    }

    fun cleanForTts(raw: String, lang: String = "vi"): String {        if (raw.isBlank()) return ""
        var text = raw
        // 0. HTML/markup thừa (comment CHAPTER-BREAK, <a id=...>, &nbsp;, bảng, # heading)
        //    phải chạy TRƯỚC mọi regex khác — nếu không `=` trong comment bị đổi thành "bằng".
        text = stripHtml(text)
        text = stripMarkup(text)
        // 1. markdown link giữ label thường, xóa nếu label là URL/domain
        text = stripMarkdownLink(text)
        // 2. URL trần + domain trần
        text = urlRegex.replace(text, " ")
        // 3. mảnh link dở
        text = danglingOpen.replace(text, "")
        text = danglingMid.replace(text, "")
        text = danglingParen.replace(text, "")
        // 3a. ngày/độ/La Mã/Fig-No — H2.1 (verify máy thật 2026-10-14, parity log
        // BibotDbg trên ART): rulepack là đường duy nhất; hàm cứng đã xóa.
        // readPageNumbers GIỮ code (cần lookahead, ngoài giao 3 engine).
        // Quy ước giữ nguyên: không-năm chỉ nhận "/" (31/12 = ngày);
        // "-" không năm là khoảng ("100-120" → "đến"); "1.440"/"12:30" thoát;
        // "Fig./No." bắt buộc có chấm; La Mã chỉ sau từ khóa.
        text = ReadingRules.applyIfReady(text, lang)
        // 3b. số trang → đọc thành chữ: "page/Trang 456" → "trang bốn trăm năm mươi sáu".
        // Chạy sớm, chỉ cụm có từ khóa page/trang nên không động vào 1.440/3,5/2x3.
        text = readPageNumbers(text)
        // 4. số dính chữ (reasoning leak)
        text = removeDigitGluedToLetter(text)
        // 5. viết tắt/số
        text = abbrevDotRegex.replace(text, " ")
        text = numDotRegex.replace(text, "")
        text = thousandCommaRegex.replace(text, "")
        // 3,5 → 3 phẩy 5  (phải sau thousand)
        text = numCommaRegex.replace(text, " phẩy ")
        // 100-120 → 100 đến 120, rồi – — → ,
        text = dashBetweenNumRegex.replace(text, " đến ")
        text = text.replace("–", ",").replace("—", ",")
        // 6. toán tử
        text = text.replace("×", " nhân ")
        text = text.replace(Regex("""(?<=\d)\s*x\s*(?=\d)"""), " nhân ")
        text = text.replace("=", " bằng ")
        text = text.replace("%", " phần trăm ")
        text = text.replace("/", " ")
        // 7. blanks → pause marker (giữ để audio_stream silence 2s)
        text = normalizeBlanks(text)
        // 8. vá chữ chẻ
        // "M èo" → "Mèo" (PDF kern) — NHƯNG giữ token chữ đơn đứng riêng:
        // "vitamin C hôm nay", "tia X quang", "điểm B cần" mà dán thành
        // "Chôm"/"Xquang" là sai. A-F/K/O/X/Y + chữ thường sau hầu như luôn là token.
        text = splitUpperLowerRegex.replace(text) { m ->
            val a = m.groupValues[1]
            if (a.length == 1 && a[0] in SOLO_TOKEN_LETTERS) m.value
            else a + m.groupValues[2]
        }
        text = Regex("""\b([$VI_U]) ([$VI_U]{1,3})\b""").replace(text, "$1$2")
        // 9. markdown/emoji
        text = stripMarkdown(text)
        text = emojiRegex.replace(text, "")
        // 10. thinking
        text = thinkingRegex.replace(text, " ")
        // 11. dấu câu dính space + nén
        text = Regex("""\s+([.,!?;:])""").replace(text, "$1")
        text = Regex("""\s+""").replace(text, " ")
        return text.trim()
    }

    // "page/Trang N" → "trang <đọc thành chữ>". Bỏ qua khoảng số (100-120),
    // số thập phân (4.5) và số dính chữ — chỉ số nguyên đứng riêng sau từ khóa.
    private val pageNumRegex =
        Regex("""(?i)\b(pages?|trang)\s+(\d{1,9})(?![\d.,])(?!\s*[-–—]\s*\d)""")

    fun readPageNumbers(s: String): String {
        if (s.isEmpty()) return s
        return pageNumRegex.replace(s) { m ->
            val digits = m.groupValues[2].trimStart('0')
            val words = if (digits.isEmpty()) "không" else numberToVietnameseWords(digits)
            "trang $words"
        }
    }

    // H2.1: readDates/normalizeDegrees/readRomanNumerals/readFigureRefs + regex đã xóa
    // (verify máy thật 2026-10-14: ruler cho output trùng trên ART). Logic sống trong
    // rulepack (tools/rules/reading-rules.ruler) + hook "roman/day/month/year/num"
    // (ReadingRules.kt) dùng chung romanToInt/numberToVietnameseWords dưới đây (GIỮ).
    // validRomanRegex phục vụ romanToInt (hook ruler) nên giữ lại.
    private val validRomanRegex =
        Regex("""^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$""")

    // D6: nhóm viết tắt trích dẫn — "Fig."/"No."/"St."... + "." cuối chunk KHÔNG phải
    // hết câu (kẻo chunker chẻ "(Fig." | "5)" và TTS ngắt giữa trích dẫn).
    private val citeAbbrevWords = setOf(
        "fig", "no", "st", "mr", "mrs", "ms", "dr", "prof", "vs",
        "etc", "ie", "eg", "am", "pm", "tr", "tp", "nxb",
        "p", "pp", "ch", "sec", "vol", "ed"
    )

    /** True nếu chunk kết bằng viết tắt trích dẫn + "." (vd "xem minh họa (Fig."). */
    fun endsWithCiteAbbrev(chunk: String): Boolean = endsWithCiteAbbrev(chunk, citeAbbrevWords)

    /** H2.3: cùng check trên với tập abbrev từ ruler (`chunkGuard().citeAbbrev`). */
    fun endsWithCiteAbbrev(chunk: String, abbrev: Set<String>): Boolean {
        val t = chunk.trimEnd()
        if (!t.endsWith('.')) return false
        val w = t.dropLast(1).substringAfterLast(' ').substringAfterLast('(')
            .substringAfterLast('[').substringAfterLast('{').substringAfterLast('"')
            .substringAfterLast('“').trim().lowercase()
        return w in abbrev
    }

    /** Roman hợp lệ → 1..3999; phi chuẩn thường gặp trong sách cũ (IIX=8) → map cứu; còn lại null. */
    private val nonstandardRoman = mapOf("IIX" to 8)

    fun romanToInt(s: String): Int? {
        val u = s.uppercase()
        if (u.isEmpty()) return null
        if (!validRomanRegex.matches(u)) return nonstandardRoman[u]
        val vals = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        var prev = 0
        for (c in u.reversed()) {
            val v = vals[c] ?: return null
            if (v < prev) total -= v else {
                total += v
                prev = v
            }
        }
        return if (total in 1..3999) total else null
    }

    private val VI_DIGITS = arrayOf(
        "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
    )

    /** Đọc số nguyên (dạng chuỗi chữ số, ≤9 chữ số) thành tiếng Việt chuẩn. */
    fun numberToVietnameseWords(digits: String): String {
        val t = digits.trimStart('0')
        if (t.isEmpty()) return "không"
        if (t.length > 9) {
            // Quá lớn: đọc rời từng chữ số để TTS không nuốt số
            return t.map { VI_DIGITS[it - '0'] }.joinToString(" ")
        }
        var n = t.toLong()
        val groups = mutableListOf<Int>()
        while (n > 0) {
            groups.add((n % 1000).toInt())
            n /= 1000
        }
        // groups[0]=đơn vị, [1]=nghìn, [2]=triệu
        val scales = arrayOf("", "nghìn", "triệu")
        val out = StringBuilder()
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            if (out.isNotEmpty()) out.append(' ')
            // Nhóm thấp <100 sau nhóm cao hơn: "lẻ" khi <10 (1005→lẻ năm), đọc gọn khi 10-99
            if (i < groups.size - 1 && groups.subList(i + 1, groups.size).any { it != 0 } && g < 100) {
                if (g < 10) out.append("lẻ ").append(VI_DIGITS[g])
                else out.append(readUnder100(g))
            } else {
                out.append(readUnder1000(g))
            }
            if (scales[i].isNotEmpty()) out.append(' ').append(scales[i])
        }
        return out.toString()
    }

    private fun readUnder100(n: Int): String {
        require(n in 1..99)
        val t = n / 10
        val u = n % 10
        if (t == 0) return VI_DIGITS[u]
        val tens = if (t == 1) "mười" else VI_DIGITS[t] + " mươi"
        if (u == 0) return tens
        return tens + when (u) {
            1 -> " mốt"
            4 -> " tư"
            5 -> " lăm"
            else -> " " + VI_DIGITS[u]
        }
    }

    private fun readUnder1000(g: Int): String {
        require(g in 1..999)
        val h = g / 100
        val rest = g % 100
        if (h == 0) return readUnder100(g)
        var s = VI_DIGITS[h] + " trăm"
        if (rest == 0) return s
        // 101→lẻ một, 105→lẻ năm (sau "lẻ" giữ "năm", không "lăm")
        s += if (rest < 10) " lẻ " + VI_DIGITS[rest] else " " + readUnder100(rest)
        return s
    }

    // Alias cho HybridTtsManager/TextCleanerForTts cũ
    fun clean(s: String): String = cleanForTts(s)

    // =====================================================================
    // A2.4: Hitbox + SearchWindow ±7 từ — đơn vị vạn năng cho highlight/bookmark/
    // TTS word-sync/search preview/tap-to-define. Thay thế position=chap*100+sent.
    // Clone orebridge/StSearchUtils.h: lưu trữ span (start, end) theo word index.
    // =====================================================================

    /**
     * Hitbox: một khoảng liên tục trong text (word indices).
     * Dùng cho: highlight tìm kiếm, bookmark, TTS karaoke sync, tap-to-define.
     * start/end là chỉ số TỪ (word index) trong mảng sentences/words.
     */
    data class Hitbox(
        val start: Int,      // word index bắt đầu (inclusive)
        val end: Int,        // word index kết thúc (exclusive)
        val chapterIdx: Int = -1, // chapter chứa hitbox (-1 nếu không áp dụng)
        val sentenceIdx: Int = -1 // sentence trong chapter (-1 nếu không áp dụng)
    ) {
        val length: Int get() = end - start
        val isValid: Boolean get() = start >= 0 && end >= start
    }

    /**
     * SearchWindow: mở rộng Hitbox ra ±N từ (mặc định ±7) để hiển thị context.
     * Dùng cho: search preview, tap-to-define, TTS word-sync visualization.
     */
    data class SearchWindow(
        val hitbox: Hitbox,
        val contextBefore: Int = 7,
        val contextAfter: Int = 7,
        val totalWords: Int = 0 // tổng từ trong chapter/document
    ) {
        val windowStart: Int
            get() = max(0, hitbox.start - contextBefore)
        val windowEnd: Int
            get() = min(totalWords, hitbox.end + contextAfter)

        /**
         * Tạo window với context mặc định ±7 từ.
         */
        companion object {
            const val DEFAULT_CONTEXT = 7

            fun fromHitbox(hitbox: Hitbox, totalWords: Int): SearchWindow {
                return SearchWindow(
                    hitbox = hitbox,
                    contextBefore = DEFAULT_CONTEXT,
                    contextAfter = DEFAULT_CONTEXT,
                    totalWords = totalWords
                )
            }
        }
    }

    /**
     * Tạo Hitbox từ vị trí câu (sentence) trong chapter.
     * Thay thế position=chap*100+sent cũ.
     */
    fun hitboxFromSentence(
        chapterIdx: Int,
        sentenceIdx: Int,
        wordsInSentence: Int
    ): Hitbox {
        // start = tổng từ trước câu này, end = start + wordsInSentence
        // Giả định caller tính tổng từ trước câu này
        return Hitbox(
            start = 0, // caller phải set đúng
            end = wordsInSentence,
            chapterIdx = chapterIdx,
            sentenceIdx = sentenceIdx
        )
    }

    /**
     * Tạo Hitbox từ word offset trong chapter.
     */
    fun hitboxFromWordOffset(
        chapterIdx: Int,
        wordStart: Int,
        length: Int,
        sentenceIdx: Int = -1
    ): Hitbox {
        return Hitbox(
            start = wordStart,
            end = wordStart + length,
            chapterIdx = chapterIdx,
            sentenceIdx = sentenceIdx
        )
    }

    /**
     * Tạo SearchWindow từ Hitbox với context mặc định ±7 từ.
     */
    fun searchWindow(hitbox: Hitbox, totalWords: Int): SearchWindow {
        return SearchWindow.fromHitbox(hitbox, totalWords)
    }

    /**
     * Tạo Hitbox từ span (startChar, endChar) trong text - dùng cho tap-to-define.
     * Chuyển char offset → word index.
     */
    fun hitboxFromCharOffset(
        text: String,
        charStart: Int,
        charEnd: Int,
        chapterIdx: Int = -1,
        sentenceIdx: Int = -1
    ): Hitbox? {
        if (charStart < 0 || charEnd > text.length || charStart >= charEnd) return null
        val before = text.substring(0, charStart)
        val match = text.substring(charStart, charEnd)
        val wordStart = before.split(Regex("\\s+")).size
        val wordEnd = wordStart + match.split(Regex("\\s+")).size
        return Hitbox(
            start = wordStart,
            end = wordEnd,
            chapterIdx = chapterIdx,
            sentenceIdx = sentenceIdx
        )
    }
}
