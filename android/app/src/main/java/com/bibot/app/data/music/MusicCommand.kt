package com.bibot.app.data.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * Lệnh nhạc bằng giọng nói — thay applet Music player (quyết định 2026-09-24):
 * "Bi ơi mở bài Diễm Xưa của Trịnh Công Sơn" → parse local (không tốn LLM)
 * → tìm MusicIndex/MediaStore local trước → fallback Intent app nhạc mặc định.
 * Scoring port `music.py search_local_ranked`: 100 exact / 80 contains / 60 fuzzy từ + không dấu.
 */

data class MusicQuery(val title: String, val artist: String?)

// Cụm điều khiển phát nhạc — KHÔNG được parse thành lệnh mở bài
private val CONTROL_BLOCKLIST = listOf("dừng", "dừng lại", "thôi", "im đi", "tạm dừng", "tiếp tục", "tiếp", "nhỏ lại", "to lên")

private val OPEN_VERBS = "(?:mở|phát|nghe|cho\\s+nghe)"
private val SONG_NOUNS = "(?:bài(?:\\s+hát)?|bản|nhạc|ca\\s+khúc)"
private val OPEN_RE = Regex(
    "^(?:bi\\s+ơi[,\\s]+)?(?:tìm(?:\\s+và)?\\s+)?$OPEN_VERBS\\s+(?:$SONG_NOUNS\\s+)?(.+)$",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
// "tìm bài X" / "tìm cho tôi nhạc X" — vẫn phải có từ chỉ nhạc (bài/bản/nhạc/ca khúc)
// để không nuốt các lệnh "tìm ..." khác của app.
private val FIND_RE = Regex(
    "^(?:bi\\s+ơi[,\\s]+)?tìm(?:\\s+cho\\s+(?:tôi|em|anh|bố|mẹ|con))?\\s+$SONG_NOUNS\\s+(.+)$",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/** Parse câu STT thành MusicQuery, null nếu không phải lệnh mở nhạc. */
fun parseMusicCommand(raw: String): MusicQuery? {
    val text = raw.trim().trimEnd('.', '!', '?', ',', ';').trim()
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    if (CONTROL_BLOCKLIST.any { lower == it || lower.startsWith("$it ") }) return null
    val m = OPEN_RE.find(text) ?: FIND_RE.find(text) ?: return null
    var rest = m.groupValues[1].trim().trimEnd('.', '!', '?', ',', ';').trim()
    if (rest.isEmpty()) return null
    // Tách "[tên bài] của [tác giả]" — lấy " của " cuối cùng để tên bài chứa "của" vẫn đúng
    var artist: String? = null
    val cuaIdx = rest.lowercase().lastIndexOf(" của ")
    if (cuaIdx > 0) {
        artist = rest.substring(cuaIdx + 5).trim().trimEnd('.', '!', '?').trim().ifBlank { null }
        rest = rest.substring(0, cuaIdx).trim()
        if (rest.isEmpty()) return null
    }
    // Bỏ tiền tố "giúp tôi/em/anh/bố/mẹ" nếu STT dính vào
    rest = rest.replace(Regex("^(?:giúp|giùm)\\s+\\S+\\s+", RegexOption.IGNORE_CASE), "").trim()
    if (rest.isEmpty()) return null
    return MusicQuery(title = rest, artist = artist)
}

/** Bỏ dấu tiếng Việt để so khớp fuzzy (port strip_accents P10). */
fun stripAccents(s: String): String {
    val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
    return nfd.replace(Regex("\\p{Mn}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
}

private fun norm(s: String): String = stripAccents(s.lowercase().trim())

/** Score 100/80/60 theo P10; artist lạ (Unknown) thì bỏ qua vòng artist. */
fun scoreTrack(qTitle: String, qArtist: String?, track: Track): Int {
    val qt = norm(qTitle)
    val tt = norm(track.title)
    if (qt.isEmpty() || tt.isEmpty()) return 0
    // Lọc artist trước (nếu cả 2 phía đều biết)
    if (!qArtist.isNullOrBlank()) {
        val ta = norm(track.artist)
        if (ta.isNotEmpty() && ta != "unknown") {
            val qa = norm(qArtist)
            if (!ta.contains(qa) && !qa.contains(ta)) return 0
        }
    }
    if (tt == qt) return 100
    if (tt.contains(qt) || qt.contains(tt)) return 80
    val qWords = qt.split(Regex("\\s+")).filter { it.length > 1 }
    if (qWords.isNotEmpty() && qWords.all { tt.contains(it) }) return 60
    // Fuzzy mới: ≥60% từ (bỏ dấu, so khớp tiền tố) khớp với tiêu đề → 40-55
    val tWords = tt.split(Regex("\\s+"))
    val hit = qWords.count { w -> tWords.any { tw -> tw.startsWith(w) || w.startsWith(tw) } }
    if (qWords.isNotEmpty() && hit * 100 >= qWords.size * 60) return (40 + hit).coerceAtMost(55)
    return 0
}

/**
 * Tìm trên TOÀN BỘ thư viện (MediaStore máy + file Bibot), sắp điểm giảm dần.
 * Đây là hàm tìm chính của module Tìm nhạc — chạy IO, list vài trăm mục.
 */
suspend fun searchTracks(context: Context, q: MusicQuery, limit: Int = 8): List<Pair<Track, Int>> =
    withContext(Dispatchers.IO) {
        try {
            MusicIndex.allTracks(context)
                .map { it to scoreTrack(q.title, q.artist, it) }
                .filter { it.second > 0 }
                .sortedWith(
                    compareByDescending<Pair<Track, Int>> { it.second }
                        .thenBy { it.first.title.lowercase() }
                )
                .take(limit)
        } catch (_: Exception) { emptyList() }
    }

/** Tên cũ giữ nguyên cho call site cũ — nay tìm cả thư viện thiết bị. */
suspend fun searchLocalTracks(context: Context, q: MusicQuery, limit: Int = 5): List<Pair<Track, Int>> =
    searchTracks(context, q, limit)
