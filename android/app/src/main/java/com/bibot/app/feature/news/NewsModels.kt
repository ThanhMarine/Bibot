package com.bibot.app.feature.news

import android.content.Context
import androidx.compose.runtime.Composable
import com.bibot.app.data.files.FilePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale

data class NewsItem(
    val title: String,
    val description: String,
    val source: String,
    val publishedAt: String
)

data class NewsSource(val id: String, val name: String, val feeds: Map<String, String>)

data class NewsResult(val items: List<NewsItem>, val source: NewsSource, val fellBack: Boolean)

object NewsApi {
    // Fix 2026-10-01 crash "missing closing bracket in character class": `[` CHƯA
    // escape trong class mở nested-class (union) nuốt mất `]` đóng (`()[\]]` fail).
    // → escape CẢ 2 ngoặc (\[ \]) + gạch nối để cuối (không cần \-) + dash để chữ
    // thật (Kotlin \uXXXX, không dùng regex \\uXXXX). Đã verify Pattern.compile OK
    // trên javac/java8+17 (TestBisect/TestNewsRegex).
    val NORM_PATTERN = "[\\s\"'\"\u201C\u201D\u2018\u2019\u00AB\u00BB\u2026.,;:!?()\u2013\u2014\\[\\]-]+"
    val CATEGORIES = listOf(
        "moi_nhat" to com.bibot.app.R.string.news_cat_latest,
        "thoi_su" to com.bibot.app.R.string.news_cat_current,
        "the_gioi" to com.bibot.app.R.string.news_cat_world,
        "kinh_doanh" to com.bibot.app.R.string.news_cat_business,
        "so_hoa" to com.bibot.app.R.string.news_cat_tech,
        "giai_tri" to com.bibot.app.R.string.news_cat_ent,
        "the_thao" to com.bibot.app.R.string.news_cat_sports,
        "phap_luat" to com.bibot.app.R.string.news_cat_law,
        "giao_duc" to com.bibot.app.R.string.news_cat_edu,
        "suc_khoe" to com.bibot.app.R.string.news_cat_health
    )

