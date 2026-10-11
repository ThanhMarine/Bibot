package com.bibot.app.core.tts

import android.content.Context
import com.bibot.app.data.store.BibotPrefs

/**
 * A1.4 — Voice-resolve TRƯỚC khi plan (port commit openreader `0b4d559b`).
 *
 * Bug gốc: reader publish plan với voice='' rồi thế fallback sau → đổi
 * settingsHash/plan/cache identity → mất trạng thái (vd lỗi storage).
 * Fix: chờ voice cuối (kể cả fallback) rồi mới tính hash + request plan.
 *
 * Ở Bibot, "plan" = tham số chain TTS snapshot lúc bắt đầu đọc
 * (LaunchedEffect(isReading) trong ReaderScreen): snapshot 1 lần, không
 * re-plan giữa chừng (jump chỉ đổi ordinal). Voice rỗng không thể xảy ra —
 * voiceId luôn non-empty — nhưng resolve tập trung để:
 *  1. Mọi màn dùng 1 chuỗi fallback duy nhất (Piper → Cloud → Offline).
 *  2. Báo rõ khi degraded (thiếu model/key) qua [degraded] thay vì im lặng.
 *  3. Cung cấp voiceId/format ổn định cho settingsHash (A1.2).
 */
enum class TtsEngine { PIPER, CLOUD, OFFLINE }

data class ResolvedVoice(
    /** Engine thắng sau resolve. */
    val engine: TtsEngine,
    /** Id ổn định cho settingsHash: "piper:vi" | tên voice Cloud | "offline:vi". */
    val voiceId: String,
    /** Tốc độ hiệu dụng (storySpeed cho vi, voiceSpeed cho en). */
    val speed: Float,
    /** Định dạng bytes engine này sinh: piper/offline=wav, cloud=mp3. */
    val format: String,
    /** true = prefer của user không đạt (thiếu model/key) → UI nên báo. */
    val degraded: Boolean
)

object TtsVoiceResolve {
    /**
     * @param lang "vi" | "en" (đã resolve theo sách).
     * @param rewardedCloud true khi chương này được Cloud qua rewarded-ad
     *        (giữ đúng kinh tế ads cũ: hết lượt thì thôi, không re-plan).
     * @param vipBypass true khi VIP còn hạn (VIP mở Cloud khi có key,
     *        kể cả priority=offline — giữ hành vi cũ).
     */
    fun resolve(
        lang: String,
        prefs: BibotPrefs,
        context: Context,
        rewardedCloud: Boolean = false,
        vipBypass: Boolean = false
    ): ResolvedVoice {
        val piperReady = try {
            prefs.storyPreferPiper && PiperTtsHelper.isVoiceComplete(context, lang)
        } catch (_: Exception) { false }
        return resolvePure(
            lang = lang,
            storySpeed = prefs.storySpeed,
            voiceSpeed = prefs.voiceSpeed,
            cloudKeyPresent = prefs.cloudTtsApiKey.isNotBlank(),
            ttsPriority = prefs.ttsPriority,
            preferPiper = prefs.storyPreferPiper,
            piperReady = piperReady,
            rewardedCloud = rewardedCloud,
            vipBypass = vipBypass
        )
    }

    /**
     * Hàm thuần (không Context — unit-test được): cùng 1 input luôn ra 1 engine.
     * Bất biến sau fix 0b4d559b: voiceId KHÔNG BAO GIỜ rỗng.
     */
    fun resolvePure(
        lang: String,
        storySpeed: Float,
        voiceSpeed: Float,
        cloudKeyPresent: Boolean,
        ttsPriority: String,
        preferPiper: Boolean,
        piperReady: Boolean,
        rewardedCloud: Boolean = false,
        vipBypass: Boolean = false
    ): ResolvedVoice {
        val speed = if (lang == "vi") storySpeed else voiceSpeed
        val cloudAllowed = (ttsPriority != "offline" || vipBypass || rewardedCloud) && cloudKeyPresent
        val cloudVoice = if (lang == "en") CloudTtsBridge.DEFAULT_EN_VOICE
        else CloudTtsBridge.DEFAULT_VI_VOICE
        return when {
            preferPiper && piperReady ->
                ResolvedVoice(TtsEngine.PIPER, "piper:$lang", speed, "wav", degraded = false)
            cloudAllowed -> ResolvedVoice(
                TtsEngine.CLOUD, cloudVoice, speed, "mp3",
                degraded = preferPiper // muốn Piper mà thiếu model
            )
            else -> ResolvedVoice(
                TtsEngine.OFFLINE, "offline:$lang", speed, "wav",
                degraded = preferPiper || ttsPriority != "offline"
            )
        }
    }
}
