package com.bibot.app.core.text

/**
 * TCVN3 (TCVN 5712-3 / ABC) — font tiếng Việt đời cũ trong PDF scan/convert.
 *
 * - Bảng byte→Unicode CHUẨN (đối chiếu vietunicode.sourceforge.net qua gói
 *   CRAN vietnameseConverter + cross-check vncharsets + mapping.txt):
 *   + 0x80-0xFF: 74 slot TCVN3, còn lại giữ nguyên.
 *   + ASCII (0x00-0x7F) KHÔNG đổi (kể cả '-' 0x2D vẫn là gạch nối).
 * - 2 chế độ font (cùng vị trí byte, khác cách vẽ — chuẩn TCVN3):
 *   + font thường (.VnTime, .VnArial, ABC-...): byte → chữ thường.
 *   + font HOA (tên kết thúc 'H': .VnTimeH, ...): byte → CHỮ HOA.
 * - Font thường còn viết chữ HOA có dấu kiểu ghép 2 byte
 *   (base ASCII + byte tone, vd 'A'+0xB5 → À) — COMPOSE xử lý.
 * - VNI KHÔNG xử lý ở đây (VNI dùng tone rời/combining, cần engine riêng).
 */
object Tcvn3 {

    private val LOWER = intArrayOf(
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, 0x0102, 0x00C2, 0x00CA, 0x00D4, 0x01A0, 0x01AF, 0x0110, 0x0103, 0x00E2, 0x00EA, 0x00F4, 0x01A1, 0x01B0, 0x0111, -1,
        -1, -1, -1, -1, -1, 0x00E0, 0x1EA3, 0x00E3, 0x00E1, 0x1EA1, -1, 0x1EB1, 0x1EB3, 0x1EB5, 0x1EAF, -1,
        -1, -1, -1, -1, -1, -1, 0x1EB7, 0x1EA7, 0x1EA9, 0x1EAB, 0x1EA5, 0x1EAD, 0x00E8, -1, 0x1EBB, 0x1EBD,
        0x00E9, 0x1EB9, 0x1EC1, 0x1EC3, 0x1EC5, 0x1EBF, 0x1EC7, 0x00EC, 0x1EC9, -1, -1, -1, 0x0129, 0x00ED, 0x1ECB, 0x00F2,
        -1, 0x1ECF, 0x00F5, 0x00F3, 0x1ECD, 0x1ED3, 0x1ED5, 0x1ED7, 0x1ED1, 0x1ED9, 0x1EDD, 0x1EDF, 0x1EE1, 0x1EDB, 0x1EE3, 0x00F9,
        -1, 0x1EE7, 0x0169, 0x00FA, 0x1EE5, 0x1EEB, 0x1EED, 0x1EEF, 0x1EE9, 0x1EF1, 0x1EF3, 0x1EF7, 0x1EF9, 0x00FD, 0x1EF5, -1,
    )

    private val UPPER = intArrayOf(
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, 0x0102, 0x00C2, 0x00CA, 0x00D4, 0x01A0, 0x01AF, 0x0110, 0x0102, 0x00C2, 0x00CA, 0x00D4, 0x01A0, 0x01AF, 0x0110, -1,
        -1, -1, -1, -1, -1, 0x00C0, 0x1EA2, 0x00C3, 0x00C1, 0x1EA0, -1, 0x1EB0, 0x1EB2, 0x1EB4, 0x1EAE, -1,
        -1, -1, -1, -1, -1, -1, 0x1EB6, 0x1EA6, 0x1EA8, 0x1EAA, 0x1EA4, 0x1EAC, 0x00C8, -1, 0x1EBA, 0x1EBC,
        0x00C9, 0x1EB8, 0x1EC0, 0x1EC2, 0x1EC4, 0x1EBE, 0x1EC6, 0x00CC, 0x1EC8, -1, -1, -1, 0x0128, 0x00CD, 0x1ECA, 0x00D2,
        -1, 0x1ECE, 0x00D5, 0x00D3, 0x1ECC, 0x1ED2, 0x1ED4, 0x1ED6, 0x1ED0, 0x1ED8, 0x1EDC, 0x1EDE, 0x1EE0, 0x1EDA, 0x1EE2, 0x00D9,
        -1, 0x1EE6, 0x0168, 0x00DA, 0x1EE4, 0x1EEA, 0x1EEC, 0x1EEE, 0x1EE8, 0x1EF0, 0x1EF2, 0x1EF6, 0x1EF8, 0x00DD, 0x1EF4, -1,
    )

