package com.bibot.app.data.library

import com.bibot.app.data.db.BookProgress

/**
 * A2.4a: Vị trí đọc vạn năng — thay `position = chap*100+sent` cũ.
 *
 * Pure Kotlin (không Context/Compose) để Shell_Manager + mọi kệ ReadEra reuse chung.
 * - chap/sent: index 0-based, đúng với mọi chương >100 câu.
 * - page/offset: mirror anchorPage/anchorOffset (PDF PAGE map tỉ lệ + TEXT anchor).
 * - Legacy `position` chỉ đọc để backfill (Migration 5→6), code mới KHÔNG dùng để seek.
 */
data class ReadingPosition(
    val chap: Int = 0,
    val sent: Int = 0,
    val page: Int = -1,
    val offset: Int = 0
) {
    companion object {
        /** Legacy chap*100+sent → tách lại (chỉ dùng backfill/migrate). */
        fun fromLegacy(pos: Int): ReadingPosition {
            if (pos <= 0) return ReadingPosition(0, 0)
            return ReadingPosition(chap = (pos / 100).coerceAtLeast(0), sent = (pos % 100).coerceAtLeast(0))
        }

        /** Đọc từ entity DB: ưu tiên cột mới, fallback anchor F2 rồi legacy. */
        fun fromProgress(p: BookProgress): ReadingPosition {
            if (p.chapterIdx >= 0) return ReadingPosition(
                chap = p.chapterIdx,
                sent = p.sentenceIdx.coerceAtLeast(0),
                page = p.anchorPage,
                offset = p.anchorOffset
            )
            if (p.anchorPage >= 0 && p.readMode == "read") {
                return ReadingPosition(
                    chap = p.anchorPage,
                    sent = p.anchorOffset.coerceAtLeast(0),
                    page = p.anchorPage,
                    offset = p.anchorOffset
                )
            }
            return fromLegacy(p.position).copy(page = p.anchorPage, offset = p.anchorOffset)
        }
    }

    /** Encode legacy để giữ tương thích hiển thị cũ trong 1 bản (KHÔNG dùng seek). */
    fun toLegacy(): Int = chap * 100 + sent

    fun resumeLabel(): String = "Chương ${chap + 1} Câu ${sent + 1}"

    val isStarted: Boolean get() = chap > 0 || sent > 0
}