    val SOURCES = listOf(
        NewsSource("vnexpress", "VnExpress", mapOf(
            "moi_nhat" to "https://vnexpress.net/rss/tin-moi-nhat.rss",
            "thoi_su" to "https://vnexpress.net/rss/thoi-su.rss",
            "the_gioi" to "https://vnexpress.net/rss/the-gioi.rss",
            "kinh_doanh" to "https://vnexpress.net/rss/kinh-doanh.rss",
            "so_hoa" to "https://vnexpress.net/rss/so-hoa.rss",
            "giai_tri" to "https://vnexpress.net/rss/giai-tri.rss",
            "the_thao" to "https://vnexpress.net/rss/the-thao.rss",
            "phap_luat" to "https://vnexpress.net/rss/phap-luat.rss",
            "giao_duc" to "https://vnexpress.net/rss/giao-duc.rss",
            "suc_khoe" to "https://vnexpress.net/rss/suc-khoe.rss"
        )),
        NewsSource("tuoitre", "Tuổi Trẻ", mapOf(
            "moi_nhat" to "https://tuoitre.vn/rss/tin-moi-nhat.rss",
            "thoi_su" to "https://tuoitre.vn/rss/thoi-su.rss",
            "the_gioi" to "https://tuoitre.vn/rss/the-gioi.rss",
            "kinh_doanh" to "https://tuoitre.vn/rss/kinh-doanh.rss",
            "so_hoa" to "https://tuoitre.vn/rss/cong-nghe.rss",
            "giai_tri" to "https://tuoitre.vn/rss/giai-tri.rss",
            "the_thao" to "https://tuoitre.vn/rss/the-thao.rss",
            "phap_luat" to "https://tuoitre.vn/rss/phap-luat.rss",
            "giao_duc" to "https://tuoitre.vn/rss/giao-duc.rss",
            "suc_khoe" to "https://tuoitre.vn/rss/suc-khoe.rss"
        )),
        NewsSource("dantri", "Dân Trí", mapOf(
            "moi_nhat" to "https://dantri.com.vn/rss/tin-moi-nhat.rss",
            "thoi_su" to "https://dantri.com.vn/rss/thoi-su.rss",
            "the_gioi" to "https://dantri.com.vn/rss/the-gioi.rss",
            "kinh_doanh" to "https://dantri.com.vn/rss/kinh-doanh.rss",
            "so_hoa" to "https://dantri.com.vn/rss/cong-nghe.rss",
            "giai_tri" to "https://dantri.com.vn/rss/giai-tri.rss",
            "the_thao" to "https://dantri.com.vn/rss/the-thao.rss",
            "phap_luat" to "https://dantri.com.vn/rss/phap-luat.rss",
            "giao_duc" to "https://dantri.com.vn/rss/giao-duc.rss",
            "suc_khoe" to "https://dantri.com.vn/rss/suc-khoe.rss"
        )),
        NewsSource("googlenews", "Google News", mapOf(
            "moi_nhat" to "https://news.google.com/rss?hl=vi&gl=VN&ceid=VN:vi",
            "thoi_su" to "https://news.google.com/rss/search?q=tin+thoi+su+Viet+Nam&hl=vi&gl=VN&ceid=VN:vi",
            "the_gioi" to "https://news.google.com/rss/search?q=tin+the+gioi&hl=vi&gl=VN&ceid=VN:vi",
            "kinh_doanh" to "https://news.google.com/rss/search?q=tin+kinh+doanh&hl=vi&gl=VN&ceid=VN:vi",
            "so_hoa" to "https://news.google.com/rss/search?q=tin+cong+nghe&hl=vi&gl=VN&ceid=VN:vi",
            "giai_tri" to "https://news.google.com/rss/search?q=tin+giai+tri&hl=vi&gl=VN&ceid=VN:vi",
            "the_thao" to "https://news.google.com/rss/search?q=tin+the+thao&hl=vi&gl=VN&ceid=VN:vi",
            "phap_luat" to "https://news.google.com/rss/search?q=tin+phap+luat&hl=vi&gl=VN&ceid=VN:vi",
            "giao_duc" to "https://news.google.com/rss/search?q=tin+giao+duc&hl=vi&gl=VN&ceid=VN:vi",
            "suc_khoe" to "https://news.google.com/rss/search?q=tin+suc+khoe&hl=vi&gl=VN&ceid=VN:vi"
        ))
    )

    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val LIMIT = 15
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 13; Bibot) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    fun sourceName(id: String): String = SOURCES.firstOrNull { it.id == id }?.name ?: SOURCES[0].name

    private fun cacheFile(context: Context, sourceId: String, categoryId: String): File =
        File(FilePaths.cacheRoot(context), "news_${sourceId}_${categoryId}.json").also {
            try { it.parentFile?.mkdirs() } catch (_: Exception) {}
        }

    suspend fun fetchCached(
        context: Context,
        sourceId: String,
        categoryId: String,
        forceRefresh: Boolean = false
    ): Result<NewsResult> = withContext(Dispatchers.IO) {
        try { File(FilePaths.cacheRoot(context), "news.json").delete() } catch (_: Exception) {}

        val primary = SOURCES.firstOrNull { it.id == sourceId } ?: SOURCES[0]
        val cat = if (categoryId in primary.feeds) categoryId else "moi_nhat"

        if (!forceRefresh) {
            try {
                val f = cacheFile(context, primary.id, cat)
                if (f.exists()) {
                    val wrapper = JSONObject(f.readText())
                    val age = System.currentTimeMillis() - wrapper.optLong("cachedAt", 0L)
                    if (age in 0 until CACHE_TTL_MS) {
                        val cachedSource = SOURCES.firstOrNull { it.id == wrapper.optString("sourceId") } ?: primary
                        return@withContext Result.success(
                            NewsResult(parseItems(wrapper.getJSONArray("items"), cachedSource.name), cachedSource, false)
                        )
                    }
                }
            } catch (_: Exception) { }
        }

        val candidates = listOf(primary) + SOURCES.filter { it.id != primary.id }
        val tried = mutableListOf<String>()
        var lastErr: Exception? = null
        for (src in candidates) {
            val c = if (cat in src.feeds) cat else "moi_nhat"
            try {
                val xml = fetchRss(src.feeds.getValue(c))
                val items = parseRss(xml, src.name)
                val json = JSONObject()
                    .put("cachedAt", System.currentTimeMillis())
                    .put("sourceId", src.id)
                    .put("items", JSONArray(items.map {
                        JSONObject().put("title", it.title).put("description", it.description)
                            .put("source", it.source).put("publishedAt", it.publishedAt)
                    }))
                try { cacheFile(context, primary.id, cat).writeText(json.toString()) } catch (_: Exception) {}
                if (src.id != primary.id) {
                    try { cacheFile(context, src.id, c).writeText(json.toString()) } catch (_: Exception) {}
                }
                return@withContext Result.success(NewsResult(items, src, fellBack = src.id != primary.id))
            } catch (e: Exception) {
                tried += src.name
                lastErr = e
            }
        }
        Result.failure(
            Exception(context.getString(com.bibot.app.R.string.news_load_fail, tried.joinToString(", "), lastErr?.message ?: ""))
        )
    }

    private suspend fun fetchRss(url: String): String {
        var lastErr = "lỗi không rõ"
        repeat(2) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", BROWSER_UA)
                conn.setRequestProperty(
                    "Accept",
                    "application/rss+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.1"
                )
                conn.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.5")
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                val code = conn.responseCode
                if (code == 200) {
                    val ctype = conn.contentType ?: ""
                    val cs = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                        .find(ctype)?.groupValues?.get(1)?.trim('"', '\'') ?: "UTF-8"
                    val charset = runCatching { Charset.forName(cs) }.getOrDefault(StandardCharsets.UTF_8)
                    return conn.inputStream.bufferedReader(charset).use { it.readText() }
                }
                lastErr = "HTTP $code"
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
            if (attempt == 0) delay(800)
        }
        throw Exception(lastErr)
    }

    private fun parseRss(xml: String, sourceName: String): List<NewsItem> {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val items = mutableListOf<NewsItem>()
        var insideItem = false
        var inTitle = false
        var inDesc = false
        var inPub = false
        var title = ""
        var desc = ""
        var pub = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (name == "item") {
                        insideItem = true; title = ""; desc = ""; pub = ""
                    } else if (insideItem) {
                        when (name) {
                            "title" -> inTitle = true
                            "description" -> inDesc = true
                            "pubdate" -> inPub = true
                        }
                    }
                }
                XmlPullParser.TEXT -> if (insideItem) {
                    if (inTitle) title += parser.text
                    else if (inDesc) desc += parser.text
                    else if (inPub) pub += parser.text
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (name == "item") {
                        insideItem = false
                        val t = cleanText(title)
                        if (t.isNotEmpty()) {
                            val d = cleanText(desc)
                            items += NewsItem(
                                title = t,
                                // B6.5: giữ TRÒN text_block (bỏ take(200)); UI tap-to-expand,
                                // TTS mergeTts đọc đủ tin.
                                description = d,
                                source = sourceName,
                                publishedAt = formatPubDate(pub)
                            )
                            if (items.size >= LIMIT) return items
                        }
                    } else if (insideItem) {
                        when (name) {
                            "title" -> inTitle = false
                            "description" -> inDesc = false
                            "pubdate" -> inPub = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        if (items.isEmpty()) throw Exception("RSS không có tin hợp lệ")
        return items
    }

    private fun parseItems(arr: JSONArray, sourceName: String): List<NewsItem> {
        val out = mutableListOf<NewsItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += NewsItem(
                title = o.optString("title", ""),
                description = o.optString("description", ""),
                source = o.optString("source", sourceName),
                publishedAt = o.optString("publishedAt", "")
            )
        }
        return out
    }

    private fun cleanText(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.replace(Regex("<[^>]*>"), " ")
        val named = mapOf(
            "&nbsp;" to " ",
            "&quot;" to "\"",
            "&apos;" to "'",
            "<" to "<",
            ">" to ">"
        )
        for ((k, v) in named) s = s.replace(k, v)
        val amp = "\u0026"
        s = s.replace(amp, amp)
        val regexHexPattern = "#x([0-9a-fA-F]+);"
        val regexHex = Regex("#x([0-9a-fA-F]+);")
        s = regexHex.replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value
        }
        val regexDec = Regex("#(\\d+);")
        s = regexDec.replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() } ?: m.value
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun formatPubDate(raw: String): String = try {
        val parsed = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(raw.trim())
        if (parsed == null) "" else SimpleDateFormat("HH:mm dd/MM", Locale("vi", "VN")).format(parsed)
    } catch (_: Exception) { "" }

    fun mergeTts(title: String, description: String): String {
        val t = title.trim()
        val d = description.trim()
        if (d.isEmpty()) return t
        if (t.isEmpty()) return d
        val norm = { s: String -> s.lowercase(Locale.ROOT)
            .replace(Regex(NORM_PATTERN), " ")
            .replace(Regex("\\s+"), " ").trim()
        }
        val nt = norm(t)
        val nd = norm(d)
        if (nt == nd) return t
        if (nt.isNotEmpty() && nt in nd) return d
        if (nd.isNotEmpty() && nd in nt) return t
        val n = minOf(nt.length, nd.length, 40)
        if (n >= 20 && nt.take(n) == nd.take(n)) return if (t.length >= d.length) t else d
        val sentenceEndings = ".!?:;"
        val body = if (t.isNotEmpty() && t.last() in sentenceEndings) t else t + "."
        return body + " " + d
    }
}