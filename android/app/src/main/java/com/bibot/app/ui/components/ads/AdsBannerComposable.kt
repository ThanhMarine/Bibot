package com.bibot.app.ui.components.ads

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 */
@Composable
fun AdsBanner(
    adsPreferences: AdsPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (!com.bibot.app.BuildConfig.ENABLE_ADS) {
        Spacer(modifier = modifier.height(0.dp))
        return
    }
    val adsState by adsPreferences.adsStateFlow.collectAsState(
        initial = AdsState()
    )

    val showBanner = adsState.isBannerEnabled && !adsState.isAdFree && !isVipActive(adsState)

    if (showBanner) {
        AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = { ctx ->
                val adView = AdView(ctx).apply {
                    setAdSize(getAdaptiveAdSize(ctx))
                    adUnitId = AdConfig.BANNER_AD_UNIT_ID
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                    loadAd(AdRequest.Builder().build())
                }
                adView
            },
            update = { adView ->
                val newSize = getAdaptiveAdSize(adView.context)
                if (adView.adSize != newSize) {
                    adView.setAdSize(newSize)
                }
            }
        )
    } else {
        Spacer(modifier = modifier.height(0.dp))
    }
}

/**
 * Lấy adaptive banner size phù hợp với orientation và màn hình hiện tại.
 * - Portrait: full width, anchored adaptive
 * - Landscape: chiều rộng màn hình, chiều cao tự động
 * - Tablet: hỗ trợ cả portrait và landscape
 */
fun getAdaptiveAdSize(context: Context): AdSize {
    val metrics = DisplayMetrics()
    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        .defaultDisplay.getMetrics(metrics)
    
    val screenWidthPx = metrics.widthPixels
    val screenHeightPx = metrics.heightPixels
    val density = metrics.density
    
    val screenWidthDp = (screenWidthPx / density).toInt()
    val isLandscape = screenWidthPx > screenHeightPx
    
    return if (isLandscape) {
        AdSize.getLandscapeAnchoredAdaptiveBannerAdSize(context, screenWidthDp)
    } else {
        AdSize.getPortraitAnchoredAdaptiveBannerAdSize(context, screenWidthDp)
    }
}

/**
 * Helper để kiểm tra banner có hiển thị không.
 */
fun isBannerVisible(adsState: AdsState): Boolean =
    try {
        com.bibot.app.BuildConfig.ENABLE_ADS && adsState.isBannerEnabled && !adsState.isAdFree && !isVipActive(adsState)
    } catch (_: Exception) { false }