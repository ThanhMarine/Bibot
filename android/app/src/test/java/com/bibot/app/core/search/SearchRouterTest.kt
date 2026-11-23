package com.bibot.app.core.search

import com.bibot.app.data.store.BotPromptManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression cho logic mở rộng tra cứu web + prompt giới hạn từ động. */
class SearchRouterTest {

    @Test
    fun `tra cuu thong tin thuc su duoc router hoa`() {
        assertTrue(IntentRouter.requiresWebInfo("Ai là Albert Einstein?"))
        assertTrue(IntentRouter.requiresWebInfo("Giải thích khái niệm hố đen"))
        assertTrue(IntentRouter.requiresWebInfo("Tra cứu lịch sử Việt Nam"))
        assertTrue(IntentRouter.requiresWebInfo("Thời tiết Hà Nội hôm nay"))
    }

    @Test
    fun `cau xa giao va cau ve bot khong di web`() {
        assertFalse(IntentRouter.requiresWebInfo("Chào bạn"))
        assertFalse(IntentRouter.requiresWebInfo("Cảm ơn em"))
        assertFalse(IntentRouter.requiresWebInfo("Bạn là ai?"))
        assertFalse(IntentRouter.requiresWebInfo("Em nhớ gì về mình không?"))
    }

    @Test
    fun `prompt tra cuu co gioi han tu va che do ngan gon`() {
        val brief = BotPromptManager.searchAnswerInstruction(50, true)
        assertTrue(brief.contains("50"))
        assertTrue(brief.contains("ngắn gọn và đủ ý"))
        val plain = BotPromptManager.searchAnswerInstruction(30, false)
        assertTrue(plain.contains("30"))
        assertFalse(plain.contains("ngắn gọn và đủ ý"))
    }
}
