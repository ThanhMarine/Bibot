package com.bibot.app.data.ads

import com.bibot.app.BuildConfig

/**
 * AdMob configuration — thay placeholder bằng giá trị thật từ AdMob console.
 * commercial flavor chỉ có file này; demo flavor không có AdMob dependency.
 */
object AdConfig {
    // ADMOB APP_ID — thay bằng APP_ID từ AdMob console
    const val APP_ID = BuildConfig.ADMOB_APP_ID

    // Ad Unit IDs — HIỆN TẠI dùng test ID của Google (chạy được ngay, không vi phạm invalid traffic).
    // Khi publish: thay bằng ID thật từ AdMob console.
    const val TEST_BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_INTERSTITIAL_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_REWARDED_ID = "ca-app-pub-3940256099942544/5224354917"

    const val BANNER_AD_UNIT_ID = "ca-app-pub-2478233272401556/2757758291"
    const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-2478233272401556/7543181110"
    const val REWARDED_AD_UNIT_ID = "ca-app-pub-2478233272401556/5171420211"

    // May test cua dev (lay tu env AD_TEST_DEVICES luc build, cach nhau bang dau phay).
    // May trong danh sach nay LUON nhan QC test ke ca khi dung ID that -> bam vo tu khong bi khoa.
    // Lay ID: logcat dong "Use RequestConfiguration.Builder().setTestDeviceIds(Arrays.asList(...))".
    val TEST_DEVICE_IDS: List<String> = try {
        com.bibot.app.BuildConfig.AD_TEST_DEVICES.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    // Tan suat quang cao (AD_DESIGN v1.0 — B6.14: reader KHONG interstitial giữa chừng,
    // chỉ banner + rewarded Cloud TTS; cooldown dưới chỉ dùng ngoài reader)
    const val INTERSTITIAL_INTERVAL_MODE_SWITCHES = 3
    const val INTERSTITIAL_COOLDOWN_MS = 30 * 60 * 1000L // 30 phut (chi dung ngoai reader)
    const val REWARDED_COOLDOWN_MS = 30 * 60 * 1000L // 30 phut
}
