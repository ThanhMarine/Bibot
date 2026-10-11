package com.bibot.app.core.announce

import android.content.Context
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thong bao chinh thuc tu NPT (pull JSON, khong can FCM o v1).
 * Nguon: 1 file JSON tinh (GitHub raw), URL trong BuildConfig ANNOUNCE_URL, rong = tat.
 * Cache 24h de offline van xem duoc; loi mang -> dung cache cu.
 *
 * dinh dang JSON:
 * { "announcements": [
 *   { "id":"v12-book-01", "title":"...", "body":"...", "type":"bookstore",
 *     "minVersion":"1.2.0", "url":"https://...", "publishedAt":"2026-10-05" } ] }
 * type: upgrade (hien lai den khi dung ban >= minVersion) | bookstore | promo | info.
 */
data class AnnounceItem(
    val id: String,
    val title: String,
    val body: String,
    val type: String,
    val minVersion: String = "",
    val url: String = "",
    val publishedAt: String = ""
)

object AnnounceApi {
    const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    fun cacheFile(context: Context): File =
        File(FilePaths.cacheRoot(context), "announce.json").also {
            try { it.parentFile?.mkdirs() } catch (_: Exception) {}
        }

    /** Tai + cache; tra danh sach (rong neu chua cau hinh URL hoac loi va khong co cache). */
    suspend fun fetch(context: Context, url: String, force: Boolean = false): List<AnnounceItem> =
        withContext(Dispatchers.IO) {
            if (url.isBlank()) return@withContext emptyList()
            if (!force) {
                readCache(context)?.let { (at, items) ->
                    val age = System.currentTimeMillis() - at
                    if (age in 0 until CACHE_TTL_MS) return@withContext items
                }
            }
            try {
                val json = download(url)
                val items = parse(json)
                writeCache(context, items)
                items
            } catch (_: Exception) {
                readCache(context)?.second ?: emptyList()
            }
        }

    /** Doc cache dia (khong can mang) — dung cho muc lich su khi offline. */
    suspend fun readCached(context: Context): List<AnnounceItem> = withContext(Dispatchers.IO) {
        readCache(context)?.second ?: emptyList()
    }

    /** Co hien tin nay voi ban app hien tai khong. */
    fun isApplicable(item: AnnounceItem, currentVersion: String): Boolean {
        if (item.id.isBlank() || item.title.isBlank()) return false
        // upgrade: hien lai den khi user len ban >= minVersion
        if (item.type == "upgrade" && item.minVersion.isNotBlank()) {
            return compareVersions(currentVersion, item.minVersion) < 0
        }
        return true
    }

    /** So sanh ban "1.2.0" (-demo/-vip hau to duoc bo qua): -1/0/1. */
    fun compareVersions(a: String, b: String): Int {
        val pa = numericParts(a)
        val pb = numericParts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun numericParts(v: String): List<Int> {
        val m = Regex("""\d+(?:\.\d+)*""").find(v) ?: return emptyList()
        return m.value.split(".").mapNotNull { it.toIntOrNull() }
    }

    private fun download(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Bibot-Announce/1.0")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun parse(json: String): List<AnnounceItem> {
        val out = ArrayList<AnnounceItem>()
        val arr: JSONArray = try {
            val root = JSONObject(json)
            root.optJSONArray("announcements") ?: JSONArray()
        } catch (_: Exception) {
            // Chap nhan mang JSON tran [{...}]
            JSONArray(json)
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += AnnounceItem(
                id = o.optString("id", "").trim(),
                title = o.optString("title", "").trim(),
                body = o.optString("body", "").trim(),
                type = o.optString("type", "info").trim().lowercase().ifBlank { "info" },
                minVersion = o.optString("minVersion", "").trim(),
                url = o.optString("url", "").trim(),
                publishedAt = o.optString("publishedAt", "").trim()
            )
        }
        return out.filter { it.id.isNotEmpty() && it.title.isNotEmpty() }
    }

    private fun readCache(context: Context): Pair<Long, List<AnnounceItem>>? {
        return try {
            val f = cacheFile(context)
            if (!f.exists()) return null
            val root = JSONObject(f.readText())
            root.getLong("fetchedAt") to parse(root.getJSONArray("items").toString())
        } catch (_: Exception) { null }
    }

    private fun writeCache(context: Context, items: List<AnnounceItem>) {
        try {
            val arr = JSONArray()
            for (it in items) {
                arr.put(
                    JSONObject()
                        .put("id", it.id).put("title", it.title).put("body", it.body)
                        .put("type", it.type).put("minVersion", it.minVersion)
                        .put("url", it.url).put("publishedAt", it.publishedAt)
                )
            }
            cacheFile(context).writeText(
                JSONObject().put("fetchedAt", System.currentTimeMillis()).put("items", arr).toString()
            )
        } catch (_: Exception) {}
    }
}