    private val COMPOSE = mapOf(
        "A\u00E0" to '\u00C0',
        "A\u00E1" to '\u00C1',
        "A\u00E3" to '\u00C3',
        "A\u1EA1" to '\u1EA0',
        "A\u1EA3" to '\u1EA2',
        "E\u00E8" to '\u00C8',
        "E\u00E9" to '\u00C9',
        "E\u1EB9" to '\u1EB8',
        "E\u1EBB" to '\u1EBA',
        "E\u1EBD" to '\u1EBC',
        "I\u00EC" to '\u00CC',
        "I\u00ED" to '\u00CD',
        "I\u0129" to '\u0128',
        "I\u1EC9" to '\u1EC8',
        "I\u1ECB" to '\u1ECA',
        "O\u00F2" to '\u00D2',
        "O\u00F3" to '\u00D3',
        "O\u00F5" to '\u00D5',
        "O\u1ECD" to '\u1ECC',
        "O\u1ECF" to '\u1ECE',
        "U\u00F9" to '\u00D9',
        "U\u00FA" to '\u00DA',
        "U\u0169" to '\u0168',
        "U\u1EE5" to '\u1EE4',
        "U\u1EE7" to '\u1EE6',
        "Y\u00FD" to '\u00DD',
        "Y\u1EF3" to '\u1EF2',
        "Y\u1EF5" to '\u1EF4',
        "Y\u1EF7" to '\u1EF6',
        "Y\u1EF9" to '\u1EF8',
        "\u00C2\u1EA5" to '\u1EA4',
        "\u00C2\u1EA7" to '\u1EA6',
        "\u00C2\u1EA9" to '\u1EA8',
        "\u00C2\u1EAB" to '\u1EAA',
        "\u00C2\u1EAD" to '\u1EAC',
        "\u00CA\u1EBF" to '\u1EBE',
        "\u00CA\u1EC1" to '\u1EC0',
        "\u00CA\u1EC3" to '\u1EC2',
        "\u00CA\u1EC5" to '\u1EC4',
        "\u00CA\u1EC7" to '\u1EC6',
        "\u00D4\u1ED1" to '\u1ED0',
        "\u00D4\u1ED3" to '\u1ED2',
        "\u00D4\u1ED5" to '\u1ED4',
        "\u00D4\u1ED7" to '\u1ED6',
        "\u00D4\u1ED9" to '\u1ED8',
        "\u0102\u1EAF" to '\u1EAE',
        "\u0102\u1EB1" to '\u1EB0',
        "\u0102\u1EB3" to '\u1EB2',
        "\u0102\u1EB5" to '\u1EB4',
        "\u0102\u1EB7" to '\u1EB6',
        "\u01A0\u1EDB" to '\u1EDA',
        "\u01A0\u1EDD" to '\u1EDC',
        "\u01A0\u1EDF" to '\u1EDE',
        "\u01A0\u1EE1" to '\u1EE0',
        "\u01A0\u1EE3" to '\u1EE2',
        "\u01AF\u1EE9" to '\u1EE8',
        "\u01AF\u1EEB" to '\u1EEA',
        "\u01AF\u1EED" to '\u1EEC',
        "\u01AF\u1EEF" to '\u1EEE',
        "\u01AF\u1EF1" to '\u1EF0',
    )

    /** Byte TCVN3 (0-255) → Unicode. upper=true cho font HOA (H-font). */
    fun decodeByte(b: Int, upper: Boolean): Char {
        if (b < 0x80) return b.toChar()
        if (b > 0xFF) return b.toChar()
        val v = (if (upper) UPPER else LOWER)[b - 0x80]
        return if (v < 0) b.toChar() else v.toChar()
    }

