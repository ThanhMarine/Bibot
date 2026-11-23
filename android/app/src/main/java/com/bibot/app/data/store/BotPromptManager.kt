package com.bibot.app.data.store

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bot Prompt chính thức — cá nhân hoá trong Cài đặt.
 * Template hỗ trợ placeholder: {{persona}}, {{datetime}}, {{style}}, {{tone}}, {{maxWords}}
 * Mặc định: "Em là Bi", friendly/warm, 30 từ — port từ internal/config/config.go:51
 */
object BotPromptManager {
    const val DEFAULT_TEMPLATE = "Bạn là trợ lý giọng nói Bibot. Xưng \"{{persona}}\" CHỈ khi tự giới thiệu hoặc được hỏi tên; các lượt sau trò chuyện tự nhiên, không lặp lại tên hay câu mở đầu. Trả lời tiếng Việt, dưới {{maxWords}} từ, {{style}}, {{tone}}, không markdown/link/emoji trong TTS. Dùng web search khi cần tra cứu; nếu có [Kết quả web] thì bám sát nguồn. Ngày/giờ hiện tại: {{datetime}}."

    /** Template cũ từng lưu trên máy — tự nâng cấp khi đọc prefs (giữ nguyên template user tự sửa). */
    const val LEGACY_DEFAULT_TEMPLATE = "Bạn là Bi, trợ lý giọng nói Bibot. Luôn xưng \"{{persona}}\", trả lời tiếng Việt, dưới {{maxWords}} từ, {{style}}, {{tone}}, không markdown/link/emoji trong TTS. Chỉ dùng web search cho thời tiết/giá/tin tức. Ngày/giờ hiện tại: {{datetime}}."

    /**
     * Luật chống ảo giác — HARD-CODE, tự gắn vào MỌI prompt (user không sửa được).
     * Fix 2026-09-30: bot hay bịa tên/số liệu + nhại memory rác ("Bạn tên là gì.").
     */
    const val HARD_RULES = "Quy tắc bắt buộc: chỉ trả lời đúng điều được hỏi, " +
        "KHÔNG bịa tên người, số liệu, địa chỉ, ngày tháng, giá cả; " +
        "mục Ghi nhớ chỉ dùng khi user hỏi trực tiếp về bản thân, không suy diễn thêm; " +
        "không biết thì nói \"Em chưa rõ, bạn nói thêm giúp em nhé.\", không đoán; " +
        "số liệu thời tiết/giá/tin tức CHỈ lấy từ Kết quả web, không có thì nói không có dữ liệu."

    val STYLE_OPTIONS = listOf(
        "friendly" to "Thân thiện",
        "formal" to "Trang trọng",
        "humorous" to "Hài hước",
        "concise" to "Cô đọng",
        "storyteller" to "Kể chuyện"
    )
    val TONE_OPTIONS = listOf(
        "warm" to "Ấm áp",
        "professional" to "Chuyên nghiệp",
        "playful" to "Vui tươi",
        "calm" to "Điềm đạm"
    )
    val MAX_WORDS_OPTIONS = listOf(20, 30, 50, 80, 120)
    val SEARCH_WORDS_OPTIONS = listOf(30, 50, 80, 120)
    const val SEARCH_BRIEF_LABEL = "Ngắn gọn và đủ ý"

    fun styleLabel(v: String) = STYLE_OPTIONS.find { it.first == v }?.second ?: v
    fun toneLabel(v: String) = TONE_OPTIONS.find { it.first == v }?.second ?: v

