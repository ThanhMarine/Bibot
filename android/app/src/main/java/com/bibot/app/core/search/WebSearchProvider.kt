package com.bibot.app.core.search

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * P2 (blueprint TASK 3) — Web Search cho Voice Chat: trả 1-2 câu tóm tắt ngắn nhất
 * đưa vào system prompt của LLM ("lấy 2 đoạn text tóm tắt ngắn nhất").
 *
 * Port từ Pi `services/web_search.py` (search/summarize_results/extract_topic/
 * wikipedia_summary) + `services/weather.py` (Open-Meteo). Bỏ Google CSE (cần key).
 *
 * Nguồn theo thứ tự: Weather (query thời tiết) → Bing RSS → DuckDuckGo HTML →
 * Wikipedia (fallback).
 *
 * Mọi hàm đều BLOCKING (HttpURLConnection) — chỉ gọi trên Dispatchers.IO.
 */
interface WebSearchProvider {
    fun searchSummary(query: String): String
}

object DefaultWebSearchProvider : WebSearchProvider {

    private const val UA = "Mozilla/5.0 (X11; Linux x86_64) Bibot/1.0"
    private const val MAX_ANSWER_CHARS = 280
    private const val MAX_DIGEST_CHARS = 460

    override fun searchSummary(query: String): String = searchDigest(query, MAX_ANSWER_CHARS)

    /**
     * Digest phục vụ voice chat: gom nhiều nguồn hơn, 3-4 câu, đủ ý để bot đọc lại.
     * Vẫn block (IO) — chỉ gọi từ Dispatchers.IO.
     */
    fun searchDigest(query: String, maxChars: Int = MAX_DIGEST_CHARS): String {
        val q = query.trim()
        if (q.isEmpty()) return ""
        try {
            weatherReport(q)?.let { if (it.isNotBlank()) return it }
        } catch (_: Exception) { }
        val results = try {
            search(q, maxResults = 6)
        } catch (_: Exception) { emptyList() }
        val answer = try {
            summarizeResults(q, results, maxSentences = 4, maxChars = maxChars)
        } catch (_: Exception) { "" }
        if (answer.isNotBlank()) return answer
        val topic = extractTopic(q)
        return try {
            if (topic.isNotBlank()) wikipediaSummary(topic) else ""
        } catch (_: Exception) { "" }
    }

    // ------------------------------------------------------------------ HTTP

