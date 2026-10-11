package com.bibot.app.ui.components.ads

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.bibot.app.data.ads.AdsPreferences
import com.bibot.app.data.ads.AdsState
import com.bibot.app.data.ads.AdManager
import com.bibot.app.data.ads.isVipActive

/**
 * AdsInterstitialHelper — quản lý interstitial ad hiển thị giữa các mode.
 * Logic: hiển thị 1 interstitial mỗi 3 lần chuyển mode, không hiển thị khi voice chat đang active.
 */
@Composable
fun AdsInterstitialHelper(
    activity: Activity,
    adsPreferences: AdsPreferences,
    currentMode: String,
    isVoiceActive: Boolean = false,
    onAdShown: () -> Unit = {}
) {
    val adsState by adsPreferences.adsStateFlow.collectAsState(
        initial = AdsState()
    )

    var modeSwitchCount by remember { mutableStateOf(0) }
    var shouldShowInterstitial by remember { mutableStateOf(false) }

    // Ban commercial VIP (vip_only): tat hoan toan logic dem/show interstitial
    if (!com.bibot.app.BuildConfig.ENABLE_ADS) return

    // Khi chuyển mode (không phải voice đang active, không ad-free)
    LaunchedEffect(currentMode, isVoiceActive, adsState.isAdFree) {
        if (!adsState.isAdFree && !isVoiceActive) {
            modeSwitchCount++
            if (modeSwitchCount >= 3) {
                modeSwitchCount = 0
                shouldShowInterstitial = true
            }
        }
    }

    // Hiển thị interstitial khi shouldShowInterstitial
    LaunchedEffect(shouldShowInterstitial) {
        if (shouldShowInterstitial && AdManager.isReady()) {
            val shown = AdManager.showInterstitial(activity)
            if (shown) {
                onAdShown()
                adsPreferences.incrementAdClick()
                adsPreferences.setAdLastShowTime(System.currentTimeMillis())
            }
            shouldShowInterstitial = false
        }
    }
}

/**
 * AdsRewardedHelper — quản lý rewarded ad cho premium features.
 * User xem ad → nhận reward (ví dụ: extra daily messages, ad-free 24h).
 */
@Composable
fun AdsRewardedHelper(
    activity: Activity,
    adsPreferences: AdsPreferences,
    onRewardedClaimed: () -> Unit = {}
) {
    val adsState by adsPreferences.adsStateFlow.collectAsState(
        initial = AdsState()
    )

    // Load rewarded ad khi screen mo — bo qua o ban commercial VIP
    LaunchedEffect(Unit) {
        if (!com.bibot.app.BuildConfig.ENABLE_ADS) return@LaunchedEffect
        if (!adsState.isAdFree) {
            AdManager.loadRewarded(activity)
        }
    }

    // Cleanup khi ad-free
    LaunchedEffect(adsState.isAdFree) {
        if (adsState.isAdFree) {
            AdManager.release()
        }
    }
}

/**
 * Xác định user có đủ điều kiện xem rewarded không.
 * Quy tắc: tối đa 1 rewarded/30 phút, không xem khi đang voice.
 */
fun canShowRewarded(adsState: AdsState): Boolean {
    try {
        if (!com.bibot.app.BuildConfig.ENABLE_ADS) return false
        // VIP da co Cloud + sach ads: an nut doi thuong de khoi roi
        if (isVipActive(adsState)) return false
    } catch (_: Exception) { return false }
    val now = System.currentTimeMillis()
    val cooldownMs = 30 * 60 * 1000L // 30 phút
    return !adsState.isAdFree &&
           (now - adsState.adLastShowTime) > cooldownMs
}