    fun build(
        template: String = DEFAULT_TEMPLATE,
        persona: String = "Em là Bi",
        style: String = "friendly",
        tone: String = "warm",
        maxWords: Int = 30,
        language: String = "vi"
    ): String {
        val dt = SimpleDateFormat("EEEE, dd/MM/yyyy, HH:mm", Locale("vi", "VN")).format(Date())
        val styleVi = styleLabel(style)
        val toneVi = toneLabel(tone)
        var res = template
        res = res.replace("{{persona}}", persona)
        res = res.replace("{{datetime}}", dt)
        res = res.replace("{{style}}", styleVi)
        res = res.replace("{{tone}}", toneVi)
        res = res.replace("{{maxWords}}", maxWords.toString())
        // Fallback nếu template cũ không có placeholder mới
        if ("{{" in res) res = res.replace(Regex("\\{\\{.*?}}"), "")
        // Ngôn ngữ EN
        if (language == "en") {
            res = res.replace("Bạn là Bi", "You are Bi")
            res = res.replace("trả lời tiếng Việt", "reply in English")
        }
        res = res.trim()
        // Luật cứng luôn gắn cuối (kể cả template user tự sửa) — chống ảo giác
        if (HARD_RULES !in res) res = "$res\n$HARD_RULES"
        return res
    }

    /**
     * Prompt ĐỘNG cho lượt có web search: giới hạn số từ + chế độ ngắn gọn đủ ý.
     * Gắn thêm vào system prompt khi có [Kết quả web].
     */
    fun searchAnswerInstruction(maxWords: Int, brief: Boolean): String {
        val cap = maxWords.coerceIn(20, 120)
        return if (brief) {
            "Khi có [Kết quả web]: hãy đọc lại thông tin đã tổng hợp trong tối đa $cap từ, " +
                "ngắn gọn và đủ ý, bám sát dữ liệu nguồn, không thêm chi tiết ngoài nguồn."
        } else {
            "Khi có [Kết quả web]: trả lời dưới $cap từ, chỉ dùng dữ liệu nguồn, không bịa số liệu."
        }
    }

    fun buildFromPrefs(prefs: BibotPrefs): String = build(
        template = prefs.botPrompt,
        persona = prefs.persona,
        style = prefs.botStyle,
        tone = prefs.botTone,
        maxWords = prefs.botMaxWords,
        language = prefs.language
    )

    fun preview(prefs: BibotPrefs): String = buildFromPrefs(prefs).take(220) + if (buildFromPrefs(prefs).length > 220) "..." else ""

    // B5.2: prompt tóm tắt sách versioned (promptVer đi vào key cache + doc wiki;
    // prompt mới KHÔNG đè bản cũ — mỗi ver là 1 doc/cache riêng).
    const val SUMMARY_PROMPT_VER = 1
    const val SUMMARY_SYS_V1 =
        "Bạn là trợ lý tóm tắt sách Bibot. Tóm tắt trung thực bằng tiếng Việt, " +
            "chỉ dùng thông tin trong đoạn trích, KHÔNG bịa tên/sự kiện; " +
            "không biết thì bỏ qua thay vì đoán. Trả về đoạn văn ngắn, không markdown."
    const val SUMMARY_MAX_CHARS = 6000 // cap input/chương (chặn đốt token sách dày)
    const val SUMMARY_MAX_OUTPUT_TOKENS = 512

    /** Prompt user cho tóm tắt 1 chương: tiêu đề + nội dung (đã cắt cap). */
    fun summaryChapterUser(title: String, text: String): String {
        val body = text.take(SUMMARY_MAX_CHARS)
        return "Tóm tắt chương \"$title\" dưới đây trong 5-8 câu, nêu ý chính và " +
            "tên/sự kiện quan trọng:\n\n$body"
    }

    /** Prompt user cho tóm tắt cấp sách (map-reduce): dồn các tóm tắt chương. */
    fun summaryBookUser(bookTitle: String, chapterSummaries: List<String>): String {
        val joined = chapterSummaries.joinToString("\n\n").take(SUMMARY_MAX_CHARS * 2)
        return "Dưới đây là tóm tắt từng chương của sách \"$bookTitle\". " +
            "Hãy dồn thành tóm tắt toàn sách trong 8-12 câu (ý chính, tuyến truyện, " +
            "kết thúc nếu có), không bịa thêm:\n\n$joined"
    }
}
