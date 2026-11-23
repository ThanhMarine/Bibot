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

    /** Câu hỏi về chính bot/user — KHÔNG đi web (tránh search "bạn là ai"). */
    private val PERSONAL_PATTERNS = listOf(
        Regex("\\b(bạn|em|mày)\\s+(là|tên|nhớ|biết)\\b"),
        Regex("\\b(tên|tuổi|nghề)\\s+(bạn|em|của bạn|của em)\\b"),
        Regex("\\b(bạn|em)\\s+nhớ\\b")
    )

    /** Câu hỏi tra cứu thông tin nói chung (mở rộng ngoài thời tiết/giá/tin tức). */
    private val INFO_PATTERNS = listOf(
        Regex("\\b(ai|cái gì|gì|thế nào|là gì|là ai|ở đâu|khi nào|vì sao|tại sao|bao nhiêu|nghĩa là gì|định nghĩa|nguồn gốc|lịch sử|cách|phương pháp|so sánh|khác nhau|đúng không|có phải|hay là|kết quả|số liệu|thông tin|tra cứu|tìm hiểu|giải thích|hướng dẫn|mô tả|liệt kê|danh sách|ý nghĩa|nguyên nhân|hậu quả|quy trình|cấu tạo|tác dụng|đặc điểm|sự kiện|nhân vật|khái niệm|thuật ngữ|từ điển|what|who|when|where|why|how|define|dictionary)\\b")
    )

    private fun normalize(text: String): String = text.lowercase()
        .replace(Regex("[^0-9a-zA-ZÀ-ỹ\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun requiresLiveInfo(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        if (SHORT_TALK.any { it in normalized }) return false
        if (LIVE_KEYWORDS.any { it in normalized }) return true
        return false
    }

    /** Router dùng cho Voice Chat: live info + tra cứu thông tin nói chung. */
    fun requiresWebInfo(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        if (SHORT_TALK.any { it in normalized }) return false
        if (PERSONAL_PATTERNS.any { it.containsMatchIn(normalized) }) return false
        if (LIVE_KEYWORDS.any { it in normalized }) return true
        return INFO_PATTERNS.any { it.containsMatchIn(normalized) }
    }
}
