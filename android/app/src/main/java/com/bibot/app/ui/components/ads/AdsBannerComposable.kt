package com.bibot.app.ui.components.ads

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.bibot.app.data.ads.AdsPreferences
import com.bibot.app.data.ads.AdConfig
import com.bibot.app.data.ads.AdsState
import com.bibot.app.data.ads.isVipActive

/**
 * Banner ad composable — hiển thị AdMob adaptive banner.
 * - Portrait: full width màn hình, chiều cao tự động theo tỷ lệ
 * - Landscape: chiều rộng màn hình, chiều cao tự động
 * - Tablet: hỗ trợ cả portrait và landscape
 * Tự động hide khi ad-free hoặc user tắt trong Settings.
 *
 * G1 (fix crash xoay màn hình): AdMob cấm `setAdSize` sau `loadAd` — bản cũ gọi
 * trong `update` nên recompose lúc xoay là FATAL. Nay key `AndroidView` theo
 * (widthDp, landscape): xoay → factory dựng AdView mới đúng size + loadAd mới,
 * view cũ `destroy()` qua `onRelease`. Không còn `defaultDisplay` deprecated.
 */
@Composable
fun AdsBanner(
    adsPreferences: AdsPreferences,
    modifier: Modifier = Modifier
) {
    if (!com.bibot.app.BuildConfig.ENABLE_ADS) {
        Spacer(modifier = modifier.height(0.dp))
        return
    }
    val adsState by adsPreferences.adsStateFlow.collectAsState(
        initial = AdsState()
    )

    val showBanner = adsState.isBannerEnabled && !adsState.isAdFree && !isVipActive(adsState)

    if (showBanner) {
        val config = LocalConfiguration.current
        val widthDp = config.screenWidthDp.coerceAtLeast(320)
        val isLandscape = config.screenWidthDp > config.screenHeightDp
        androidx.compose.runtime.key(widthDp, isLandscape) {
            Box(
                modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    AdView(ctx).apply {
                        setAdSize(getAdaptiveAdSize(ctx, widthDp, isLandscape))
                        adUnitId = AdConfig.BANNER_AD_UNIT_ID
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                        loadAd(AdRequest.Builder().build())
                    }
                },
                // Xoay/recreate: hủy view cũ (tránh rò WebView/AdView).
                onRelease = { adView ->
                    try { adView.destroy() } catch (_: Exception) {}
                }
            )
            }
        }
    } else {
        Spacer(modifier = modifier.height(0.dp))
    }
}

/**
 * Lấy adaptive banner size phù hợp với orientation và màn hình hiện tại.
 * widthDp/isLandscape lấy từ Compose LocalConfiguration (không dùng
 * `defaultDisplay` deprecated — sập trên API mới khi display đã detach).
 */
fun getAdaptiveAdSize(context: Context, widthDp: Int, isLandscape: Boolean): AdSize {
    val w = widthDp.coerceAtLeast(320)
    return if (isLandscape) {
        AdSize.getLandscapeAnchoredAdaptiveBannerAdSize(context, w)
    } else {
        AdSize.getPortraitAnchoredAdaptiveBannerAdSize(context, w)
    }
}

/**
 * Helper để kiểm tra banner có hiển thị không.
 */
fun isBannerVisible(adsState: AdsState): Boolean =
    try {
        com.bibot.app.BuildConfig.ENABLE_ADS && adsState.isBannerEnabled && !adsState.isAdFree && !isVipActive(adsState)
    } catch (_: Exception) { false }