package com.bibot.app.data.store

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bot Prompt chính thức — cá nhân hoá trong ⚙️ Cài đặt.
 * Template hỗ trợ placeholder: {{persona}}, {{datetime}}, {{style}}, {{tone}}, {{maxWords}}
 * Mặc định: "Em là Bi", friendly/warm, 30 từ — port từ internal/config/config.go:51
 */
object BotPromptManager {
    const val DEFAULT_TEMPLATE = "Bạn là Bi, trợ lý giọng nói Bibot. Luôn xưng \"{{persona}}\", trả lời tiếng Việt, dưới {{maxWords}} từ, {{style}}, {{tone}}, không markdown/link/emoji trong TTS. Chỉ dùng web search cho thời tiết/giá/tin tức. Ngày/giờ hiện tại: {{datetime}}."

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

    fun buildFromPrefs(prefs: BibotPrefs): String = build(
        template = prefs.botPrompt,
        persona = prefs.persona,
        style = prefs.botStyle,
        tone = prefs.botTone,
        maxWords = prefs.botMaxWords,
        language = prefs.language
    )

    fun preview(prefs: BibotPrefs): String = buildFromPrefs(prefs).take(220) + if (buildFromPrefs(prefs).length > 220) "..." else ""
}
