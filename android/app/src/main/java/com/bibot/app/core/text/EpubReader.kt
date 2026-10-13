package com.bibot.app.core.text

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * Đọc EPUB (pure JDK `java.util.zip`, không thêm dependency) — đối xứng với
 * tầng PDF trong [BlockTextReader]:
 *  - Spine order (container.xml → OPF manifest/spine, bỏ `linear="no"` + nav).
 *  - XHTML → text (bỏ head/script/style, block → xuống dòng, entities).
 *  - Encoding: BOM/XML-decl/meta → strict decode, fail → Latin-1 GIỮ BYTE
 *    để tầng text-level ([Tcvn3]) còn detect/convert được EPUB TCVN3 đời cũ.
 *  - EPUB ảnh (toàn trang scan, không text — tương đương PDF Type3 scan):
 *    [isScannedEpub] để UI báo rõ thay vì trang trắng.
 *
 * RAM: đọc từng entry theo nhu cầu (trần 8MB/entry), không bung cả zip.
 */
object EpubReader {

    /** 1 chương = 1 spine item (đã strip tag, entities đã decode, giữ \n khối). */
    data class Chapter(val title: String, val text: String)

    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_SPINE_ITEMS = 1000
    // Tổng text dưới ngưỡng + có ảnh → EPUB scan (không có chữ thật để đọc)
    private const val SCANNED_TEXT_CHARS = 200

