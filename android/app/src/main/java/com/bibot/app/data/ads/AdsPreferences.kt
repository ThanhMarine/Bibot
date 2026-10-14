package com.bibot.app.data.ads

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.bibotAdsDataStore: DataStore<Preferences> by preferencesDataStore(name = "bibot_ads")

object AdPrefKeys {
    val AD_FREE = booleanPreferencesKey("ad_free")
    val AD_CLICK_COUNT = longPreferencesKey("ad_click_count")
    val AD_LAST_SHOW_TIME = longPreferencesKey("ad_last_show_time")
    val AD_BANNER_ENABLED = booleanPreferencesKey("ad_banner_enabled")
    val AD_REWARDED_CLAIMED = booleanPreferencesKey("ad_rewarded_claimed")
    val REWARDED_CLOUD_CHAPTERS = intPreferencesKey("rewarded_cloud_chapters")
    // B5.4: lượt tóm tắt AI đổi từ rewarded ad (mirror cloud chapters, trần 9)
    val REWARDED_SUMMARY = intPreferencesKey("rewarded_summary")
    val VIP_TIER = stringPreferencesKey("vip_tier")
    val VIP_UNTIL = longPreferencesKey("vip_until")
    val AD_DAILY_QUOTA_EXHAUSTED = booleanPreferencesKey("ad_daily_quota_exhausted")
    /** Hash SHA-256 (chuẩn hoá) của các mã VIP đã đổi — chống tái dùng 1 mã cộng dồn vô hạn. */
    val VIP_USED_CODES = stringSetPreferencesKey("vip_used_codes")
    /** Entitlement ký từ relay (JSON) — nguồn grace offline (I2). */
    val VIP_ENT = stringPreferencesKey("vip_entitlement_json")
    /** Tier lúc verify online ("month"/"lifetime") — đi kèm entitlement. */
    val VIP_ENT_TIER = stringPreferencesKey("vip_ent_tier")
    /** serverTime lần verify online cuối. */
    val VIP_LAST_CHECK = longPreferencesKey("vip_last_check_ms")
    /** Max thời gian từng thấy (neo chống vặn đồng hồ ngược). */
    val VIP_MAX_SEEN = longPreferencesKey("vip_max_seen_ms")
    /** Hash mã đã verify online (đổi mã → bắt buộc online lại). */
    val VIP_CODE_HASH = stringPreferencesKey("vip_code_hash")
}

/** Tier lưu trong DataStore (khớp SettingsScreen.VipContent + VipCode). */
const val VIP_TIER_MONTH = "month"
const val VIP_TIER_LIFETIME = "lifetime"
const val VIP_LIFETIME_UNTIL = Long.MAX_VALUE
/** Cộng dồn tối đa ~12 tháng (tránh tràn khi nhập nhiều mã). */
const val VIP_MAX_STACK_DAYS = 366L
const val DAY_MILLIS = 86400000L

/** Grace offline (I2): tháng 7 ngày, lifetime recheck 30 ngày, lệch đồng hồ 24h. */
const val VIP_GRACE_MONTH_MS = 7L * DAY_MILLIS
const val VIP_GRACE_LIFE_MS = 30L * DAY_MILLIS
const val VIP_SKEW_MS = DAY_MILLIS

data class AdsState(
    val isAdFree: Boolean = false,
    val adClickCount: Long = 0,
    val adLastShowTime: Long = 0,
    val isBannerEnabled: Boolean = true,
    val isRewardedClaimed: Boolean = false,
    val rewardedCloudChapters: Int = 0,
    val rewardedSummary: Int = 0,
    /** Tier VIP ("month"/"lifetime", rong = khong VIP). Quyen con han khi vipUntil > now. */
    val vipTier: String = "",
    /** Han VIP millis (Long.MAX_VALUE = vinh vien). */
    val vipUntil: Long = 0L,
    val isDailyQuotaExhausted: Boolean = false
)

class AdsPreferences(private val context: Context) {
    val adsStateFlow: Flow<AdsState> = context.bibotAdsDataStore.data.map { p ->
        AdsState(
            isAdFree = p[AdPrefKeys.AD_FREE] ?: false,
            adClickCount = p[AdPrefKeys.AD_CLICK_COUNT] ?: 0,
            adLastShowTime = p[AdPrefKeys.AD_LAST_SHOW_TIME] ?: 0,
            isBannerEnabled = p[AdPrefKeys.AD_BANNER_ENABLED] ?: true,
            isRewardedClaimed = p[AdPrefKeys.AD_REWARDED_CLAIMED] ?: false,
            rewardedCloudChapters = p[AdPrefKeys.REWARDED_CLOUD_CHAPTERS] ?: 0,
            rewardedSummary = p[AdPrefKeys.REWARDED_SUMMARY] ?: 0,
            vipTier = p[AdPrefKeys.VIP_TIER] ?: "",
            vipUntil = p[AdPrefKeys.VIP_UNTIL] ?: 0L,
            isDailyQuotaExhausted = p[AdPrefKeys.AD_DAILY_QUOTA_EXHAUSTED] ?: false
        )
    }

