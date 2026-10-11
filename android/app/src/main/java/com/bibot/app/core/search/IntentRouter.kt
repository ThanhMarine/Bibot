package com.bibot.app.core.search

/**
 * P2 (blueprint TASK 3) — Intent Router: quyết định lượt thoại có cần web search không.
 *
 * Port trung thành từ Pi `services/gemini.py:_query_requires_live_info`:
 * keyword-based, ~0ms, KHÔNG tốn roundtrip LLM — với cloud Gemini một roundtrip router
 * riêng sẽ phá guardrail TTFA < 400-500ms.
 *
 * Quy tắc:
 *  1. Câu xã giao ngắn (chào/cảm ơn/bạn là ai...) → KHÔNG search dù trùng keyword.
 *  2. Chứa từ khóa thông tin tức thì (thời tiết/giá/tin tức/hôm nay...) → SEARCH.
 *  3. Còn lại → chat trực tiếp LLM.
 */
object IntentRouter {

    private val SHORT_TALK = setOf(
        "chào", "xin chào", "alo", "hello", "hi",
        "em là ai", "bạn là ai", "bạn tên gì", "tên bạn là gì",
        "cảm ơn", "thank you", "khỏe không", "em khỏe không",
        "mời anh nói", "nói tiếp", "bạn có khỏe không", "thế nào",
        "giới thiệu bản thân", "đọc lại", "nghe rõ"
    )

    private val LIVE_KEYWORDS = setOf(
        "thời tiết", "dự báo thời tiết", "hôm nay", "ngày mai", "mới nhất",
        "tin tức", "thời sự", "giá", "giá cả", "khuyến mãi", "mua hàng",
        "lịch trình", "vé máy bay", "vé tàu", "đường đi", "định hướng",
        "khoảng giá", "tỷ giá", "ngoại tệ", "đóng cửa", "mở cửa",
        "bây giờ", "hiện tại", "trong ngày", "cập nhật"
    )

    fun requiresLiveInfo(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        // Python: re.sub(r"[^0-9a-zA-ZÀ-ỹ\s]", " ", text.lowercase()) + collapse
        val normalized = text.lowercase()
            .replace(Regex("[^0-9a-zA-ZÀ-ỹ\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isEmpty()) return false
        if (SHORT_TALK.any { it in normalized }) return false
        if (LIVE_KEYWORDS.any { it in normalized }) return true
        return false
    }
}
