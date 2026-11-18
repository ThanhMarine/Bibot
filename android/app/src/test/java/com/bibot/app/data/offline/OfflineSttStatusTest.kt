package com.bibot.app.data.offline

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Câu trạng thái STT offline ở Cài đặt — phải nói đúng cái đang thiếu
 * (model / engine), tránh câu chung "chưa có model" khi thực ra thiếu engine.
 */
class OfflineSttStatusTest {

    @Test
    fun `sherpa + engine + vosk la san sang tot nhat`() {
        val s = OfflineSttHelper.statusText(hasVosk = true, hasSherpaModel = true, sherpaEngineReady = true)
        assertTrue(s.startsWith("Sẵn sàng"))
        assertTrue(s.contains("Sherpa"))
        assertTrue(s.contains("Vosk"))
    }

    @Test
    fun `chi co sherpa va engine van san sang`() {
        val s = OfflineSttHelper.statusText(hasVosk = false, hasSherpaModel = true, sherpaEngineReady = true)
        assertTrue(s.startsWith("Sẵn sàng"))
        assertTrue(s.contains("Sherpa"))
    }

    @Test
    fun `co model sherpa nhung thieu engine thi noi ro thieu engine`() {
        val s = OfflineSttHelper.statusText(hasVosk = false, hasSherpaModel = true, sherpaEngineReady = false)
        assertTrue(s.contains("engine"))
        assertTrue(!s.startsWith("Sẵn sàng"))
    }

    @Test
    fun `co vosk du phong thi van san sang du thieu engine sherpa`() {
        val s = OfflineSttHelper.statusText(hasVosk = true, hasSherpaModel = true, sherpaEngineReady = false)
        assertTrue(s.startsWith("Sẵn sàng"))
        assertTrue(s.contains("Vosk"))
        assertTrue(s.contains("engine"))
    }

    @Test
    fun `chi co vosk`() {
        val s = OfflineSttHelper.statusText(hasVosk = true, hasSherpaModel = false, sherpaEngineReady = false)
        assertTrue(s.startsWith("Sẵn sàng"))
        assertTrue(s.contains("Vosk"))
    }

    @Test
    fun `khong co gi thi huong dan tai model`() {
        val s = OfflineSttHelper.statusText(hasVosk = false, hasSherpaModel = false, sherpaEngineReady = false)
        assertTrue(s.contains("Mô hình offline"))
    }
}
