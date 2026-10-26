package com.bibot.app.data.ads

import android.content.Context
import android.provider.Settings
import com.bibot.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * I2 — Đổi mã VIP qua relay + grace offline.
 *
 * Online: POST relay `/v1/vip/verify` (server giữ HMAC secret) → lưu entitlement ký
 * + mốc thời gian → setVip. Offline: tin entitlement đã lưu nếu còn trong grace
 * (tháng 7 ngày, lifetime recheck 30 ngày), đúng mã đã verify, đồng hồ không bị
 * vặn ngược quá 24h. Đổi mã khác → bắt buộc online.
 *
 * Dev fallback: khi chưa cấu hình relay (BIBOT_LLM_PROXY_URL rỗng) mà build có secret
 * (debug) thì verify local như cũ. Release secret rỗng → server là nguồn duy nhất.
 *
 * Play Integrity Phase 2: integrityToken gửi kèm verify request (hiện trống, server log-only).
 * Quota relay theo entitlement tier thay vì deviceId trần (xử lý ở server relay).
 */
object VipVerifier {

    sealed interface RedeemResult {
        data class VerifiedOnline(
            val months: Int,
            val until: Long,
            val lifetime: Boolean,
            val sharedWarn: Boolean
        ) : RedeemResult

        data class GraceOffline(val until: Long, val lifetime: Boolean) : RedeemResult
        data object NeedOnline : RedeemResult
        data class Invalid(val reason: String) : RedeemResult
    }

    fun deviceId(context: Context): String = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    private fun relayBase(): String = try {
        BuildConfig.BIBOT_LLM_PROXY_URL.trim().trimEnd('/')
    } catch (_: Exception) {
        ""
    }

    private fun appVersion(): String = try {
        BuildConfig.VERSION_NAME
    } catch (_: Exception) {
        ""
    }

    private data class ServerVerdict(
        val valid: Boolean,
        val tier: Int,
        val months: Int,
        val expiry: Long?,
        val reason: String,
        val serverTime: Long,
        val entJson: String,
        val sharedWarn: Boolean
    )

    private sealed interface NetOut {
        data class Ok(val v: ServerVerdict) : NetOut
        data object NetworkFail : NetOut
        data object RateLimited : NetOut
    }

