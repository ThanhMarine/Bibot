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
        return spineContents(file, defaultTitle)
            .map { Chapter(it.title, xhtmlToText(it.html)) }
            .ifEmpty { listOf(Chapter(defaultTitle, "")) }
    }

    // -------------------------------------------------- rich (scope B: giữ bản gốc)

    /** Kiểu khối hiển thị nguyên bản (scope B: ảnh + format, ReadEra-like). */
    enum class RichStyle { BODY, H1, H2, H3, CAPTION, CENTER }

    /** Đoạn inline trong khối text (đậm/nghiêng giữ nguyên bản). */
    data class RichInline(val text: String, val bold: Boolean, val italic: Boolean)

    /** 1 khối hiển thị: đoạn text / ảnh minh họa / gạch ngang. */
    sealed interface RichBlock {
        data class Para(val lines: List<RichInline>, val style: RichStyle) : RichBlock {
            val text: String get() = lines.joinToString("") { it.text }
        }

        data class Figure(val path: String, val alt: String) : RichBlock
        data object Rule : RichBlock
    }

    /** 1 spine item: title + html thô + base resolve ảnh (dùng chung cho text/rich). */
    private data class SpineContent(val title: String, val html: String, val base: String)

    private fun spineContents(file: File, defaultTitle: String): List<SpineContent> {
        if (!file.exists()) return listOf(SpineContent(defaultTitle, "", ""))
        val out = ArrayList<SpineContent>()
        try {
            ZipFile(file).use { zip ->
                val plan = spinePlan(zip) ?: return listOf(SpineContent(defaultTitle, "", ""))
                val base = try { opfText(zip)?.second ?: "" } catch (_: Exception) { "" }
                var idx = 0
                for (item in plan.items.take(MAX_SPINE_ITEMS)) {
                    idx++
                    val entry = zip.getEntry(item.path) ?: continue
                    val bytes = readEntry(zip, entry) ?: continue
                    val html = decodeBytes(bytes)
                    val title = chapterTitle(html, plan.bookTitle, idx, defaultTitle)
                    out.add(SpineContent(title, html, base))
                }
            }
        } catch (_: Exception) {
            // zip hỏng → trả phần đã có (có thể rỗng)
        }
        return out.ifEmpty { listOf(SpineContent(defaultTitle, "", "")) }
    }

    /** Titles đúng thứ tự spine — KHỚP 1-1 với [chapters] (cùng traversal). */
    fun richTitles(file: File, defaultTitle: String = "Nội dung"): List<String> =
        spineContents(file, defaultTitle).map { it.title }

    /** Khối hiển thị của 1 spine item (index theo [richTitles]/[chapters]). */
    fun richChapterAt(file: File, defaultTitle: String = "Nội dung", chapterIdx: Int): List<RichBlock> {
        if (!file.exists() || chapterIdx < 0) return emptyList()
        return try {
            val items = spineContents(file, defaultTitle)
            if (chapterIdx >= items.size) return emptyList()
            val sc = items[chapterIdx]
            if (sc.html.isBlank()) return emptyList()
            richParse(sc.html, sc.base)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Đọc bytes ảnh trong EPUB theo path đã resolve (cho view rich).
     * Cap 3MB — caller tự decode sampled (tránh OOM).
     */
    fun readImageBytes(file: File, imgPath: String, maxBytes: Int = 3 * 1024 * 1024): ByteArray? {
        if (!file.exists() || imgPath.isBlank()) return null
        return try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(imgPath) ?: return null
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

    private val richBlockTags = setOf(
        "p", "div", "section", "article", "header", "footer", "li", "ul", "ol",
        "blockquote", "table", "tr", "td", "th", "figure", "figcaption", "pre", "main", "aside"
    )
    private val richHeadingTag = mapOf(
        "h1" to RichStyle.H1, "h2" to RichStyle.H2, "h3" to RichStyle.H3,
        "h4" to RichStyle.H3, "h5" to RichStyle.H3, "h6" to RichStyle.H3
    )

    private fun hasClass(tag: String, cls: String): Boolean {
        // class="a caption b" / class='caption' — so khớp thô, không parse full attr.
        val low = tag.lowercase()
        val key = "class="
        val ci = low.indexOf(key)
        if (ci < 0) return false
        var v = tag.substring(ci + key.length).trimStart()
        if (v.startsWith("\"") || v.startsWith("'")) {
            val q = v[0]
            val e = v.indexOf(q, 1)
            if (e < 0) return false
            v = v.substring(1, e)
        } else {
            v = v.substringBefore(' ').substringBefore('\t')
        }
        return v.split(Regex("\\s+")).any { it.equals(cls, ignoreCase = true) }
    }

    /**
     * HTML spine item → khối hiển thị (1 pass, không regex cả file):
     * heading/đoạn/caption giữ style, `<img>` → Figure (path đã resolve),
     * inline b/i giữ đậm-nghiêng. Text trích GIỮ nguyên từ (không rewrite TTS).
     */
    internal fun richParse(html: String, base: String): List<RichBlock> {
        if (html.isEmpty()) return emptyList()
        val blocks = ArrayList<RichBlock>()
        val segs = ArrayList<RichInline>()
        val cur = StringBuilder()
        var curBold = false
        var curItalic = false
        var curStyle: RichStyle = RichStyle.BODY
        var captionDepth = 0
        val boldStack = ArrayDeque<Boolean>()
        val italicStack = ArrayDeque<Boolean>()
        var skipDepth = 0
        var skipTag = ""

        fun flushSeg() {
            if (cur.isNotEmpty()) {
                segs.add(RichInline(cur.toString(), curBold, curItalic))
                cur.setLength(0)
            }
        }

        fun flushPara(style: RichStyle = curStyle) {
            flushSeg()
            if (segs.isNotEmpty()) {
                blocks.add(RichBlock.Para(segs.toList(), style))
                segs.clear()
            }
            curStyle = RichStyle.BODY
        }

        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                if (html.startsWith("<!--", i)) {
                    val e = html.indexOf("-->", i + 4)
                    i = if (e < 0) n else e + 3
                    continue
                }
                if (html.startsWith("<![CDATA[", i)) {
                    val e = html.indexOf("]]>", i + 9)
                    val inner = if (e < 0) html.substring(i + 9) else html.substring(i + 9, e)
                    i = if (e < 0) n else e + 3
                    if (skipDepth == 0 && inner.isNotEmpty()) cur.append(inner)
                    continue
                }
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
                val selfClose = tag.trimEnd().endsWith("/")
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
                    if (!selfClose) {
                        skipDepth = 1
                        skipTag = name
                    }
                    i = e + 1
                    continue
                }
                val isCaption = !closing && (name == "figcaption" ||
                    ((name == "span" || name == "div" || name == "p") && hasClass(tag, "caption")))
                if (isCaption) {
                    flushPara()
                    captionDepth++
                    i = e + 1
                    continue
                }
                if (closing && captionDepth > 0 &&
                    (name == "figcaption" || name == "span" || name == "div" || name == "p")
                ) {
                    flushPara(RichStyle.CAPTION)
                    captionDepth--
                    i = e + 1
                    continue
                }
                if (!closing && name == "img") {
                    flushPara()
                    val src = Regex("""src\s*=\s*["']([^"']+)""", RegexOption.IGNORE_CASE)
                        .find(tag)?.groupValues?.get(1).orEmpty()
                    val alt = Regex("""alt\s*=\s*["']([^"']*)""", RegexOption.IGNORE_CASE)
                        .find(tag)?.groupValues?.get(1).orEmpty()
                    val path = if (src.isBlank() || src.contains("://")) "" else resolveHref(base, src)
                    if (path.isNotEmpty()) blocks.add(RichBlock.Figure(path, alt))
                    i = e + 1
                    continue
                }
                if (!closing && (name == "br" || name == "hr")) {
                    if (name == "hr") {
                        flushPara()
                        blocks.add(RichBlock.Rule)
                    } else {
                        flushSeg()
                        segs.add(RichInline("\n", curBold, curItalic))
                    }
                    i = e + 1
                    continue
                }
                if (name in richHeadingTag) {
                    if (!closing) {
                        flushPara()
                        curStyle = richHeadingTag[name] ?: RichStyle.BODY
                    } else {
                        flushPara()
                    }
                    i = e + 1
                    continue
                }
                if (name in richBlockTags) {
                    if (!closing) {
                        flushPara()
                        val tl = tag.lowercase().replace(" ", "")
                        if (hasClass(tag, "center") || tl.contains("text-align:center")) {
                            curStyle = RichStyle.CENTER
                        }
                    } else {
                        flushPara()
                    }
                    i = e + 1
                    continue
                }
                // Inline: b/strong, i/em (stack để lồng nhau đúng).
                if (!closing && (name == "b" || name == "strong")) {
                    flushSeg(); boldStack.addLast(curBold); curBold = true
                    i = e + 1; continue
                }
                if (closing && (name == "b" || name == "strong")) {
                    flushSeg(); curBold = boldStack.removeLastOrNull() ?: false
                    i = e + 1; continue
                }
                if (!closing && (name == "i" || name == "em")) {
                    flushSeg(); italicStack.addLast(curItalic); curItalic = true
                    i = e + 1; continue
                }
                if (closing && (name == "i" || name == "em")) {
                    flushSeg(); curItalic = italicStack.removeLastOrNull() ?: false
                    i = e + 1; continue
                }
                // Tag inline còn lại (a/span/small/...) → bỏ tag giữ chữ.
                i = e + 1
                continue
            }
            if (skipDepth > 0) {
                i++
                continue
            }
            if (c == '&') {
                val en = html.indexOf(';', i + 1)
                if (en < 0 || en - i > 10) {
                    cur.append(c)
                } else {
                    cur.append(decodeEntity(html.substring(i + 1, en)) ?: "&")
                    i = en + 1
                    continue
                }
                i++
                continue
            }
            if (c < ' ' && c != '\n' && c != '\t') {
                i++
                continue
            }
            cur.append(c)
            i++
        }
        flushPara(if (captionDepth > 0) RichStyle.CAPTION else curStyle)
        return blocks.filter {
            when (it) {
                is RichBlock.Para -> it.text.isNotBlank()
                else -> true
            }
        }
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
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                // Comment
                if (html.startsWith("<!--", i)) {
                    val e = html.indexOf("-->", i + 4)
                    i = if (e < 0) n else e + 3
                    continue
                }
                // CDATA → giữ text bên trong
                if (html.startsWith("<![CDATA[", i)) {
                    val e = html.indexOf("]]>", i + 9)
                    val inner = if (e < 0) html.substring(i + 9) else html.substring(i + 9, e)
                    i = if (e < 0) n else e + 3
                    if (skipDepth == 0 && inner.isNotEmpty()) {
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
            if (skipDepth > 0) {
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