    private fun fetch(url: String, timeoutMs: Int = 4000, limit: Int = 256 * 1024): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "vi,en;q=0.8")
        conn.inputStream.use { input ->
            val out = StringBuilder()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                out.append(String(buf, 0, n, Charsets.UTF_8))
                if (total >= limit) break
            }
            return out.toString()
        }
    }

    // --------------------------------------------------------------- Weather

    private data class WxLoc(val name: String, val lat: Double, val lon: Double)

    private val WX_LOCATIONS = mapOf(
        "hcm" to WxLoc("Thành phố Hồ Chí Minh", 10.8231, 106.6297),
        "hanoi" to WxLoc("Hà Nội", 21.0285, 105.8542),
        "danang" to WxLoc("Đà Nẵng", 16.0544, 108.2022),
        "haiphong" to WxLoc("Hải Phòng", 20.8449, 106.6881),
        "cantho" to WxLoc("Cần Thơ", 10.0452, 105.7469)
    )

    private val WX_ALIASES = mapOf(
        "sg" to "hcm", "saigon" to "hcm", "sai gon" to "hcm",
        "ho chi minh" to "hcm", "sai gon city" to "hcm",
        "hn" to "hanoi", "ha noi" to "hanoi",
        "dn" to "danang", "da nang" to "danang",
        "hp" to "haiphong", "hai phong" to "haiphong",
        "ct" to "cantho", "can tho" to "cantho"
    )

    // WMO weather codes → tiếng Việt (port từ Pi weather.py)
    private val WMO_CODES = mapOf(
        0 to "Trời quang đãng, không mây",
        1 to "Trời chủ yếu có nắng, ít mây",
        2 to "Trời nhiều mây rải rác",
        3 to "Trời âm u, nhiều mây",
        45 to "Có sương mù", 48 to "Có sương mù dày",
        51 to "Mưa phùn nhẹ", 53 to "Mưa phùn vừa", 55 to "Mưa phùn dày hạt",
        56 to "Mưa phùn buốt nhẹ", 57 to "Mưa phùn buốt dày",
        61 to "Có mưa rào nhẹ", 63 to "Có mưa vừa", 65 to "Có mưa to",
        66 to "Mưa đóng băng nhẹ", 67 to "Mưa đóng băng nặng",
        71 to "Tuyết rơi nhẹ", 73 to "Tuyết rơi vừa", 75 to "Tuyết rơi dày", 77 to "Hạt tuyết rơi",
        80 to "Mưa rào từng cơn nhẹ", 81 to "Mưa rào vừa", 82 to "Mưa rào rất to",
        85 to "Mưa tuyết nhẹ", 86 to "Mưa tuyết nặng",
        95 to "Có dông sét", 96 to "Dông kèm mưa đá nhỏ", 99 to "Dông kèm mưa đá to"
    )

    private fun resolveLocation(foldedQuery: String): WxLoc {
        for ((alias, key) in WX_ALIASES) if (foldedQuery.contains(alias)) return WX_LOCATIONS.getValue(key)
        for ((key, loc) in WX_LOCATIONS) if (foldedQuery.contains(key)) return loc
        return WX_LOCATIONS.getValue("hcm")
    }

    private fun weatherReport(query: String): String? {
        if ("thời tiết" !in query.lowercase()) return null
        val loc = resolveLocation(fold(query))
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation," +
            "weather_code,wind_speed_10m,wind_direction_10m,surface_pressure,cloud_cover,uv_index,is_day" +
            "&timezone=Asia%2FHo_Chi_Minh"
        val json = JSONObject(fetch(url, 4000))
        val cur = json.optJSONObject("current") ?: return null
        val temp = Math.round(cur.optDouble("temperature_2m", 0.0)).toInt()
        val apparent = Math.round(cur.optDouble("apparent_temperature", temp.toDouble())).toInt()
        val humidity = cur.optInt("relative_humidity_2m", 0)
        val wind = Math.round(cur.optDouble("wind_speed_10m", 0.0)).toInt()
        val windDir = windDirectionText(cur.optDouble("wind_direction_10m", -1.0))
        val pressure = Math.round(cur.optDouble("surface_pressure", 0.0)).toInt()
        val cloud = cur.optInt("cloud_cover", 0)
        val uv = cur.optDouble("uv_index", 0.0)
        val code = cur.optInt("weather_code", 0)
        val desc = WMO_CODES[code] ?: "Thời tiết bình thường"
        return "Thời tiết tại ${loc.name} hiện tại: $desc. " +
            "Nhiệt độ khoảng $temp độ C, cảm giác thực tế như $apparent độ C. " +
            "Độ ẩm $humidity phần trăm, sức gió $wind km/h $windDir. " +
            "Áp suất $pressure hPa, mây che phủ $cloud%, chỉ số UV $uv."
    }

    private fun windDirectionText(degrees: Double): String {
        if (degrees < 0) return ""
        val dirs = arrayOf(
            "Bắc", "Bắc Đông Bắc", "Đông Bắc", "Đông Đông Bắc",
            "Đông", "Đông Đông Nam", "Đông Nam", "Nam Đông Nam",
            "Nam", "Nam Tây Nam", "Tây Nam", "Tây Tây Nam",
            "Tây", "Tây Tây Bắc", "Tây Bắc", "Bắc Tây Bắc"
        )
        val idx = Math.round(degrees / 22.5).toInt() % 16
        return "(${dirs[idx]})"
    }

    // ------------------------------------------------------------- Wikipedia

    private val ENTITY_ALIASES = mapOf(
        "tong thong my" to "Joe Biden",
        "tong thong nga" to "Vladimir Putin",
        "giao hoan" to "Giáo hoàng Phanxicô",
        "thu tuong viet nam" to "Phạm Minh Chính"
    )

    private val LEAD_PATTERNS = listOf(
        Regex("^(pi ơi|bi ơi|pi|bi)[\\s,]+"),
        Regex("^(cho (tôi|anh|em|mình|con) (biết|hỏi)|hãy|giúp (tôi|anh|em|mình)|xin hỏi|bạn có biết|em có biết|nói cho (tôi|anh) biết|kể (cho tôi|cho anh)?\\s*về|tìm hiểu về|tra cứu|tìm kiếm)[\\s,]*"),
        Regex("^(ai là|cái gì là|thế nào là|là gì|là ai)\\s+")
    )
    private val TAIL_PATTERNS = listOf(
        Regex("\\b(là ai|là gì|là cái gì|là như thế nào|nghĩa là gì|có nghĩa là gì|ở đâu|như thế nào|thế nào|bao nhiêu|khi nào|năm nào|ra sao|có gì|sinh năm nào|mất năm nào|là người nào)\\b.*$"),
        Regex("\\b(vậy|nhé|ạ|không|hả|à|đi|nha)\\s*$")
    )

    fun extractTopic(query: String): String {
        var topic = query.replace(Regex("[?!.,;:]+"), " ").trim().lowercase()
        for (p in LEAD_PATTERNS) topic = p.replace(topic, "").trim()
        for (p in TAIL_PATTERNS) topic = p.replace(topic, "").trim()
        topic = topic.replace(Regex("\\s+"), " ")
        if (topic.length in 2..80) {
            for ((alias, canonical) in ENTITY_ALIASES) if (fold(alias) in fold(topic)) return canonical
            return topic
        }
        val m = Regex("""\b(là|ai|cái gì|thế nào)\s+(.{2,80})""", RegexOption.IGNORE_CASE).find(query)
        return m?.groupValues?.get(2)?.trim()?.replace(Regex("\\s+"), " ") ?: ""
    }

    private fun wikipediaSummary(topic: String): String {
        if (topic.isBlank()) return ""
        val open = fetch(
            "https://vi.wikipedia.org/w/api.php?action=opensearch&limit=3&format=json&search=" +
                URLEncoder.encode(topic, "UTF-8"), 3000
        )
        val arr = org.json.JSONArray(open)
        val titles = if (arr.length() > 1) arr.getJSONArray(1) else return ""
        val topicTokens = tokens(fold(topic))
        var best: String? = null
        for (i in 0 until titles.length()) {
            val title = titles.getString(i)
            val titleTokens = tokens(fold(title))
            if (topicTokens.isEmpty() || titleTokens.isEmpty()) continue
            val overlap = topicTokens.intersect(titleTokens).size.toDouble() / topicTokens.size
            if (overlap >= 0.5 || fold(title) in fold(topic) || fold(topic) in fold(title)) {
                best = title; break
            }
        }
        if (best == null) return ""
        val rest = fetch(
            "https://vi.wikipedia.org/api/rest_v1/page/summary/" +
                URLEncoder.encode(best.replace(' ', '_'), "UTF-8"), 3000
        )
        val summary = JSONObject(rest)
        if (summary.optString("type") == "disambiguation") return ""
        val extract = cleanExtract(summary.optString("extract", ""))
        if (extract.isBlank()) return ""
        return firstSentences(extract, 2, MAX_DIGEST_CHARS)
    }

    // ------------------------------------------------------------ Web sources

    fun search(query: String, maxResults: Int = 4): List<SearchSnippet> {
        val bing = try { bingSearch(query, maxResults) } catch (_: Exception) { emptyList() }
        if (bing.isNotEmpty()) return bing
        return try { duckduckgoSearch(query, maxResults) } catch (_: Exception) { emptyList() }
    }

    fun bingSearch(query: String, maxResults: Int = 4): List<SearchSnippet> {
        val raw = fetch(
            "https://www.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") +
                "&format=rss&setlang=vi&cc=VN", 4000
        )
        val out = mutableListOf<SearchSnippet>()
        for (item in Regex("(?s)<item>(.*?)</item>").findAll(raw).take(maxResults)) {
            val block = item.groupValues[1]
            fun tag(name: String): String =
                Regex("(?s)<$name>(.*?)</$name>").find(block)?.groupValues?.get(1) ?: ""
            out += SearchSnippet(
                title = stripTags(htmlUnescape(tag("title"))),
                url = tag("link"),
                snippet = stripTags(htmlUnescape(tag("description")))
            )
        }
        return out.filter { it.title.isNotBlank() }
    }

    fun duckduckgoSearch(query: String, maxResults: Int = 4): List<SearchSnippet> {
        val raw = fetch(
            "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8") + "&kl=vn-vi", 4000
        )
        val titleRe = Regex("""(?s)<a\s+([^>]*class="[^"]*result__a[^"]*"[^>]*)>(.*?)</a>""")
        val hrefRe = Regex("""href="([^"]+)"""")
        val snipRe = Regex("""(?s)<(?:a|div|td)\s+[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</(?:a|div|td)>""")
        val titles = titleRe.findAll(raw).map { m ->
            val href = hrefRe.find(m.groupValues[1])?.groupValues?.get(1) ?: ""
            SearchSnippet(stripTags(htmlUnescape(m.groupValues[2])), cleanUrl(href), "")
        }.filter { it.title.isNotBlank() }.toList()
        val snippets = snipRe.findAll(raw).map { stripTags(htmlUnescape(it.groupValues[1])) }.toList()
        return titles.mapIndexed { i, t ->
            t.copy(snippet = snippets.getOrElse(i) { "" })
        }.take(maxResults)
    }

    // ------------------------------------------------------- Scoring / filter

    private val STOPWORDS = setOf(
        "là", "gì", "ai", "của", "và", "có", "cho", "tôi", "anh", "em", "bạn", "mình",
        "về", "với", "được", "một", "các", "những", "này", "đó", "thì", "mà", "ở", "tại",
        "như", "thế", "nào", "bao", "nhiêu", "khi", "hãy", "biết", "xin", "hỏi",
        "pi", "ơi", "bi", "không", "vậy", "nhé", "ạ", "hả", "à", "ừ", "đi", "rồi", "hay",
        "the", "a", "an", "is", "are", "what", "who", "where", "how", "of", "to", "in"
    )

    private val SENT_SPLIT = Regex("""(?<=[.!?…])\s+(?=[A-ZÀ-Ỹ0-9"“])""")
    private val JUNK_RE = Regex(
        """(?i)(xem thêm|đọc thêm|click|nhấn vào|đăng nhập|đăng ký|cookie|bản quyền|copyright|""" +
            """chi tiết tại|liên hệ|hotline|quảng cáo|tải app|theo dõi chúng tôi|\bhttp\S*|www\.)"""
    )
    private val NON_LATIN_RE = Regex("""[Ѐ-ӿ؀-ۿก-๿぀-ヿ㐀-鿿가-힯]""")
    private val PAREN_RE = Regex("""\s*[(\[][)\)]{0,80}[)\]]""")
    private val FACTUAL_RE = Regex("""\b(là ai|là gì|là cái gì|nghĩa là gì|ai là|thế nào là|ở đâu|sinh năm|năm nào|là người nào)\b""")

    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace('đ', 'd')

    private fun tokens(text: String): Set<String> =
        Regex("[0-9a-zà-ỹ]+").findAll(text.lowercase())
            .map { it.value }
            .filter { it !in STOPWORDS && it.length > 1 }
            .toSet()

    private fun cleanExtract(text: String): String =
        PAREN_RE.replace(text, "").replace(Regex("\\s+"), " ").trim()

    private fun hasNonLatin(text: String, ratio: Double = 0.1): Boolean {
        val hits = NON_LATIN_RE.findAll(text).count()
        return (hits > 0 && hits.toDouble() / text.length.coerceAtLeast(1) >= ratio) || hits >= 4
    }

    private fun firstSentences(text: String, count: Int, limit: Int = MAX_ANSWER_CHARS): String {
        val parts = SENT_SPLIT.split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
        var out = ""
        for (part in parts.take(count)) {
            val candidate = (out + " " + part).trim()
            if (out.isNotEmpty() && candidate.length > limit) break
            out = candidate
        }
        if (out.length > limit) out = out.substring(0, limit).substringBeforeLast(" ").trimEnd(',', ';', ':') + "."
        return out
    }

    private fun domainOf(url: String): String {
        val host = try { URL(url).host.lowercase() } catch (_: Exception) { return "" }
        return host.removePrefix("www.").split(":")[0]
    }

    fun summarizeResults(query: String, results: List<SearchSnippet>, maxSentences: Int = 2, maxChars: Int = MAX_ANSWER_CHARS): String {
        if (results.isEmpty()) return ""
        val qTokens = tokens(query)
        val qFolded = qTokens.map { fold(it) }.toSet()
        val minOverlap = if (qFolded.isNotEmpty()) minOf(2, maxOf(1, Math.round(qFolded.size * 0.4).toInt())) else 0
        val wantsNumber = qTokens.intersect(setOf("giá", "tỷ", "bao", "nhiêu", "lãi", "điểm", "tỉ", "độ", "nhiệt")).isNotEmpty()

        data class Cand(val score: Double, val rank: Int, val sentence: String, val source: String)

        val candidates = mutableListOf<Cand>()
        results.forEachIndexed { rank, item ->
            val source = domainOf(item.url)
            val title = item.title
            val text = item.snippet
            if (text.isBlank() || hasNonLatin(text) || hasNonLatin(title)) return@forEachIndexed
            val titleTokens = tokens(fold(title)).map { fold(it) }.toSet()
            val titleOverlap = qFolded.intersect(titleTokens).size
            for (raw in SENT_SPLIT.split(cleanExtract(text))) {
                val sentence = raw.trim(' ', '-', '–', '•', '|')
                val words = sentence.split(Regex("\\s+"))
                if (words.size < 4 || JUNK_RE.containsMatchIn(sentence)) continue
                val sTokens = tokens(fold(sentence)).map { fold(it) }.toSet()
                val overlap = qFolded.intersect(sTokens).size
                if (maxOf(overlap, titleOverlap) < minOverlap) continue
                var score = overlap * 2.0 + titleOverlap * 0.5 - rank * 0.6
                if (wantsNumber && Regex("\\d").containsMatchIn(sentence)) score += 1.5
                if (sentence.endsWith("...") || sentence.endsWith("…")) score -= 0.5
                if (words.size in 6..40) score += 0.5
                if ("wiktionary" in source || "tudien" in source) score -= 1.0
                candidates += Cand(score, rank, sentence, source)
            }
        }
        if (candidates.isEmpty()) return ""
        candidates.sortWith(compareByDescending<Cand> { it.score }.thenBy { it.rank })

        val chosen = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (c in candidates) {
            val key = fold(c.sentence).take(60)
            if (key in seen) continue
            seen += key
            chosen += if (c.sentence.isNotEmpty() && c.sentence.last() in ".!?…") c.sentence else c.sentence + "."
            if (chosen.size >= maxSentences) break
        }
        val final = chosen.joinToString(" ").trim()
        if (hasNonLatin(final, 0.3) || final.length < 12) return ""
        return if (final.length > maxChars) final.substring(0, maxChars).substringBeforeLast(" ").trimEnd(',', ';', ':', '.') + "." else final
    }

    // ---------------------------------------------------------------- helpers

    private fun stripTags(value: String): String =
        Regex("<[^>]+>").replace(value, " ")

    private fun htmlUnescape(value: String): String = value
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .replace("&#x27;", "'").replace("&apos;", "'")

    private fun cleanUrl(value: String): String {
        val v = htmlUnescape(value)
        return if (v.startsWith("//")) "https:$v" else v
    }
}

data class SearchSnippet(val title: String, val url: String, val snippet: String)