    /**
     * Giải mã chuỗi raw (mỗi Char 0x00-0xFF = 1 byte gốc từ PDF Tj).
     * Font thường: ghép luôn cặp 2-byte HOA (CRAN multi-byte).
     */
    fun decode(s: String, upper: Boolean = false): String {
        if (s.isEmpty()) return s
        var hasHigh = false
        for (c in s) {
            if (c.code >= 0x80 && c.code <= 0xFF) { hasHigh = true; break }
        }
        if (!hasHigh) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            val o = c.code
            sb.append(if (o in 0..0xFF) decodeByte(o, upper) else c)
        }
        var t = sb.toString()
        if (!upper && t.length >= 2) {
            val out = StringBuilder(t.length)
            var i = 0
            while (i < t.length) {
                val hit = if (i + 2 <= t.length) COMPOSE[t.substring(i, i + 2)] else null
                if (hit != null) {
                    out.append(hit)
                    i += 2
                } else {
                    out.append(t[i])
                    i++
                }
            }
            t = out.toString()
        }
        return t
    }

    private val subsetPrefix = Regex("^[A-Z]{6}\\+")

    /**
     * Phân loại font PDF theo tên. Trả 0 = không phải TCVN3,
     * 1 = TCVN3 thường, 2 = TCVN3 HOA (H-font).
     */
    fun fontKind(baseFont: String): Int {
        var n = baseFont.trim()
        if (n.isEmpty()) return 0
        n = subsetPrefix.replace(n, "")
        if (n.isEmpty()) return 0
        val isTcvn = n.startsWith(".Vn") ||
            (n.startsWith("Vn") && n.length > 2 && n[2].isUpperCase()) ||
            n.contains("ABC") ||
            n.contains("TCVN", ignoreCase = true)
        if (!isTcvn) return 0
        // Quy ước font ABC: hậu tố 'H' = HOA (.VnTimeH, .VnArialH, ...)
        return if (n.endsWith('H') && n.length > 1) 2 else 1
    }

    // Marker "đã là Unicode tiếng Việt" → KHÔNG convert (tránh phá text đúng).
    // CHỈ tính chữ > U+00FF: text TCVN3-byte (decode Latin-1) không bao giờ có
    // char > U+00FF, còn text Unicode thật luôn có (ă/đ/ơ/ư/ệ…). Các chữ ≤ U+00FF
    // (ê/ô/â/…) vừa là chữ Latin hợp lệ vừa là slot TCVN3 nên KHÔNG kết luận được.
    private val vnUnicode = Regex("[\u0102\u0103\u0110\u0111\u01A0\u01A1\u01AF\u01B0\u1EA0-\u1EF9]")

    /**
     * Heuristic text-level (khi không biết font): true nếu giống TCVN3.
     * - Bỏ qua ASCII thuần và text đã là Unicode tiếng Việt.
     * - Dấu hiệu mạnh 0xAC/0xAD (¬/soft-hyphen: gần như không bao giờ xuất
     *   hiện trong text Latin-1 thật) + ≥1 byte mapped nữa → convert.
     * - Không có marker: cần ≥8 byte mapped + ≥2 byte "lạ" (tránh thịt text
     *   Pháp/Đức dài có é/è/ê — chúng cũng nằm trong bảng TCVN3).
     */
    fun looksLikeTcvn3(s: String): Boolean {
        if (s.isEmpty()) return false
        var mapped = 0
        var strong = 0
        var marker = false
        for (c in s) {
            val o = c.code
            if (o in 0x80..0xFF && LOWER[o - 0x80] >= 0) {
                mapped++
                if (o == 0xAC || o == 0xAD) marker = true
                if (o < 0xC0 || o > 0xDF) strong++
            }
        }
        if (mapped == 0) return false
        if (vnUnicode.containsMatchIn(s)) return false
        if (marker) return mapped >= 2
        return mapped >= 8 && strong >= 2
    }

    /** Chữ Việt đặc trưng chỉ TCVN3-decode đúng mới sinh ra (không lẫn Latin). */
    private fun hasVnDistinctive(s: String): Boolean {
        for (c in s) {
            if (c == 'đ' || c == 'Đ' || c == 'ơ' || c == 'Ơ' || c == 'ư' || c == 'Ư') return true
        }
        return false
    }

    /**
     * Convert có detect + hậu kiểm — an toàn gọi cho mọi text (sai thì trả nguyên):
     * kết quả decode phải chứa đ/Đ/ơ/Ơ/ư/Ư, nếu không thì đây là text Latin
     * (vd tiếng Pháp có é/è/ê — chúng cũng nằm trong bảng TCVN3) → bỏ convert.
     */
    fun convertIfTcvn3(s: String): String {
        if (!looksLikeTcvn3(s)) return s
        val d = decode(s)
        if (d == s || !hasVnDistinctive(d)) return s
        return d
    }
}
