package com.bibot.app.core.text

import android.content.Context
import android.util.Log
import com.bibot.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * H3.2 OTA rulepack — tải `reading-rules.json` mới từ GitHub raw, verify checksum +
 * version lớn hơn, ghi `filesDir/reading-rules.json`; app đọc file này TRƯỚC file nhúng
 * trong APK (`ReadingRules.load`). Lỗi mạng/404/checksum sai → giữ bản nhúng (fallback
 * mặc định, không crash). Rule mới hiệu lực từ LẦN MỞ APP SAU — không đổi rule giữa
 * phiên đang đọc (tránh lệch `.prep`/karaoke).
 *
 * Checksum Y HỆT `compile_rules.py`: sha256 của canonical JSON `{"rules":[...],
 * "version":N}` — single-line, key sắp xếp (sort_keys), UTF-8 trần (ensure_ascii=False),
 * ngăn cách như Python mặc định (`, ` và `: `), escape như Python (nháy kép,
 * gạch chéo, ký tự điều khiển kiểu b/t/n/f/r, còn lại escape hex 4 ký tự).
 *
 * Lưu ý an toàn: checksum chống HỎNG HỌT file (transport/storage), KHÔNG phải chữ ký
 * số — người sửa được nội dung cũng tự tính lại được. Niềm tin đặt ở HTTPS + GitHub;
 * version gate chặn downgrade, checksum chặn parse-sai/truncated.
 */
object RulesOta {

    /** Nguồn tĩnh (H3.1 chốt: GitHub raw URL cứng). 404/không đổi → fallback nhúng. */
    const val SOURCE_URL =
        "https://raw.githubusercontent.com/ThanhMarine/Bibot/main/tools/rules/reading-rules.json"

    /** Trần bytes (JSON v4 ~6KB, KDoc ReadingRules: trần 30KB — chừa dư). */
    const val MAX_BYTES = 64 * 1024

    fun diskFile(context: Context): File = File(context.filesDir, "reading-rules.json")

    /** version của pack (null nếu không parse được). */
    fun versionOf(text: String): Long? {
        return try {
            val root = ReadingRules.MiniJson.parse(text) as? Map<*, *> ?: return null
            root["version"] as? Long
        } catch (_: Exception) { null }
    }

    /**
     * Pack hợp lệ: parse được, có rules (rỗng = bị cắt/thay), checksum khớp canonical.
     * Dùng cho cả file tải về lẫn file trên disk.
     */
    fun isValidPack(text: String): Boolean {
        return try {
            val root = ReadingRules.MiniJson.parse(text) as? Map<*, *> ?: return false
            val v = root["version"] as? Long ?: return false
            val stored = root["checksum"] as? String ?: return false
            val rules = root["rules"] as? List<*> ?: return false
            if (rules.isEmpty()) return false
            val payload = LinkedHashMap<String, Any?>()
            payload["rules"] = rules
            payload["version"] = v
            sha256Hex(canonicalJson(payload)).equals(stored, ignoreCase = true)
        } catch (_: Exception) { false }
    }

    /**
     * Chọn pack dùng khi nạp: disk chỉ thắng assets khi version LỚN hơn + checksum đúng;
     * disk cũ hơn/hỏng → assets (caller xoá file disk để lần sync tới tải lại).
     */
    fun pickPack(assetsText: String, diskText: String?): String {
        if (diskText == null) return assetsText
        val av = versionOf(assetsText) ?: 0L
        val dv = versionOf(diskText) ?: return assetsText
        if (dv <= av) return assetsText
        if (!isValidPack(diskText)) return assetsText
        return diskText
    }

    /**
     * Canonical JSON khớp `json.dumps(..., ensure_ascii=False, sort_keys=True)` của
     * Python: key sắp TOÀN BỘ map theo thứ tự tăng dần, mảng giữ nguyên thứ tự,
     * ngăn cách mặc định của Python = `, ` và `: ` (KHÔNG compact), số nguyên không
     * dấu thập phân. Ký tự hiếm ngoài dải BMP: Kotlin/Python đều giữ nguyên → UTF-8 trùng.
     */
    fun canonicalJson(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> if (v) "true" else "false"
        is Long -> v.toString()
        is Int -> v.toString()
        is String -> quotePython(v)
        is Map<*, *> -> {
            val sb = StringBuilder("{")
            var first = true
            for (k in v.keys.sortedBy { it.toString() }) {
                if (!first) sb.append(", ")
                first = false
                sb.append(quotePython(k.toString())).append(": ")
                sb.append(canonicalJson(v[k]))
            }
            sb.append('}').toString()
        }
        is List<*> -> {
            val sb = StringBuilder("[")
            for ((i, e) in v.withIndex()) {
                if (i > 0) sb.append(", ")
                sb.append(canonicalJson(e))
            }
            sb.append(']').toString()
        }
        // Schema compile_rules.py không sinh số thực/số khác — fail-closed (verify fail → fallback).
        else -> throw IllegalArgumentException("unsupported ${v::class}")
    }

    /** Escape như encoder chuẩn Python: nháy kép/gạch chéo/b t n f r, còn lại hex 4 ký tự. */
    private fun quotePython(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) {
                sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", ch.code))
            } else sb.append(ch)
        }
        sb.append('"')
        return sb.toString()
    }

    fun sha256Hex(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format(java.util.Locale.ROOT, "%02x", b))
        return sb.toString()
    }

    /**
     * Tải pack mới ở nền (gọi từ appScope IO). Trả true nếu có bản MỚI hợp lệ đã ghi
     * disk (hiệu lực lần mở sau). Mọi lỗi → false, giữ nguyên bản hiện tại.
     */
    suspend fun sync(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = download(SOURCE_URL)
            if (!isValidPack(text)) {
                dbg("rules ota: pack sai checksum/rules — bỏ, giữ bản nhúng")
                return@withContext false
            }
            val v = versionOf(text) ?: return@withContext false
            val cur = ReadingRules.loadedVersion
            if (v <= cur) {
                dbg("rules ota: v$v <= v$cur — không đổi")
                return@withContext false
            }
            diskFile(context).writeText(text, Charsets.UTF_8)
            dbg("rules ota: nhận v$v (hiện v$cur) → filesDir, hiệu lực lần mở sau")
            true
        } catch (e: Exception) {
            dbg("rules ota: lỗi (${e.message}) — giữ bản nhúng")
            false
        }
    }

    private fun download(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Bibot-Rules/1.0")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                val out = ByteArrayOutputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) throw Exception("quá $MAX_BYTES bytes")
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun dbg(msg: String) {
        if (BuildConfig.DEBUG) Log.d("BibotDbg", msg)
    }
}