    suspend fun setAdFree(v: Boolean) = context.bibotAdsDataStore.edit { it[AdPrefKeys.AD_FREE] = v }
    suspend fun incrementAdClick() = context.bibotAdsDataStore.edit {
        it[AdPrefKeys.AD_CLICK_COUNT] = (it[AdPrefKeys.AD_CLICK_COUNT] ?: 0) + 1
    }
    suspend fun setAdLastShowTime(v: Long) = context.bibotAdsDataStore.edit { it[AdPrefKeys.AD_LAST_SHOW_TIME] = v }
    suspend fun setBannerEnabled(v: Boolean) = context.bibotAdsDataStore.edit { it[AdPrefKeys.AD_BANNER_ENABLED] = v }
    suspend fun setRewardedClaimed(v: Boolean) = context.bibotAdsDataStore.edit { it[AdPrefKeys.AD_REWARDED_CLAIMED] = v }
    // Rewarded mo giong Cloud theo so chuong (tich luy toi da 9). Doc 1 chuong tru 1.
    suspend fun getRewardedCloudChapters(): Int = try {
        context.bibotAdsDataStore.data.first()[AdPrefKeys.REWARDED_CLOUD_CHAPTERS] ?: 0
    } catch (_: Exception) { 0 }
    suspend fun setRewardedCloudChapters(v: Int) = context.bibotAdsDataStore.edit {
        it[AdPrefKeys.REWARDED_CLOUD_CHAPTERS] = v.coerceAtLeast(0)
    }
    suspend fun claimRewardedCloudChapters(n: Int = 3) = context.bibotAdsDataStore.edit {
        val cur = it[AdPrefKeys.REWARDED_CLOUD_CHAPTERS] ?: 0
        it[AdPrefKeys.REWARDED_CLOUD_CHAPTERS] = (cur + n).coerceAtMost(9)
        it[AdPrefKeys.AD_LAST_SHOW_TIME] = System.currentTimeMillis()
    }
    // B5.4: lượt tóm tắt (đọc 1 chương trừ 1; VIP bypass ở caller)
    suspend fun getRewardedSummary(): Int = try {
        context.bibotAdsDataStore.data.first()[AdPrefKeys.REWARDED_SUMMARY] ?: 0
    } catch (_: Exception) { 0 }
    suspend fun setRewardedSummary(v: Int) = context.bibotAdsDataStore.edit {
        it[AdPrefKeys.REWARDED_SUMMARY] = v.coerceAtLeast(0)
    }
    suspend fun claimRewardedSummary(n: Int = 3) = context.bibotAdsDataStore.edit {
        val cur = it[AdPrefKeys.REWARDED_SUMMARY] ?: 0
        it[AdPrefKeys.REWARDED_SUMMARY] = (cur + n).coerceAtMost(9)
        it[AdPrefKeys.AD_LAST_SHOW_TIME] = System.currentTimeMillis()
    }

    /** Dat quyen VIP (until millis; vinh vien = Long.MAX_VALUE). */
    suspend fun setVip(tier: String, until: Long) = context.bibotAdsDataStore.edit {
        it[AdPrefKeys.VIP_TIER] = tier
        it[AdPrefKeys.VIP_UNTIL] = until
    }
    suspend fun clearVip() = context.bibotAdsDataStore.edit {
        it[AdPrefKeys.VIP_TIER] = ""
        it[AdPrefKeys.VIP_UNTIL] = 0L
    }

    /** Chuẩn hoá mã để hash/so sánh: hoa, bỏ gạch/dấu cách. */
    fun normalizeVipCode(code: String): String =
        code.trim().uppercase().filter { it.isLetterOrDigit() }

