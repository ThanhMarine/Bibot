package com.bibot.app.core.ads

/**
 * AdCopy — noi dung quang cao VI/EN + cau bonus chuyen pha (CHECKLIST 2026-10-01).
 * Eng Books (hoc sinh/sinh vien): full EN. Cotich: giong ke nhe + badge gia dinh.
 */
object AdCopy {
    fun bonusChapter(lang: String, chapterTitle: String): String =
        if (lang == "en") "End of chapter $chapterTitle. Please relax with a short ad break!"
        else "Hết chương $chapterTitle, xin mời bạn thư giãn với ít phút quảng cáo!"

    fun nativeLabel(lang: String): String = if (lang == "en") "Ad" else "Quang cao"

    fun familyBadge(lang: String): String = if (lang == "en") "Family safe" else "Danh cho gia dinh"

    fun interstitialCountdown(lang: String, seconds: Int): String =
        if (lang == "en") "Ad closes in ${seconds}s" else "Quang cao se tat sau ${seconds}s"

    fun rewardedUnlock(lang: String): String =
        if (lang == "en") "Watch 15s to unlock 3 chapters in premium voice"
        else "Xem 15s de mo 3 chuong giong cao cap"

    fun chapterInfo(lang: String, chapter: Int, totalChapters: Int, sent: Int, totalSents: Int): String =
        if (lang == "en") "Chapter $chapter/$totalChapters - Sentence $sent/$totalSents"
        else "Chuong $chapter/$totalChapters - Cau $sent/$totalSents"
}

/**
 * KidsSafe — cau hinh goc phu huynh/tre em cho module Cotich (Families Policy).
 * - tagForChildDirectedTreatment=true, max rating G, tat personalized.
 * - Tat interstitial video co tieng trong Cotich; chi 1 native tinh/2 chu ky + rewarded co parental-gate.
 */
object KidsSafe {
    const val TAG_FOR_CHILD_DIRECTED_TREATMENT = true
    const val MAX_AD_CONTENT_RATING = "G"
    const val PERSONALIZED_ADS_ENABLED_COTICH = false
    /** So chu ky Doc/Ngat cho 1 native trong Cotich (thua 2x so voi sach thuong). */
    const val NATIVE_EVERY_N_PHASES_COTICH = 2
    /** Rewarded o Cotich can giu nut 2s (parental gate). */
    const val REWARDED_HOLD_MS_COTICH = 2000L
}
