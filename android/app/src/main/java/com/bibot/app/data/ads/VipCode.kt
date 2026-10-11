package com.bibot.app.data.ads

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * VipCode — xac thuc ma VIP offline (khop tools/vipcodes/vip_batch.py).
 * Dinh dang: BIBOT-XXXXXX-XXXXXX-XXXXXX-XXXXXXXX (payload 10B + HMAC-SHA256 cat 6B).
 */
object VipCode {
    const val PREFIX = "BIBOT"
    const val VERSION = 1
    const val TIER_MONTH = 1
    const val TIER_LIFETIME = 2
    const val EXPIRY_LIFE = 0xFFFF
    const val EPOCH_MILLIS = 1767225600000L // 2026-01-01T00:00Z
    const val DAY_MILLIS = 86400000L
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    sealed interface VipResult {
        data class Valid(
            val tier: Int,
            val months: Int,
            /** null = vinh vien; khac null = han dung (millis). */
            val expiryMillis: Long?,
            val batch: Int,
            val serial: Int
        ) : VipResult
        data class Invalid(val reason: String) : VipResult
    }

    fun verify(secretHex: String, code: String, nowMillis: Long = System.currentTimeMillis()): VipResult {
        if (secretHex.isBlank()) return VipResult.Invalid("chua cau hinh khoa xac thuc")
        val secret = try { hexToBytes(secretHex) } catch (_: Exception) {
            return VipResult.Invalid("khoa xac thuc loi")
        }
        if (secret.size < 16) return VipResult.Invalid("khoa xac thuc loi")
        val c = code.trim().uppercase()
        if (!c.startsWith("$PREFIX-")) return VipResult.Invalid("thieu tien to BIBOT-")
        val body = c.substring(PREFIX.length + 1).replace("-", "")
        if (body.length != 26 || body.any { it !in ALPHABET }) {
            return VipResult.Invalid("do dai/ky tu sai")
        }
        val raw = try { b32Decode26(body) } catch (_: Exception) {
            return VipResult.Invalid("giai ma that bai")
        }
        val payload = raw.copyOfRange(0, 10)
        val sig = raw.copyOfRange(10, 16)
        val expect = try { hmacSha256(secret, payload) } catch (_: Exception) {
            return VipResult.Invalid("loi ky so")
        }
        if (!constantTimeEq(expect, sig)) return VipResult.Invalid("chu ky sai (ma gia/nhap sai)")
        val ver = payload[0].toInt() and 0xFF
        val tier = payload[1].toInt() and 0xFF
        val months = payload[2].toInt() and 0xFF
        val expiryDays = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
        val batch = payload[5].toInt() and 0xFF
        val serial = payload[6].toInt() and 0xFF
        if (ver != VERSION) return VipResult.Invalid("phien ban ma khong ho tro")
        if (tier != TIER_MONTH && tier != TIER_LIFETIME) return VipResult.Invalid("loai ma la")
        if (tier == TIER_LIFETIME) return VipResult.Valid(tier, 0, null, batch, serial)
        val expMs = EPOCH_MILLIS + expiryDays * DAY_MILLIS
        if (nowMillis > expMs) return VipResult.Invalid("ma het han")
        return VipResult.Valid(tier, months, expMs, batch, serial)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.trim()
        require(s.length % 2 == 0 && s.isNotEmpty())
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Giai 26 ky tu base32 tu che -> 16 byte (bo 2 bit dem cuoi, khop python). */
    private fun b32Decode26(body: String): ByteArray {
        val out = ByteArray(16)
        var acc = 0
        var bits = 0
        var pos = 0
        for (ch in body) {
            val v = ALPHABET.indexOf(ch)
            if (v < 0) throw IllegalArgumentException("bad char")
            acc = (acc shl 5) or v
            bits += 5
            while (bits >= 8) {
                if (pos >= 16) throw IllegalArgumentException("overflow")
                bits -= 8
                out[pos++] = ((acc shr bits) and 0xFF).toByte()
            }
        }
        if (pos != 16) throw IllegalArgumentException("short")
        return out
    }

    private fun hmacSha256(secret: ByteArray, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(payload).copyOfRange(0, 6)
    }

    private fun constantTimeEq(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
