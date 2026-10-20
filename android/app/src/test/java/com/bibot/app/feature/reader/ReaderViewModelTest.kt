package com.bibot.app.feature.reader

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * G1.2: VM giữ vị trí đọc — sống sót recreate (cùng SavedStateHandle) + clear khi đóng sách.
 */
class ReaderViewModelTest {

    @Test
    fun position_recordedAndSurvivesRecreate() {
        val handle = SavedStateHandle()
        val vm = ReaderViewModel(handle)
        vm.onPosition("how-it-works", 2, 317)
        assertEquals("how-it-works", vm.bookId)
        assertEquals(2, vm.chapter)
        assertEquals(317, vm.sentence)
        // "Recreate"/kill process: ViewModel mới đọc lại từ SavedStateHandle cũ.
        val restored = ReaderViewModel(handle)
        assertEquals("how-it-works", restored.bookId)
        assertEquals(2, restored.chapter)
        assertEquals(317, restored.sentence)
    }

    @Test
    fun freshViewModel_hasNoBook_soKhongTuMoLai() {
        val vm = ReaderViewModel(SavedStateHandle())
        assertNull(vm.bookId)
        assertEquals(0, vm.chapter)
        assertEquals(0, vm.sentence)
    }

    @Test
    fun clear_dongSach_khongTuMoLaiSauRecreate() {
        val handle = SavedStateHandle()
        val vm = ReaderViewModel(handle)
        vm.onPosition("b1", 5, 10)
        vm.clear()
        assertNull(vm.bookId)
        assertNull(ReaderViewModel(handle).bookId)
        // Mở sách khác sau đó vẫn ghi được (VM dùng lại bình thường).
        vm.onPosition("b2", 1, 3)
        assertEquals("b2", vm.bookId)
        assertEquals(1, vm.chapter)
        assertEquals(3, vm.sentence)
    }

    @Test
    fun onPosition_ghiDeDayDu() {
        val handle = SavedStateHandle()
        val vm = ReaderViewModel(handle)
        vm.onPosition("a", 0, 0)
        vm.onPosition("a", 3, 44)
        vm.onPosition("c", 9, 120)
        assertEquals("c", vm.bookId)
        assertEquals(9, vm.chapter)
        assertEquals(120, vm.sentence)
        assertEquals("c", ReaderViewModel(handle).bookId)
    }
}
