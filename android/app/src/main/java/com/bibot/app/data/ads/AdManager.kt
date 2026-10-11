package com.bibot.app.data.ads

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.initialization.InitializationStatus
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * AdManager — singleton quản lý AdMob cho Bibot.
 * Thiết kế nhẹ: chỉ load khi cần, release sau khi hiển thị.
 * RAM overhead <5MB.
 */
object AdManager {
    private const val TAG = "AdManager"
    // Ban commercial la VIP dac biet (vip_only): ENABLE_ADS=false -> moi logic quang cao la no-op.
    // Ban demo la ban chinh thuc mien phi: ENABLE_ADS=true -> ads hoat dong binh thuong.
    private fun adsEnabled(): Boolean = try {
        com.bibot.app.BuildConfig.ENABLE_ADS
    } catch (_: Exception) { false }
    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null
    private var isInitialized = false

    /** Callback khi interstitial hiển thị thành công → user click */
    var onAdClicked: (() -> Unit)? = null

    /** Callback khi rewarded ad xem xong → claim reward */
    var onRewardedClaimed: (() -> Unit)? = null

    /** Callback khi QC hien that bai (show that bai) → caller tu resume doc de khong ket */
    var onAdFailed: (() -> Unit)? = null

    /**
     * Khởi tạo AdMob. Gọi 1 lần từ Application hoặc MainActivity.
     */
    fun initialize(context: android.content.Context) {
        if (!adsEnabled()) return
        if (isInitialized) return
        MobileAds.initialize(context) { _: InitializationStatus ->
            isInitialized = true
            // May test luon nhan QC test (an toan bam) ke ca khi dung ID that
            try {
                val testIds = AdConfig.TEST_DEVICE_IDS
                if (testIds.isNotEmpty()) {
                    MobileAds.setRequestConfiguration(
                        com.google.android.gms.ads.RequestConfiguration.Builder()
                            .setTestDeviceIds(testIds)
                            .build()
                    )
                }
            } catch (_: Exception) {}
            Log.d(TAG, "AdMob initialized")
        }
    }

    /**
     * Load interstitial ad (gọi trước khi hiển thị).
     */
    fun loadInterstitial(activity: Activity) {
        if (!adsEnabled() || !isInitialized) return
        val request = AdRequest.Builder().build()
        InterstitialAd.load(activity, AdConfig.INTERSTITIAL_AD_UNIT_ID, request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    Log.d(TAG, "Interstitial loaded")
                }
            })
    }

    /**
     * Hiển thị interstitial. Trả về true nếu có ad để hiển thị.
     */
    fun showInterstitial(activity: Activity): Boolean {
        if (!adsEnabled()) return false
        val ad = interstitialAd ?: return false
        ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadInterstitial(activity)
                onAdClicked?.invoke()
            }
            override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                interstitialAd = null
                onAdFailed?.invoke()
            }
        }
        ad.show(activity)
        return true
    }

    /**
     * Load rewarded ad.
     */
    fun loadRewarded(activity: Activity) {
        if (!adsEnabled() || !isInitialized) return
        val request = AdRequest.Builder().build()
        RewardedAd.load(activity, AdConfig.REWARDED_AD_UNIT_ID, request,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    Log.d(TAG, "Rewarded loaded")
                }
            })
    }

    /**
     * Hiển thị rewarded ad. Callback whenEarnedCalled khi user xem xong.
     */
    fun showRewarded(activity: Activity): Boolean {
        if (!adsEnabled()) return false
        val ad = rewardedAd ?: return false
        ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewarded(activity)
            }
            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                rewardedAd = null
                onAdFailed?.invoke()
            }
        }
        ad.show(activity) { onRewardedClaimed?.invoke() }
        return true
    }

    /**
     * Load banner vào AdView. Gọi khi screen hiển thị.
     */
    fun loadBanner(adView: AdView) {
        if (!adsEnabled() || !isInitialized) return
        val request = AdRequest.Builder().build()
        adView.setAdUnitId(AdConfig.BANNER_AD_UNIT_ID)
        adView.setAdSize(com.google.android.gms.ads.AdSize.BANNER)
        adView.loadAd(request)
    }

    /**
     * Giải phóng ad resources. Gọi khi app vào background hoặc khi cần free RAM.
     */
    fun release() {
        interstitialAd?.let {
            it.fullScreenContentCallback = null
            interstitialAd = null
        }
        rewardedAd?.let {
            it.fullScreenContentCallback = null
            rewardedAd = null
        }
        Log.d(TAG, "Ad resources released")
    }

    /** Kiểm tra AdMob đã init chưa */
    fun isReady(): Boolean = isInitialized
}
