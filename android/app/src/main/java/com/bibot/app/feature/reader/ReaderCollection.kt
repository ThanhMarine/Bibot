package com.bibot.app.feature.reader

import com.bibot.app.data.books.BookMeta

/**
 * R0 — Một màn đọc duy nhất cho mọi kho (F0.2).
 * Mỗi collection chỉ là config: filter + ngôn ngữ + cờ ads/quản lý + chuỗi UI.
 * Tham khảo (THAMKHAO) = thêm folder ID theo đúng pattern VietBooksCatalog (0 màn mới).
 */
enum class ReaderCollection(
    /** key ổn định để lưu pref/progress (en/story/cotich/thamkhao/local_pdf). */
    val key: String,
    /** Emoji header màn đọc. */
    val headerEmoji: String,
    /** null = theo từng sách (EN đa ngữ); ngược lại ép ngôn ngữ cố định (vi). */
    val fixedLang: String?,
    /** Cổ tích tắt ads hẳn (giữ hành vi StoryScreen cũ). */
    val adsEnabled: Boolean,
    /** EN (+ Tham khảo/PDF local sau này): có nút Refresh/Scan/URL/Delete. */
    val allowManage: Boolean,
    /** true = dùng chuỗi story (Đang nạp truyện/Đang ở đầu truyện/Kể/Đã kể xong). */
    val useStoryStrings: Boolean,
    /** Fallback title khi build .prep (Story cũ hardcode "Truyện", Book dùng book.title). */
    val fallbackTitle: String?,
    /** Prefix audio session (book_/story_) — giữ để không đụng cache cũ. */
    val audioPrefix: String,
) {
    EN(
        key = "en",
        headerEmoji = "\uD83D\uDCCA",
        fixedLang = null,
        adsEnabled = true,
        allowManage = true,
        useStoryStrings = false,
        fallbackTitle = null,
        audioPrefix = "book_",
    ),
    STORY_VI(
        key = "story",
        headerEmoji = "\uD83D\uDCD6",
        fixedLang = "vi",
        adsEnabled = true,
        allowManage = false,
        useStoryStrings = true,
        fallbackTitle = "Truyện",
        audioPrefix = "story_",
    ),
    COTICH(
        key = "cotich",
        headerEmoji = "\uD83E\uDDDA",
        fixedLang = "vi",
        adsEnabled = false,
        allowManage = false,
        useStoryStrings = true,
        fallbackTitle = "Truyện",
        audioPrefix = "story_",
    ),
    THAMKHAO(
        key = "thamkhao",
        headerEmoji = "\uD83D\uDCDA",
        fixedLang = "vi",
        adsEnabled = true,
        allowManage = true,
        useStoryStrings = false,
        fallbackTitle = null,
        audioPrefix = "book_",
    ),
    LOCAL_PDF(
        key = "local_pdf",
        headerEmoji = "\uD83D\uDCC4",
        fixedLang = null,
        adsEnabled = true,
        allowManage = true,
        useStoryStrings = false,
        fallbackTitle = null,
        audioPrefix = "book_",
    );

    /** Ngôn ngữ TTS thực tế cho 1 sách (đa ngữ EN, cố định vi cho story/cổ tích/tham khảo). */
    fun resolveLang(book: BookMeta): String = fixedLang ?: book.lang

    /** Lọc danh sách theo đúng hành vi cũ của từng module (Book vs Story). */
    fun filter(book: BookMeta): Boolean = when (this) {
        EN -> !(book.lang == "vi" && book.source == "pdrive")
        STORY_VI -> book.lang == "vi" && book.collection != "cotich" &&
            book.collection != "thamkhao" &&
            (book.mimeType.contains("markdown") || book.mimeType.contains("plain") ||
                book.mimeType.contains("pdf") || book.mimeType.contains("epub") ||
                book.title.endsWith(".md") || book.title.endsWith(".txt") ||
                book.title.endsWith(".pdf", ignoreCase = true) ||
                book.title.endsWith(".epub", ignoreCase = true))
        COTICH -> book.collection == "cotich"
        THAMKHAO -> book.collection == "thamkhao"
        LOCAL_PDF -> book.source == "local" &&
            (book.mimeType.contains("pdf") || book.mimeType.contains("epub") ||
                book.title.endsWith(".pdf", ignoreCase = true) ||
                book.title.endsWith(".epub", ignoreCase = true))
    }

    companion object {
        fun fromKey(key: String?): ReaderCollection =
            values().firstOrNull { it.key == key } ?: EN

        /** Giữ tương thích với param collection:String cũ của StoryScreen. */
        fun fromLegacy(collection: String?): ReaderCollection = when (collection) {
            "cotich" -> COTICH
            "story" -> STORY_VI
            "thamkhao" -> THAMKHAO
            "local_pdf" -> LOCAL_PDF
            else -> EN
        }
    }
}