    private val rootfileRe = Regex("""<rootfile\b[^>]*?full-path="([^"]+)"""")
    private val itemTagRe = Regex("""<item\b[^>]*>""")
    private val itemrefTagRe = Regex("""<itemref\b[^>]*>""")
    private val attrRe = Regex("""([A-Za-z-]+)\s*=\s*"([^"]*)"""")
    private val dcTitleRe =
        Regex("""<(?:\w+:)?title\b[^>]*>(.*?)</(?:\w+:)?title>""", RegexOption.DOT_MATCHES_ALL)
    private val dcLangRe =
        Regex("""<(?:\w+:)?language\b[^>]*>(.*?)</(?:\w+:)?language>""", RegexOption.DOT_MATCHES_ALL)
    private val hTitleRe =
        Regex("""<h[1-3]\b[^>]*>(.*?)</h[1-3]>""", RegexOption.DOT_MATCHES_ALL)
    private val xmlEncRe = Regex("""<\?xml\b[^?]*?encoding\s*=\s*["']([^"']+)""")
    private val metaCharsetRe =
        Regex("""<meta\b[^>]*?charset\s*=\s*["']?([A-Za-z0-9:_-]+)""", RegexOption.IGNORE_CASE)

    /** True nếu là EPUB (đuôi .epub + mở được zip + có mimetype/application). */
    fun isEpub(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        if (!file.extension.equals("epub", ignoreCase = true)) return false
        return try {
            ZipFile(file).use { zip ->
                zip.getEntry("mimetype") != null ||
                    zip.entries().asSequence().any { it.name.endsWith(".opf") }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Danh sách chương theo đúng thứ tự spine.
     * Mỗi chương đọc riêng entry (không giữ cả sách RAM cùng lúc quá 1 entry + text đã ra).
     */
    fun chapters(file: File, defaultTitle: String = "Nội dung"): List<Chapter> {
        if (!file.exists()) return listOf(Chapter(defaultTitle, ""))
        val out = ArrayList<Chapter>()
        try {
            ZipFile(file).use { zip ->
                val plan = spinePlan(zip) ?: return listOf(Chapter(defaultTitle, ""))
                var idx = 0
                for (item in plan.items.take(MAX_SPINE_ITEMS)) {
                    idx++
                    val entry = zip.getEntry(item.path) ?: continue
                    val bytes = readEntry(zip, entry) ?: continue
                    val html = decodeBytes(bytes)
                    val text = xhtmlToText(html)
                    val title = chapterTitle(html, plan.bookTitle, idx, defaultTitle)
                    out.add(Chapter(title, text))
                }
            }
        } catch (_: Exception) {
            // zip hỏng → trả phần đã có (có thể rỗng)
        }
        return out.ifEmpty { listOf(Chapter(defaultTitle, "")) }
    }

    /** Stream text từng spine item (cho TTS/picard, không giữ cả sách). */
    fun iterText(file: File): Sequence<String> = sequence {
        if (!file.exists()) return@sequence
        try {
            ZipFile(file).use { zip ->
                val plan = spinePlan(zip) ?: return@sequence
                for (item in plan.items.take(MAX_SPINE_ITEMS)) {
                    val entry = zip.getEntry(item.path) ?: continue
                    val bytes = readEntry(zip, entry) ?: continue
                    val t = xhtmlToText(decodeBytes(bytes))
                    if (t.isNotBlank()) yield(t)
                }
            }
        } catch (_: Exception) { /* yield phần đã có */ }
    }

    /**
     * True nếu EPUB scan/ảnh: text trích được < ngưỡng mà manifest có ảnh.
     * (Tương đương PDF Type3 scan — byte TCVN3 decode sai vẫn ra NHIỀU chữ
     * nên không bị nhận nhầm là scan.)
     */
    fun isScannedEpub(file: File): Boolean {
        if (!file.exists()) return false
        return try {
            var textLen = 0
            var images = 0
            ZipFile(file).use { zip ->
                val plan = spinePlan(zip) ?: return false
                images = plan.imageCount
                if (images == 0) return false
                for (item in plan.items.take(MAX_SPINE_ITEMS)) {
                    val entry = zip.getEntry(item.path) ?: continue
                    val bytes = readEntry(zip, entry) ?: continue
                    textLen += xhtmlToText(decodeBytes(bytes)).length
                    if (textLen >= SCANNED_TEXT_CHARS) return false
                }
            }
            textLen < SCANNED_TEXT_CHARS
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Đoán ngôn ngữ: dc:language → text mẫu có dấu tiếng Việt → "vi",
     * ngược lại "en". Dùng khi scan local (audioBooks) để xếp đúng module.
     */
    fun sniffLang(file: File): String {
        if (!file.exists()) return "en"
        return try {
            ZipFile(file).use { zip ->
                val plan = spinePlan(zip) ?: return "en"
                val lang = plan.language.lowercase()
                if (lang.startsWith("vi")) return "vi"
                if (lang.startsWith("en")) return "en"
                val sb = StringBuilder()
                for (item in plan.items.take(5)) {
                    val entry = zip.getEntry(item.path) ?: continue
                    val bytes = readEntry(zip, entry) ?: continue
                    sb.append(xhtmlToText(decodeBytes(bytes)))
                    if (sb.length >= 4000) break
                }
                if (vnMark.containsMatchIn(sb)) "vi" else "en"
            }
        } catch (_: Exception) {
            "en"
        }
    }

    private val vnMark =
        Regex("[\u0103\u0102\u0111\u0110\u01A1\u01A0\u01B0\u01AF\u00EA\u00CA\u00F4\u00D4\u1EA0-\u1EF9]")

    // ------------------------------------------------------------ internals

    private data class SpineItem(val path: String)
    private data class SpinePlan(
        val items: List<SpineItem>,
        val imageCount: Int,
        val bookTitle: String,
        val language: String
    )

    private fun readEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): ByteArray? {
        return try {
            if (entry.isDirectory) return null
            zip.getInputStream(entry).use { input ->
                val bos = ByteArrayOutputStream(32 * 1024)
                val buf = ByteArray(32 * 1024)
                var total = 0
                while (total < MAX_ENTRY_BYTES) {
                    val r = input.read(buf, 0, minOf(buf.size, MAX_ENTRY_BYTES - total))
                    if (r < 0) break
                    bos.write(buf, 0, r)
                    total += r
                }
                bos.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun attrs(tag: String): Map<String, String> {
        val m = HashMap<String, String>()
        for (a in attrRe.findAll(tag)) m[a.groupValues[1].lowercase()] = a.groupValues[2]
        return m
    }

    private fun decodePath(href: String): String {
        // URLDecoder biến '+' thành space — sai với tên file có '+'
        return try {
            java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            href
        }
    }

    private fun resolveHref(base: String, href: String): String {
        var h = decodePath(href.substringBefore('#').trim())
        if (h.isEmpty()) return ""
        // Bỏ scheme lạ (http/file tuyệt đối không nằm trong zip)
        if (h.contains("://")) return ""
        while (h.startsWith("./")) h = h.substring(2)
        var path = base + h
        // Chuẩn hoá ../ (không cho thoát gốc zip)
        val segs = ArrayList<String>()
        for (p in path.split('/')) {
            when {
                p.isEmpty() || p == "." -> {}
                p == ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.lastIndex)
                else -> segs.add(p)
            }
        }
        return segs.joinToString("/")
    }

    private fun spinePlan(zip: ZipFile): SpinePlan? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        val containerXml = decodeBytes(readEntry(zip, container) ?: return null)
        val opfPath = rootfileRe.find(containerXml)?.groupValues?.get(1) ?: return null
        val opfEntry = zip.getEntry(opfPath) ?: return null
        val opf = decodeBytes(readEntry(zip, opfEntry) ?: return null)
        val base = opfPath.substringBeforeLast('/', "") + "/"

        // Manifest: id → (href, media-type, properties)
        val idToHref = HashMap<String, String>()
        val idToMime = HashMap<String, String>()
        val navIds = HashSet<String>()
        var imageCount = 0
        for (t in itemTagRe.findAll(opf)) {
            val a = attrs(t.value)
            val id = a["id"] ?: continue
            val href = a["href"] ?: continue
            val mime = (a["media-type"] ?: "").lowercase()
            idToHref[id] = href
            idToMime[id] = mime
            if (mime.startsWith("image/")) imageCount++
            if ((a["properties"] ?: "").lowercase().split(' ').any { it == "nav" }) navIds.add(id)
        }
        val items = ArrayList<SpineItem>()
        for (t in itemrefTagRe.findAll(opf)) {
            val a = attrs(t.value)
            val idref = a["idref"] ?: continue
            if ((a["linear"] ?: "yes").equals("no", ignoreCase = true)) continue
            if (idref in navIds) continue
            val href = idToHref[idref] ?: continue
            val mime = idToMime[idref] ?: ""
            val low = href.lowercase()
            val isDoc = mime.contains("xhtml") || mime.contains("html") || mime.contains("xml") ||
                low.endsWith(".htm") || low.endsWith(".html") ||
                low.endsWith(".xhtml") || low.endsWith(".xml")
            if (!isDoc) continue
            val path = resolveHref(base, href)
            if (path.isNotEmpty()) items.add(SpineItem(path))
        }
        if (items.isEmpty()) return null
        val title = dcTitleRe.find(opf)?.groupValues?.get(1)?.let { stripInline(it) }?.trim() ?: ""
        val lang = dcLangRe.find(opf)?.groupValues?.get(1)?.trim() ?: ""
        return SpinePlan(items, imageCount, title, lang)
    }

    private fun chapterTitle(html: String, bookTitle: String, idx: Int, defaultTitle: String): String {
        dcTitleRe.find(html)?.let {
            val t = stripInline(it.groupValues[1]).trim()
            if (t.isNotEmpty()) return t
        }
        hTitleRe.find(html)?.let {
            val t = stripInline(it.groupValues[1]).trim()
            if (t.isNotEmpty() && t.length <= 200) return t
        }
        if (bookTitle.isNotBlank()) return "$bookTitle $idx"
        return "$defaultTitle $idx"
    }

    // ------------------------------------------------------ decode + strip

    /** Giải mã bytes entry: BOM/XML-decl/meta → strict; fail → Latin-1 giữ byte (cho TCVN3). */
    internal fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return try {
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            } catch (_: Exception) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
                return try {
                    String(bytes, Charsets.UTF_16LE)
                } catch (_: Exception) {
                    String(bytes, Charsets.ISO_8859_1)
                }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
                return try {
                    String(bytes, Charsets.UTF_16BE)
                } catch (_: Exception) {
                    String(bytes, Charsets.ISO_8859_1)
                }
        }
        val head = String(bytes, 0, minOf(bytes.size, 2000), Charsets.ISO_8859_1)
        val declared = xmlEncRe.find(head)?.groupValues?.get(1)
            ?: metaCharsetRe.find(head)?.groupValues?.get(1)
        if (declared != null) {
            val cs = try {
                Charset.forName(declared.trim())
            } catch (_: Exception) {
                null
            }
            if (cs != null) {
                try {
                    return cs.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (_: CharacterCodingException) {
                    // declared sai (vd ghi utf-8 nhưng ruột TCVN3) → rơi xuống Latin-1
                }
            }
        }
        // Mặc định UTF-8 strict → fail thì Latin-1 (giữ nguyên byte cho TCVN3 detect)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private val blockTags = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol",
        "blockquote", "section", "article", "header", "footer", "table", "tr",
        "td", "th", "figure", "figcaption", "hr", "pre", "address", "dl",
        "dt", "dd", "main", "aside", "body", "title"
    )
    private val skipTags = setOf("head", "script", "style")

    /**
     * XHTML → text 1 pass: bỏ head/script/style + comment, block → \n,
     * entities (tên + số). Không regex trên cả file (tránh StackOverflow như PDF).
     */
    internal fun xhtmlToText(html: String): String {
        if (html.isEmpty()) return ""
        val out = StringBuilder(html.length / 2 + 16)
        var lastBreak = true
        fun needBreak() {
            if (!lastBreak && out.isNotEmpty()) {
                out.append('\n')
                lastBreak = true
            }
        }
        var i = 0
        val n = html.length
        var skipDepth = 0
        var skipTag = ""
        // Số trang in (Gutenberg `<span class="x-ebookmaker-pageno">[Pg 62]</span>`)
        // không phải nội dung — ẩn cả display lẫn TTS (ReadEra cũng ẩn).
        var pageNoDepth = 0
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                // Comment
                if (html.startsWith("<!--", i)) {
                    val e = html.indexOf("-->", i + 4)
                    i = if (e < 0) n else e + 3
                    continue
                }
                // CDATA → giữ text bên trong (trừ khi đang bỏ span số trang)
                if (html.startsWith("<![CDATA[", i)) {
                    val e = html.indexOf("]]>", i + 9)
                    val inner = if (e < 0) html.substring(i + 9) else html.substring(i + 9, e)
                    i = if (e < 0) n else e + 3
                    if (skipDepth == 0 && pageNoDepth == 0 && inner.isNotEmpty()) {
                        out.append(inner)
                        lastBreak = false
                    }
                    continue
                }
                // <!DOCTYPE ...> / <?...?> → bỏ
                if (i + 1 < n && (html[i + 1] == '!' || html[i + 1] == '?')) {
                    val e = html.indexOf('>', i + 2)
                    i = if (e < 0) n else e + 1
                    continue
                }
                val e = html.indexOf('>', i + 1)
                if (e < 0) break
                val tag = html.substring(i + 1, e).trim()
                val closing = tag.startsWith("/")
                val name = tag.trimStart('/').substringBefore(' ').substringBefore('\t')
                    .substringBefore('\n').substringBefore('/').lowercase()
                if (pageNoDepth > 0) {
                    if (!closing && name == "span") pageNoDepth++
                    else if (closing && name == "span") pageNoDepth--
                    i = e + 1
                    continue
                }
                if (!closing && name == "span" && tag.lowercase().contains("pageno")) {
                    pageNoDepth = 1
                    i = e + 1
                    continue
                }
                if (skipDepth > 0) {
                    if (!closing && name == skipTag) skipDepth++
                    else if (closing && name == skipTag) {
                        skipDepth--
                        if (skipDepth == 0) skipTag = ""
                    }
                    i = e + 1
                    continue
                }
                if (!closing && name in skipTags) {
                    // <script ...> không tự đóng — script/style/head hiếm khi self-close
                    if (!tag.trimEnd().endsWith("/")) {
                        skipDepth = 1
                        skipTag = name
                    }
                    i = e + 1
                    continue
                }
                if (name == "br") needBreak()
                else if (name in blockTags) needBreak()
                i = e + 1
                continue
            }
            if (skipDepth > 0 || pageNoDepth > 0) {
                i++
                continue
            }
            if (c == '&') {
                val e = html.indexOf(';', i + 1)
                if (e < 0 || e - i > 10) {
                    out.append(c)
                    lastBreak = false
                    i++
                    continue
                }
                val ent = html.substring(i + 1, e)
                val decoded = decodeEntity(ent)
                if (decoded != null) {
                    if (decoded == "\n") needBreak()
                    else {
                        out.append(decoded)
                        lastBreak = false
                    }
                } else {
                    out.append(c)
                    lastBreak = false
                }
                i = e + 1
                continue
            }
            out.append(c)
            lastBreak = c == '\n'
            i++
        }
        return out.toString()
    }

    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "copy" to "©", "reg" to "®",
        "mdash" to "—", "ndash" to "–",
        "ldquo" to "\u201C", "rdquo" to "\u201D",
        "lsquo" to "\u2018", "rsquo" to "\u2019",
        "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "deg" to "°", "times" to "×", "divide" to "÷"
    )

    private fun decodeEntity(ent: String): String? {
        if (ent.isEmpty()) return null
        if (ent[0] == '#') {
            return try {
                val cp = if (ent.length > 1 && (ent[1] == 'x' || ent[1] == 'X')) {
                    ent.substring(2).toInt(16)
                } else {
                    ent.substring(1).toInt()
                }
                if (cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) null
                else String(Character.toChars(cp))
            } catch (_: Exception) {
                null
            }
        }
        return namedEntities[ent]
    }

    /** Strip tag inline cho tiêu đề (không cần block-newline). */
    private fun stripInline(s: String): String {
        if (s.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '<') {
                val e = s.indexOf('>', i + 1)
                i = if (e < 0) s.length else e + 1
                sb.append(' ')
            } else if (c == '&') {
                val e = s.indexOf(';', i + 1)
                if (e < 0 || e - i > 10) {
                    sb.append(c)
                    i++
                } else {
                    sb.append(decodeEntity(s.substring(i + 1, e)) ?: "&")
                    i = e + 1
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ B1.1 metadata + cover

    /** Tác giả/series từ OPF (dc:creator, calibre:series) — pure regex như spinePlan. */
    data class EpubMeta(val author: String = "", val series: String = "")

    private val dcCreatorRe =
        Regex("""<(?:\w+:)?creator\b[^>]*>(.*?)</(?:\w+:)?creator>""", RegexOption.DOT_MATCHES_ALL)
    private val seriesMetaRe =
        Regex("""<meta\b[^>]*?(?:name\s*=\s*["']calibre:series["'][^>]*?content\s*=\s*["']([^"']+)["']|content\s*=\s*["']([^"']+)["'][^>]*?name\s*=\s*["']calibre:series["'])""", RegexOption.IGNORE_CASE)
    private val coverMetaRe =
        Regex("""<meta\b[^>]*?name\s*=\s*["']cover["'][^>]*?content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun opfText(zip: ZipFile): Pair<String, String>? {
        return try {
            val container = zip.getEntry("META-INF/container.xml") ?: return null
            val containerXml = decodeBytes(readEntry(zip, container) ?: return null)
            val opfPath = rootfileRe.find(containerXml)?.groupValues?.get(1) ?: return null
            val opfEntry = zip.getEntry(opfPath) ?: return null
            val opf = decodeBytes(readEntry(zip, opfEntry) ?: return null)
            val base = opfPath.substringBeforeLast('/', "") + "/"
            opf to base
        } catch (_: Exception) {
            null
        }
    }

    /** Đọc tác giả/series (rẻ: chỉ parse OPF, không đụng content). */
    fun readMeta(file: File): EpubMeta {
        if (!file.exists()) return EpubMeta()
        return try {
            ZipFile(file).use { zip ->
                val (opf, _) = opfText(zip) ?: return EpubMeta()
                val author = dcCreatorRe.find(opf)?.groupValues?.get(1)
                    ?.let { stripInline(it).replace(Regex("\\s+"), " ").trim() } ?: ""
                val sm = seriesMetaRe.find(opf)
                val series = (sm?.groupValues?.get(1)?.ifBlank { sm.groupValues.get(2) } ?: "")
                    .let { stripInline(it).replace(Regex("\\s+"), " ").trim() }
                EpubMeta(author.take(120), series.take(120))
            }
        } catch (_: Exception) {
            EpubMeta()
        }
    }

    /**
     * Đọc bytes ảnh bìa (EPUB2 `<meta name="cover">` → manifest id;
     * fallback item properties `cover-image`; fallback item ảnh đầu có id/href
     * chứa "cover"). Cap 2MB — caller tự decode sampled.
     */
    fun readCover(file: File, maxBytes: Int = 2 * 1024 * 1024): ByteArray? {
        if (!file.exists()) return null
        return try {
            ZipFile(file).use { zip ->
                val (opf, base) = opfText(zip) ?: return null
                val idToHref = HashMap<String, String>()
                val idToMime = HashMap<String, String>()
                var coverImageHref: String? = null
                var firstImageHref: String? = null
                for (t in itemTagRe.findAll(opf)) {
                    val a = attrs(t.value)
                    val id = a["id"] ?: continue
                    val href = a["href"] ?: continue
                    val mime = (a["media-type"] ?: "").lowercase()
                    idToHref[id] = href
                    idToMime[id] = mime
                    if (!mime.startsWith("image/")) continue
                    if (firstImageHref == null) firstImageHref = href
                    if ((a["properties"] ?: "").lowercase().split(' ').any { it == "cover-image" }) {
                        coverImageHref = href
                    }
                    if (id.lowercase().contains("cover") || href.lowercase().contains("cover")) {
                        if (coverImageHref == null || !coverImageHref.lowercase().contains("cover")) {
                            coverImageHref = href
                        }
                    }
                }
                val metaId = coverMetaRe.find(opf)?.groupValues?.get(1)
                val href = (metaId?.let { idToHref[it] } ?: coverImageHref ?: firstImageHref)
                    ?.takeIf { it.isNotBlank() } ?: return null
                val entry = zip.getEntry(resolveHref(base, href)) ?: return null
                if (entry.isDirectory) return null
                zip.getInputStream(entry).use { input ->
                    val bos = ByteArrayOutputStream(64 * 1024)
                    val buf = ByteArray(32 * 1024)
                    var total = 0
                    while (total < maxBytes) {
                        val r = input.read(buf, 0, minOf(buf.size, maxBytes - total))
                        if (r < 0) break
                        bos.write(buf, 0, r)
                        total += r
                    }
                    bos.toByteArray().takeIf { it.isNotEmpty() }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
