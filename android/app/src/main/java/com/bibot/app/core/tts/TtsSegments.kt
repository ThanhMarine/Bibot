package com.bibot.app.core.tts

import java.security.MessageDigest

/**
 * A1 — Segment plan + cache identity (port openreader `packages/tts`,
 * mirror `internal/chunker/segments.go`).
 *
 * Giữ đúng hợp đồng openreader:
 * - Segment = {ordinal (0-based, identity resume), key, text, owner, locator, anchors}.
 * - key = "{prefix}:{FNV-1a 32-bit của identity-text}" trên UTF-16 units
 *   (Kotlin Char == UTF-16 unit, từng half của surrogate tính riêng —
 *   khớp JS charCodeAt; vector chuẩn ASCII: "hello"→4f9f2cab).
 * - settingsHash = SHA256(settingsCanonical + 0x00 + segmentationCanonical).
 * - audioContentHash = SHA256({d,v,s,k,t,l}); có segmentKey → locator null
 *   (dedup: cùng text+voice ở 2 vị trí dùng chung 1 file).
 * - audioKey = "{settingsHash}/{audioContentHash}" (dir/file, ext theo engine).
 * - Chuẩn audio đích (audio-format.ts): 44.1kHz mono 128k CBR,
 *   16000 byte/s, frame 1152 mẫu (~26.12ms), strip Xing.
 *
 * KHÁC BIỆT CÓ CHỦ ĐÍCH (side-local, 2 phía không chia sẻ file cache):
 * - normalizeIdentity input là `plain` đã qua TextCleaner (producer),
 *   rồi lowercase + collapse ws + trim. Go dùng CleanTTSText + cùng bước sau.
 * - locator Bibot = {spineIndex = chapter/page, charOffset best-effort};
 *   cache key bỏ locator khi có segmentKey nên offset chỉ để debug/resume.
 * - Chưa transcode on-device (không ffmpeg): Piper wav / Google MP3 giữ nguyên;
 *   hằng PROFILE bên dưới là đích cho export (A3) + kế toán silence theo frame.
 */
data class TtsSegmentLocator(
    val readerType: String = "",
    val spineIndex: Int = 0,
    val charOffset: Int = -1
)

data class TtsSegmentAnchor(val sourceKey: String, val offset: Int)

data class TtsSegment(
    val ordinal: Int,
    val key: String,
    val text: String,
    val ownerSourceKey: String,
    val locator: TtsSegmentLocator,
    val startAnchor: TtsSegmentAnchor,
    val endAnchor: TtsSegmentAnchor
)

data class TtsSourceUnit(
    val sourceKey: String,
    val text: String,
    val spineIndex: Int = 0,
    val readerType: String = ""
)

data class TtsSettings(
    val voice: String,
    val speed: String,
    val format: String,
    val language: String
)

data class TtsSegmentation(
    val maxBlockLength: Int,
    val language: String,
    val enforceSourceBoundaries: Boolean = true,
    val skipBlockKinds: List<String> = emptyList(),
    val isPlainText: Boolean = false,
    val namespace: String? = null
)

object TtsSegments {
    const val PLAN_VERSION = "tts-segment-plan-v2"

    // Chuẩn audio đích (port audio-format.ts STREAM_AUDIO_PROFILE).
    const val SAMPLE_RATE_HZ = 44100
    const val CHANNELS = 1
    const val BITRATE_KBPS = 128
    const val BYTES_PER_SECOND = 16000 // 128kbit/s / 8
    const val MP3_FRAME_SAMPLES = 1152
    const val MP3_FRAME_DURATION_MS = 1152.0 / 44100.0 * 1000.0 // ~26.122ms

    private val WS = Regex("\\s+")

    /** Identity cho key: lowercase + collapse ws + trim (input đã clean). */
    fun normalizeIdentityText(s: String): String =
        s.lowercase().replace(WS, " ").trim()

    /** Text cho content hash: collapse ws + trim, giữ case. */
    fun normalizeContentText(s: String): String =
        s.replace(WS, " ").trim()

