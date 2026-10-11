package com.bibot.app.core.ads

/**
 * ReadingPhaseTracker — co so chuyen pha Doc/Ngat 16-20' (giam tan suat 50% tu 2026-10-03).
 * - Chinh: elapsedMs thuc (chiu doi speed 0.5-2.0x). Phu: dem tu/chars (fallback TTS offline).
 * - Khong bao gio ngat giua block: soft-due chi armed, cho het chuong; hard-due ngat o bien cau ke tiep.
 * - 16' ~ 158 blocks (~3000 tu VI / 2600 tu EN / 16000 chars); tran 20' ~ 198 blocks.
 */
class ReadingPhaseTracker {
    var startMs: Long = System.currentTimeMillis()
        private set
    var words: Int = 0
        private set
    var chars: Int = 0
        private set
    var blocks: Int = 0
        private set
    var armed: Boolean = false
        private set

    fun onBlock(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        blocks++
        chars += t.length
        words += t.split(WS).count { it.isNotEmpty() }
    }

    fun elapsedMs(now: Long = System.currentTimeMillis()): Long = now - startMs

    /** Dem theo so ky tu (item Pcm chi giu textLen, khong giu text): uoc tinh 5 ky tu/tu. */
    fun onChars(n: Int) {
        if (n <= 0) return
        blocks++
        chars += n
        words += (n / 5).coerceAtLeast(1)
    }    /** 0 = doc tiep, 1 = soft-due (cho het chuong), 2 = hard-due (ngat bien cau ke tiep). */
    fun level(now: Long = System.currentTimeMillis()): Int {
        val e = elapsedMs(now)
        if (e >= AdPhase.HARD_MS || words >= AdPhase.HARD_WORDS) return 2
        if (e >= AdPhase.SOFT_MS || words >= AdPhase.SOFT_WORDS_VI || chars >= AdPhase.SOFT_CHARS) {
            armed = true
            return 1
        }
        return 0
    }

    /** Dieu kien ngat tai vi tri hien tai: soft can het chuong, hard chi can het cau/block. */
    fun shouldBreakAtBoundary(lv: Int, isChapterEnd: Boolean): Boolean {
        if (lv == 2) return true // caller dam bao dang o bien block (sau awaitAudioDone)
        if (lv == 1 && isChapterEnd) return true
        return false
    }

    fun reset(now: Long = System.currentTimeMillis()) {
        startMs = now
        words = 0
        chars = 0
        blocks = 0
        armed = false
    }

    /** Chuoi chan doan cho log khi ngat (Words/elapsed luc break). */
    fun debugStats(now: Long = System.currentTimeMillis()): String =
        "words=$words chars=$chars blocks=$blocks elapsed=${elapsedMs(now)}ms"

    companion object {
        private val WS = Regex("\\s+")
    }
}

/** Nguong pha — tach rieng de test/không hardcode trong Screen. */
object AdPhase {
    const val SOFT_MS = 16 * 60 * 1000L
    const val HARD_MS = 24 * 60 * 1000L
    const val SOFT_WORDS_VI = 3000
    const val SOFT_WORDS_EN = 2600
    // Dem chung, lay theo lang khi check (EN dung 2600, VI dung 3000).
    const val SOFT_WORDS = 3000
    const val HARD_WORDS = 4400
    const val SOFT_CHARS = 16000
}