    /** SHA-256 hex của mã đã chuẩn hoá (không lưu mã gốc). */
    fun hashVipCode(code: String): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(normalizeVipCode(code).toByteArray(Charsets.UTF_8))
        buildString(bytes.size * 2) { bytes.forEach { b -> append("%02x".format(b)) } }
    } catch (_: Exception) { normalizeVipCode(code) }

    suspend fun isVipCodeUsed(code: String): Boolean = try {
        val h = hashVipCode(code)
        context.bibotAdsDataStore.data.first()[AdPrefKeys.VIP_USED_CODES]?.contains(h) == true
    } catch (_: Exception) { false }

    suspend fun markVipCodeUsed(code: String) = context.bibotAdsDataStore.edit {
        val h = hashVipCode(code)
        val cur = it[AdPrefKeys.VIP_USED_CODES] ?: emptySet()
        it[AdPrefKeys.VIP_USED_CODES] = (cur + h).toList().takeLast(500).toSet()
    }

    /** Entitlement ký từ relay cho grace offline (I2). */
    data class VipAuth(
        val entJson: String,
        val tier: String,
        val until: Long,
        val lastCheck: Long,
        val maxSeen: Long,
        val codeHash: String
    )

    suspend fun saveVipAuth(entJson: String, tier: String, until: Long, lastCheck: Long, maxSeen: Long, codeHash: String) =
        context.bibotAdsDataStore.edit {
            it[AdPrefKeys.VIP_ENT] = entJson
            it[AdPrefKeys.VIP_ENT_TIER] = tier
            it[AdPrefKeys.VIP_LAST_CHECK] = lastCheck
            it[AdPrefKeys.VIP_MAX_SEEN] = maxSeen
            it[AdPrefKeys.VIP_CODE_HASH] = codeHash
        }

    suspend fun loadVipAuth(): VipAuth? = try {
        val p = context.bibotAdsDataStore.data.first()
        val ent = p[AdPrefKeys.VIP_ENT] ?: return null
        VipAuth(
            entJson = ent,
            tier = p[AdPrefKeys.VIP_ENT_TIER] ?: "",
            // until lấy từ AdsState hiện hành (đã setVip lúc online); auth chỉ giữ mốc thời gian.
            until = p[AdPrefKeys.VIP_UNTIL] ?: 0L,
            lastCheck = p[AdPrefKeys.VIP_LAST_CHECK] ?: 0L,
            maxSeen = p[AdPrefKeys.VIP_MAX_SEEN] ?: 0L,
            codeHash = p[AdPrefKeys.VIP_CODE_HASH] ?: ""
        )
    } catch (_: Exception) { null }

    suspend fun touchVipMaxSeen(now: Long) = context.bibotAdsDataStore.edit {
        val cur = it[AdPrefKeys.VIP_MAX_SEEN] ?: 0L
        if (now > cur) it[AdPrefKeys.VIP_MAX_SEEN] = now
    }

    suspend fun clearVipAuth() = context.bibotAdsDataStore.edit {
        it.remove(AdPrefKeys.VIP_ENT)
        it.remove(AdPrefKeys.VIP_ENT_TIER)
        it.remove(AdPrefKeys.VIP_LAST_CHECK)
        it.remove(AdPrefKeys.VIP_MAX_SEEN)
        it.remove(AdPrefKeys.VIP_CODE_HASH)
    }
    suspend fun setDailyQuotaExhausted(v: Boolean) = context.bibotAdsDataStore.edit { it[AdPrefKeys.AD_DAILY_QUOTA_EXHAUSTED] = v }
    suspend fun resetDailyQuota() = context.bibotAdsDataStore.edit { it[AdPrefKeys.AD_DAILY_QUOTA_EXHAUSTED] = false }

    /** Kiểm tra và tự xoá VIP nếu đã hết hạn. Trả về true nếu đã xoá (quay về cơ bản). */
    suspend fun checkAndClearExpiredVip(now: Long = System.currentTimeMillis()): Boolean {
        val cur = try { context.bibotAdsDataStore.data.first() } catch (_: Exception) { return false }
        val until = cur[AdPrefKeys.VIP_UNTIL] ?: 0L
        if (until > 0 && until != VIP_LIFETIME_UNTIL && now > until) {
            try { clearVip() } catch (_: Exception) { return false }
            return true
        }
        // Dọn tier rác: tier còn nhưng until đã 0 (không bao giờ active) -> về cơ bản sạch.
        val tier = cur[AdPrefKeys.VIP_TIER] ?: ""
        if (tier.isNotEmpty() && until <= 0) {
            try { clearVip() } catch (_: Exception) { return false }
            return true
        }
        return false
    }
}

/** VIP con han thi coi nhu ad-free + mo Cloud (can key) o moi gate. */
fun isVipActive(s: AdsState, now: Long = System.currentTimeMillis()): Boolean = s.vipUntil > now

/** Số ngày VIP còn lại (0 = hết/không VIP; Int.MAX_VALUE = vĩnh viễn). */
fun vipDaysLeft(s: AdsState, now: Long = System.currentTimeMillis()): Int {
    if (s.vipUntil == VIP_LIFETIME_UNTIL) return Int.MAX_VALUE
    if (s.vipUntil <= now) return 0
    return ((s.vipUntil - now + DAY_MILLIS - 1) / DAY_MILLIS).toInt()
}
