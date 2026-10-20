package com.bibot.app.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/**
 * G1.2 (hardening tối giản — chốt 2026-10-20): vị trí sách ĐANG MỞ của 1 collection
 * (EN/STORY/COTICH), sống sót qua:
 *  - Activity recreate (uiMode/locale/fontScale/density KHÔNG nằm trong `configChanges`
 *    của MainActivity → các `remember` trong ReaderScreen bị reset),
 *  - process bị kill (`SavedStateHandle` đọc lại state đã lưu của ViewModelStore).
 *
 * KHÔNG hoist pipeline TTS (`prepSession`, `jumpSeq`, producer job, LaunchedEffect
 * `isReading`) — chúng vẫn ở Compose để giữ nguyên hành vi đã verify (G3.2/G3.3 title
 * chương, G1.3 xoay màn hình, fix OfflineTtsHelper.stop). VM chỉ trả lời đúng 1 câu:
 * "vừa mở sách nào, đang ở chương/câu nào" để ReaderScreen mở lại khi vào màn.
 *
 * Không cần Context → constructor chỉ `(SavedStateHandle)` (factory mặc định của
 * ComponentActivity nhận chữ ký này; KHÔNG phải AndroidViewModel như NewsViewModel vì
 * không dùng Application). Key theo collection khi lấy: `viewModel(key = "reader_...")`
 * (3 kệ dùng chung Activity-scoped ViewModelStore).
 */
class ReaderViewModel(private val handle: SavedStateHandle) : ViewModel() {

    /** id sách đang mở; null = không mở sách nào (không tự mở lại lần sau). */
    var bookId: String? by mutableStateOf(handle["bookId"])
        private set

    /** Chương hiện tại (0-based) — cùng đơn vị với `loadBook(forceChap, forceSent)`. */
    var chapter: Int by mutableIntStateOf(handle.get<Int>("chapter") ?: 0)
        private set

    /** Câu hiện tại (0-based) trong chương. */
    var sentence: Int by mutableIntStateOf(handle.get<Int>("sentence") ?: 0)
        private set

    /**
     * Ghi vị trí hiện tại — gọi từ LaunchedEffect mirror trong ReaderScreen
     * (chỉ khi có sách mở; `bookId == null` là no-op để không đè bản lưu
     * đang chờ khôi phục sau recreate).
     */
    fun onPosition(bookId: String, chapter: Int, sentence: Int) {
        this.bookId = bookId
        this.chapter = chapter
        this.sentence = sentence
        handle["bookId"] = bookId
        handle["chapter"] = chapter
        handle["sentence"] = sentence
    }

    /** Người dùng đóng hẳn sách (Back về kệ) → lần sau vào màn là kệ sách, không tự mở. */
    fun clear() {
        bookId = null
        handle["bookId"] = null
    }
}
