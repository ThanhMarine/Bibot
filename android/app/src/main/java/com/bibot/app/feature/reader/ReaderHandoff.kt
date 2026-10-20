package com.bibot.app.feature.reader

import com.bibot.app.data.books.BookMeta

/**
 * B2/B5.8: yêu cầu mở 1 cuốn sách ở chương/câu cụ thể.
 *
 * Detail/Wiki (màn ABOUT, không compose cùng ReaderScreen) post [post] rồi
 * `switchMode` sang mode đọc tương ứng; ReaderScreen khi compose sẽ [consume]
 * đúng 1 lần (không state Compose chéo màn, 0 dependency).
 */
object ReaderHandoff {
    data class Request(
        val book: BookMeta,
        /** -1 = theo progress/đọc từ đầu. */
        val chapterIdx: Int = -1,
        val sentenceIdx: Int = 0
    )

    @Volatile
    private var pending: Request? = null

    fun post(book: BookMeta, chapterIdx: Int = -1, sentenceIdx: Int = 0) {
        pending = Request(book, chapterIdx, sentenceIdx)
    }

    fun consume(): Request? {
        val r = pending
        pending = null
        return r
    }
}