    /**
     * FNV-1a 32-bit trên UTF-16 code units, hex 8 ký tự.
     * Int Kotlin tràn số bọc 2's complement == Math.imul mod 2^32 của JS.
     */
    fun stableHash(s: String): String {
        var h = 0x811C9DC5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return (h.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    }

    fun keyPrefix(docId: String, readerType: String): String =
        "${docId.ifBlank { "document" }}:$readerType:v1"

    fun segmentKey(prefix: String, text: String): String =
        "$prefix:${stableHash(normalizeIdentityText(text))}"

    fun readerTypeFor(fileName: String?): String {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""
        return when (ext) {
            "pdf" -> "pdf"
            "epub" -> "epub"
            else -> "md"
        }
    }

    /**
     * Chia units thành segments có identity ổn định.
     * splitter: hàm tách câu/block (vd VietnameseChunker.split).
     * Anchor/offset theo UTF-16 units (String.length), clamp trong unit.
     */
    fun planSegments(
        units: List<TtsSourceUnit>,
        docId: String,
        readerType: String,
        splitter: (String) -> List<String>
    ): List<TtsSegment> {
        val rt = readerType.ifBlank { "md" }
        val prefix = keyPrefix(docId, rt)
        val out = mutableListOf<TtsSegment>()
        var ordinal = 0
        for (u in units) {
            var cursor = 0
            val unitLen = u.text.length
            for (raw in splitter(u.text)) {
                val block = raw.trim()
                if (block.isEmpty()) continue
                val rel = u.text.indexOf(block, cursor)
                val abs = if (rel >= 0) rel else cursor
                val start = abs.coerceIn(0, unitLen)
                val end = (abs + block.length).coerceIn(start, unitLen)
                cursor = abs + block.length
                val locRt = u.readerType.ifBlank { rt }
                val loc = TtsSegmentLocator(locRt, u.spineIndex, start)
                out += TtsSegment(
                    ordinal = ordinal,
                    key = segmentKey(prefix, block),
                    text = block,
                    ownerSourceKey = u.sourceKey,
                    locator = loc,
                    startAnchor = TtsSegmentAnchor(u.sourceKey, start),
                    endAnchor = TtsSegmentAnchor(u.sourceKey, end)
                )
                ordinal++
            }
        }
        return out
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun jsonStr(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private fun settingsCanonical(s: TtsSettings): String =
        "{\"voice\":${jsonStr(s.voice)}," +
            "\"speed\":${jsonStr(s.speed)}," +
            "\"format\":${jsonStr(s.format)}," +
            "\"language\":${jsonStr(s.language)}}"

    private fun segmentationCanonical(seg: TtsSegmentation): String =
        "{\"maxBlockLength\":${seg.maxBlockLength}," +
            "\"language\":${jsonStr(seg.language)}," +
            "\"enforceSourceBoundaries\":${seg.enforceSourceBoundaries}," +
            "\"skipBlockKinds\":[${seg.skipBlockKinds.joinToString(",") { jsonStr(it) }}]," +
            "\"isPlainText\":${seg.isPlainText}," +
            "\"namespace\":${if (seg.namespace == null) "null" else jsonStr(seg.namespace)}}"

    /**
     * settingsHash tách theo voice/format/speed/language/maxBlock:
     * đổi voice EN↔VI rồi switch-back → trúng cache cũ (A1.5).
     */
    fun settingsHash(s: TtsSettings, seg: TtsSegmentation): String {
        val clean = seg.copy(skipBlockKinds = seg.skipBlockKinds.map { it.trim() }.filter { it.isNotEmpty() }.sorted())
        return sha256Hex(settingsCanonical(s) + "\u0000" + segmentationCanonical(clean))
    }

    fun locatorFingerprint(loc: TtsSegmentLocator?): String {
        if (loc == null || loc.readerType.isBlank()) return ""
        return sha256Hex(
            "{\"readerType\":${jsonStr(loc.readerType)}," +
                "\"spineIndex\":${loc.spineIndex}," +
                "\"charOffset\":${loc.charOffset}}"
        )
    }

    /**
     * audioContentHash bao {d,v,s,k,t,l}; có segmentKey → locator null
     * (dedup chủ đích, khớp segments.ts).
     */
    fun audioContentHash(
        docId: String,
        docVersion: Long,
        settingsHash: String,
        segmentKey: String?,
        normalizedText: String,
        locatorFP: String
    ): String {
        val l = if (!segmentKey.isNullOrEmpty()) "" else locatorFP
        val payload = "{\"d\":${jsonStr(docId)}," +
            "\"v\":$docVersion," +
            "\"s\":${jsonStr(settingsHash)}," +
            "\"k\":${if (segmentKey == null) "null" else jsonStr(segmentKey)}," +
            "\"t\":${jsonStr(normalizedText)}," +
            "\"l\":${jsonStr(l)}}"
        return sha256Hex(payload)
    }

    /** audioKey = "{settingsHash}/{audioHash}" (dir/file; caller thêm ext). */
    fun audioKey(settingsHash: String, audioHash: String): String =
        "$settingsHash/$audioHash"
}