    private fun postVerify(base: String, code: String, did: String): NetOut {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$base/v1/vip/verify").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject()
                .put("code", code)
                .put("deviceId", did)
                .put("appVersion", appVersion())
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            when (conn.responseCode) {
                429 -> NetOut.RateLimited
                in 200..299 -> {
                    val bytes = conn.inputStream.use { it.readBytes() }.take(8192).toByteArray()
                    NetOut.Ok(parseVerdict(String(bytes, Charsets.UTF_8)))
                }
                else -> NetOut.NetworkFail
            }
        } catch (_: Exception) {
            NetOut.NetworkFail
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseVerdict(text: String): ServerVerdict {
        val o = JSONObject(text)
        val ent = o.optJSONObject("entitlement")
        return ServerVerdict(
            valid = o.optBoolean("valid", false),
            tier = o.optInt("tier", 0),
            months = o.optInt("months", 0),
            expiry = if (o.isNull("expiryMillis")) null else o.optLong("expiryMillis", 0L),
            reason = o.optString("reason", ""),
            serverTime = o.optLong("serverTime", System.currentTimeMillis()),
            entJson = ent?.toString() ?: "{}",
            sharedWarn = o.optBoolean("sharedWarn", false)
        )
    }

    suspend fun redeem(context: Context, prefs: AdsPreferences, code: String): RedeemResult =
        withContext(Dispatchers.IO) {
            val input = code.trim()
            if (input.isEmpty()) return@withContext RedeemResult.Invalid("empty")
            val hash = prefs.hashVipCode(input)
            val base = relayBase()
            if (base.isBlank()) {
                // Dev fallback: local verify chỉ khi build có secret (debug).
                val secret = try {
                    BuildConfig.VIP_HMAC_SECRET
                } catch (_: Exception) {
                    ""
                }
                if (secret.isBlank()) return@withContext RedeemResult.NeedOnline
                return@withContext when (val r = VipCode.verify(secret, input, System.currentTimeMillis())) {
                    is VipCode.VipResult.Invalid -> RedeemResult.Invalid(r.reason)
                    is VipCode.VipResult.Valid -> {
                        val exp = if (r.tier == VipCode.TIER_LIFETIME) null
                        else devMonthlyExpiry(r)
                        applyValid(prefs, input, hash, r.tier, r.months, exp, System.currentTimeMillis(), "{\"dev\":true}", false)
                    }
                }
            }
            val did = deviceId(context)
            when (val net = postVerify(base, input, did)) {
                is NetOut.RateLimited -> RedeemResult.Invalid("verify quá nhiều, thử lại mai")
                is NetOut.NetworkFail -> grace(prefs, hash)
                is NetOut.Ok -> {
                    val v = net.v
                    if (!v.valid) {
                        // Mã đang lưu mà server nói invalid (hết hạn/thu hồi) → clear ngay.
                        val stored = try {
                            prefs.loadVipAuth()?.codeHash ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        if (hash.isNotEmpty() && hash == stored) {
                            try {
                                prefs.clearVip()
                                prefs.clearVipAuth()
                            } catch (_: Exception) {
                            }
                        }
                        return@withContext RedeemResult.Invalid(
                            v.reason.ifBlank { "mã không hợp lệ" }
                        )
                    }
                    applyValid(
                        prefs, input, hash, v.tier, v.months, v.expiry,
                        v.serverTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                        v.entJson, v.sharedWarn
                    )
                }
            }
        }

    /** expiry cho dev fallback (VipCode local không trả millis với monthly). */
    private fun devMonthlyExpiry(r: VipCode.VipResult.Valid): Long {
        val now = System.currentTimeMillis()
        return now + r.months.coerceIn(1, 36) * 30L * DAY_MILLIS
    }

    private suspend fun applyValid(
        prefs: AdsPreferences,
        input: String,
        hash: String,
        tierInt: Int,
        months: Int,
        expiry: Long?,
        serverTime: Long,
        entJson: String,
        sharedWarn: Boolean
    ): RedeemResult.VerifiedOnline {
        val now = System.currentTimeMillis()
        val lifetime = tierInt == VipCode.TIER_LIFETIME
        val (tierStr, until) = if (lifetime) {
            VIP_TIER_LIFETIME to VIP_LIFETIME_UNTIL
        } else {
            val exp = expiry ?: (serverTime + months.coerceIn(1, 36) * 30L * DAY_MILLIS)
            val cur = try {
                prefs.adsStateFlow.first()
            } catch (_: Exception) {
                AdsState()
            }
            val curUntil = cur.vipUntil.takeIf { it > 0 && it != Long.MAX_VALUE } ?: 0L
            val cap = now + VIP_MAX_STACK_DAYS * DAY_MILLIS
            VIP_TIER_MONTH to maxOf(curUntil, exp).coerceAtMost(cap)
        }
        prefs.setVip(tierStr, until)
        val prevMax = try {
            prefs.loadVipAuth()?.maxSeen ?: 0L
        } catch (_: Exception) {
            0L
        }
        prefs.saveVipAuth(entJson, tierStr, until, serverTime, maxOf(serverTime, now, prevMax), hash)
        try {
            prefs.markVipCodeUsed(input)
        } catch (_: Exception) {
        }
        return RedeemResult.VerifiedOnline(months, until, lifetime, sharedWarn)
    }

    private suspend fun grace(prefs: AdsPreferences, hash: String): RedeemResult {
        val a = try {
            prefs.loadVipAuth()
        } catch (_: Exception) {
            null
        } ?: return RedeemResult.NeedOnline
        if (a.codeHash.isEmpty() || a.codeHash != hash) return RedeemResult.NeedOnline
        if (a.tier.isEmpty()) return RedeemResult.NeedOnline
        val now = System.currentTimeMillis()
        // Chống vặn đồng hồ ngược quá 24h so với mốc lớn nhất từng thấy.
        if (now < a.maxSeen - VIP_SKEW_MS) return RedeemResult.NeedOnline
        val lifetime = a.tier == VIP_TIER_LIFETIME
        val ok = if (lifetime) {
            now - a.lastCheck <= VIP_GRACE_LIFE_MS
        } else {
            a.until > now && now - a.lastCheck <= VIP_GRACE_MONTH_MS
        }
        if (!ok) return RedeemResult.NeedOnline
        try {
            prefs.touchVipMaxSeen(now)
            prefs.setVip(a.tier, a.until)
        } catch (_: Exception) {
            return RedeemResult.NeedOnline
        }
        return RedeemResult.GraceOffline(a.until, lifetime)
    }
}
