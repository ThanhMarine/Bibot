package com.bibot.app.core.announce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quy tắc hiển thị tin NPT — thuần logic, không cần Context/JSON.
 *
 * Bug từng gặp: tin `upgrade` phải TỰ ẨN sau khi người dùng cập nhật. Nếu so sánh phiên
 * bản sai (so chuỗi) thì "1.0.10" < "1.0.9" và tin cũ hiện mãi.
 */
class AnnounceApiTest {

    @Test
    fun `so sanh phien bang nhau`() {
        assertEquals(0, AnnounceApi.compareVersions("1.0.4", "1.0.4"))
        // hậu tố flavor không được ảnh hưởng
        assertEquals(0, AnnounceApi.compareVersions("1.0.4-demo", "1.0.4"))
        assertEquals(0, AnnounceApi.compareVersions("1.0.4", "1.0.4-vip"))
    }

    @Test
    fun `so sanh theo tung phan so hoc khong phai chuoi`() {
        assertTrue(AnnounceApi.compareVersions("1.0.10", "1.0.9") > 0)
        assertTrue(AnnounceApi.compareVersions("1.0.4", "1.0.5") < 0)
        assertTrue(AnnounceApi.compareVersions("1.1.0", "1.0.99") > 0)
        assertTrue(AnnounceApi.compareVersions("2.0", "1.9.9") > 0)
    }

    @Test
    fun `thieu phan so duoc coi la 0`() {
        assertEquals(0, AnnounceApi.compareVersions("1.0", "1.0.0"))
        assertTrue(AnnounceApi.compareVersions("1.0.1", "1.0") > 0)
    }

    @Test
    fun `tin upgrade hien khi app con cu hon`() {
        val item = announce("upgrade", minVersion = "1.0.5")
        assertTrue(AnnounceApi.isApplicable(item, "1.0.4"))
    }

    @Test
    fun `tin upgrade tu an sau khi da cap nhat`() {
        val item = announce("upgrade", minVersion = "1.0.5")
        assertFalse(AnnounceApi.isApplicable(item, "1.0.5"))
        assertFalse(AnnounceApi.isApplicable(item, "1.0.6"))
    }

    @Test
    fun `tin thuong luon hien du phien ban nao`() {
        val item = announce("info")
        assertTrue(AnnounceApi.isApplicable(item, "1.0.4"))
        assertTrue(AnnounceApi.isApplicable(item, "9.9.9"))
    }

    @Test
    fun `tin thieu id hoac title bi bo qua`() {
        assertFalse(AnnounceApi.isApplicable(announce("info").copy(id = ""), "1.0.4"))
        assertFalse(AnnounceApi.isApplicable(announce("info").copy(title = "  "), "1.0.4"))
    }

    @Test
    fun `upgrade thieu minVersion khong bi an nham`() {
        // Không có mốc so sánh thì hiện bình thường, tránh tin "mất tích" do quên field.
        assertTrue(AnnounceApi.isApplicable(announce("upgrade", minVersion = ""), "1.0.4"))
    }

    private fun announce(type: String, minVersion: String = "") = AnnounceItem(
        id = "v1.0.4-test",
        title = "Tin thử",
        body = "",
        type = type,
        minVersion = minVersion,
        url = "",
        publishedAt = "2026-11-18"
    )
}
