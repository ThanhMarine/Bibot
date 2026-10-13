package com.bibot.app.core.text

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Đọc MOBI/AZW (MobiPocket Mobi6, pure JDK, không thêm dependency).
 *
 * Format thật (PalmDB):
 *  - Header 78B: tên[32] + ... + type "BOOK" + creator "MOBI" + numRecords[2BE].
 *  - Danh sách record: offset[4BE] + attr[1] + uid[3].
 *  - Record 0: PalmDoc header 16B (compression 1=none / 2=PalmDoc / 17480=HuffCdic,
 *    textLength, recordCount, recordSize) + MOBI header ("MOBI", textEncoding
 *    1252/65001, EXTH flag 0x40) + EXTH ("EXTH": 503=title, 100=author).
 *  - Record 1..N: text (PalmDoc LZ77 hoặc raw).
 *
 * Giới hạn trung thực v1:
 *  - HuffCdic (17480) và DRM (encryptionType != 0) → lỗi rõ, không đọc bừa.
 *  - KF8/AZW3 dual-format: chỉ đọc phần Mobi6 (bỏ KF8).
 *  - Chương = chunk ~32k ký tự (MOBI Mobi6 không có NCX như EPUB).
 *
 * A2.2: MOBI → EPUB rồi reuse renderer — [convertToEpub] dựng EPUB tối thiểu
 * (mimetype + container + OPF + XHTML) để [EpubReader] đọc lại; các luồng text
 * ([BlockTextReader], `BookRepository.iterSentences`) dùng [chapters]/[iterText].
 */
object MobiReader {

    const val MIME_MOBI = "application/x-mobipocket-ebook"

    fun isMobiExt(name: String): Boolean {
        val e = name.substringAfterLast('.', "").lowercase()
        return e == "mobi" || e == "azw" || e == "azw3"
    }

    data class Chapter(val title: String, val text: String)

    data class MobiMeta(val title: String, val author: String)

    data class MobiResult(
        val title: String,
        val author: String?,
        val chapters: List<String>,
        val isScanned: Boolean,
        val error: String?
    )

    private const val CHUNK_CHARS = 32 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024 * 1024

    // ------------------------------------------------------------ detect

    /** True nếu là PalmDB BOOK/MOBI (check magic, không chỉ tin đuôi file). */
    fun isMobi(file: File): Boolean {
        if (!file.exists() || file.length() < 78) return false
        if (!isMobiExt(file.name)) return false
        return try {
            RandomAccessFile(file, "r").use { r ->
                r.seek(60)
                val type = ByteArray(4).also { r.readFully(it) }
                val creator = ByteArray(4).also { r.readFully(it) }
                String(type, Charsets.US_ASCII) == "BOOK" &&
                    String(creator, Charsets.US_ASCII) == "MOBI"
            }
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------ parse

    private data class PalmDoc(
        val compression: Int,
        val textLength: Long,
        val recordCount: Int,
        val recordSize: Int,
        val encryption: Int
    )

    private data class Parsed(
        val text: String,
        val meta: MobiMeta
    )

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun parse(file: File): Parsed {
        RandomAccessFile(file, "r").use { r ->
            val hdr = ByteArray(78)
            r.readFully(hdr)
            val pdbName = String(hdr, 0, 32, Charset.forName("windows-1252")).trim { it <= ' ' || it == '\u0000' }
            val numRec = u16(hdr, 76)
            if (numRec < 1 || numRec > 10_000) throw IllegalStateException("numRecords lạ: $numRec")

            val recOff = LongArray(numRec)
            val recList = ByteArray(numRec * 8)
            r.readFully(recList)
            for (i in 0 until numRec) recOff[i] = u32(recList, i * 8)
            val fileLen = file.length()
            fun recEnd(i: Int): Long = if (i + 1 < numRec) recOff[i + 1] else fileLen
            fun readRec(i: Int): ByteArray {
                val start = recOff[i]
                val end = recEnd(i).coerceAtMost(start + 256 * 1024)
                if (start < 0 || end <= start) return ByteArray(0)
                val n = (end - start).toInt()
                val b = ByteArray(n)
                r.seek(start)
                r.readFully(b)
                return b
            }

            val rec0 = readRec(0)
            if (rec0.size < 32) throw IllegalStateException("record 0 quá ngắn")
            val palm = PalmDoc(
                compression = u16(rec0, 0),
                textLength = u32(rec0, 4),
                recordCount = u16(rec0, 8),
                recordSize = u16(rec0, 10),
                encryption = u16(rec0, 12)
            )
            if (palm.encryption != 0) throw IllegalStateException("MOBI có DRM (encryption=${palm.encryption}), không đọc được")
            if (palm.compression == 17480) throw IllegalStateException("MOBI nén HuffCdic, chưa hỗ trợ (chỉ PalmDoc/raw)")
            if (palm.compression != 1 && palm.compression != 2)
                throw IllegalStateException("compression lạ: ${palm.compression}")

            var charset: Charset = Charsets.UTF_8
            var title = pdbName.ifBlank { file.nameWithoutExtension }
            var author = ""
            // MOBI header: "MOBI" ở +16
            if (rec0.size >= 20 && String(rec0, 16, 4, Charsets.US_ASCII) == "MOBI") {
                val enc = u32(rec0, 28).toInt()
                charset = when (enc) {
                    65001 -> Charsets.UTF_8
                    1252 -> Charset.forName("windows-1252")
                    else -> Charsets.UTF_8
                }
                val mobiHdrLen = u32(rec0, 20).toInt()
                // exthFlags ở MOBI-header +0x80 (tức rec0 +16+0x80)
                val exthFlags = if (rec0.size >= 16 + 0x80 + 4) u32(rec0, 16 + 0x80).toInt() else 0
                if (exthFlags and 0x40 != 0 && mobiHdrLen in 16..1024 && 16 + mobiHdrLen + 12 <= rec0.size) {
                    val eo = 16 + mobiHdrLen
                    if (String(rec0, eo, 4, Charsets.US_ASCII) == "EXTH") {
                        // "EXTH"(4) + len(4) + count(4) rồi mới tới records
                        var p = eo + 12
                        val count = u32(rec0, eo + 4).toInt().coerceIn(0, 500)
                        repeat(count) {
                            if (p + 8 > rec0.size) return@repeat
                            val type = u32(rec0, p).toInt()
                            val len = u32(rec0, p + 4).toInt()
                            if (len < 8 || p + len > rec0.size) return@repeat
                            val data = rec0.copyOfRange(p + 8, p + len)
                            // EXTH string có thể null-terminated / UTF-16 với BOM
                            val s = decodeExthString(data, charset)
                            when (type) {
                                503 -> if (s.isNotBlank()) title = s
                                100 -> if (s.isNotBlank() && author.isBlank()) author = s
                            }
                            p += len
                        }
                    }
                }
            }

            val nText = palm.recordCount.coerceIn(1, numRec - 1)
            val raw = ByteArrayOutputStream(minOf(palm.textLength.coerceAtMost(MAX_TEXT_BYTES.toLong()), MAX_TEXT_BYTES.toLong()).toInt().coerceAtLeast(0))
            var budget = MAX_TEXT_BYTES
            for (i in 1..nText) {
                if (budget <= 0) break
                var rec = readRec(i)
                if (rec.size > budget + 4096) rec = rec.copyOf(budget + 4096)
                val out = if (palm.compression == 2) palmdocDecompress(rec) else rec
                raw.write(out, 0, minOf(out.size, budget))
                budget -= out.size
            }
            val bytes = raw.toByteArray()
            // textLength=0 ở vài file lậu → vẫn đọc hết record (đã làm ở trên)
            val text = mobiHtmlToText(decodeBytes(bytes, charset))
            return Parsed(text, MobiMeta(title.take(200), author.take(120)))
        }
    }

    private fun decodeBytes(bytes: ByteArray, charset: Charset): String {
        if (bytes.isEmpty()) return ""
        return try {
            charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun decodeExthString(data: ByteArray, charset: Charset): String {
        var d = data
        // UTF-16 BOM
        if (d.size >= 2 && ((d[0] == 0xFE.toByte() && d[1] == 0xFF.toByte()) ||
                (d[0] == 0xFF.toByte() && d[1] == 0xFE.toByte()))
        ) {
            return try {
                String(d, Charset.forName("UTF-16")).trim { it <= ' ' || it == '\u0000' }
            } catch (_: Exception) { "" }
        }
        // Cắt null terminator
        val zi = d.indexOf(0.toByte()).takeIf { it >= 0 }
        if (zi != null) d = d.copyOfRange(0, zi)
        return decodeBytes(d, charset).trim { it <= ' ' || it == '\u0000' }
            .replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Giải nén PalmDoc LZ77 (mobiunpack
usupport).
     *  - 0x00–0x08: copy (c+1) byte literal tiếp theo.
     *  - 0x09–0x7F: literal 1 byte.
     *  - 0x80–0xBF + byte2: back-reference (length/dist).
     *  - 0xC0–0xFF: " " + (byte xor 0x80).
     */
    internal fun palmdocDecompress(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size * 2 + 16)
        val buf = ArrayList<Byte>(src.size * 2 + 16)
        var i = 0
        while (i < src.size) {
            val c = src[i].toInt() and 0xFF
            i++
            when {
                c in 0x01..0x08 -> {
                    repeat(c) {
                        if (i >= src.size) return@repeat
                        buf.add(src[i])
                        i++
                    }
                }
                c < 0x80 -> buf.add(c.toByte())
                c in 0x80..0xBF -> {
                    if (i >= src.size) break
                    val c2 = src[i].toInt() and 0xFF
                    i++
                    val combined = (c shl 8) or c2
                    val length = (combined and 0x7) + 3
                    var dist = (combined shr 3) and 0x7FF
                    if (dist == 0) break
                    var srcPos = buf.size - dist
                    repeat(length) {
                        buf.add(if (srcPos in buf.indices) buf[srcPos++] else 0.toByte())
                    }
                }
                else -> {
                    buf.add(' '.code.toByte())
                    buf.add((c xor 0x80).toByte())
                }
            }
        }
        buf.forEach { out.write(it.toInt()) }
        return out.toByteArray()
    }

    /** HTML Mobipocket → text (block → xuống dòng, entities, bỏ tag còn lại). */
    internal fun mobiHtmlToText(html: String): String {
        if (html.isEmpty()) return ""
        val out = StringBuilder(html.length / 2 + 16)
        var lastBreak = true
        fun needBreak() {
            if (!lastBreak && out.isNotEmpty()) {
                out.append('\n')
                lastBreak = true
            }
        }
        val blockTags = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol",
            "blockquote", "section", "article", "header", "footer", "table", "tr",
            "td", "th", "hr", "pre", "br", "mbp:pagebreak", "pagebreak"
        )
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
                val e = html.indexOf('>', i + 1)
                if (e < 0) break
                val tag = html.substring(i + 1, e).trim()
                val name = tag.trimStart('/').substringBefore(' ').substringBefore('\t')
                    .substringBefore('\n').substringBefore('/').lowercase()
                if (name in blockTags) needBreak()
                i = e + 1
                continue
            }
            if (c == '&') {
                val e = html.indexOf(';', i + 1)
                if (e < 0 || e - i > 10) {
                    out.append(c); lastBreak = false; i++; continue
                }
                val ent = html.substring(i + 1, e)
                val d = decodeEntity(ent)
                if (d != null) {
                    if (d == "\n") needBreak() else {
                        out.append(d); lastBreak = false
                    }
                } else {
                    out.append(c); lastBreak = false
                }
                i = e + 1
                continue
            }
            // Ký tự điều khiển PalmDoc (0x00–0x08, trừ \n\t) → bỏ
            if (c < ' ' && c != '\n' && c != '\t') {
                i++; continue
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
        "hellip" to "…", "laquo" to "«", "raquo" to "»"
    )

    private fun decodeEntity(ent: String): String? {
        if (ent.isEmpty()) return null
        if (ent[0] == '#') {
            return try {
                val cp = if (ent.length > 1 && (ent[1] == 'x' || ent[1] == 'X')) ent.substring(2).toInt(16)
                else ent.substring(1).toInt()
                if (cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) null
                else String(Character.toChars(cp))
            } catch (_: Exception) {
                null
            }
        }
        return namedEntities[ent]
    }

    // ------------------------------------------------------------ public API

    fun readMeta(file: File): MobiMeta {
        if (!file.exists()) return MobiMeta(file.nameWithoutExtension, "")
        return try {
            parse(file).meta
        } catch (_: Exception) {
            MobiMeta(file.nameWithoutExtension, "")
        }
    }

    /** Text toàn sách chia chunk (stream từng record, không giữ cả sách quá 1 lần). */
    fun iterText(file: File): Sequence<String> = sequence {
        if (!file.exists() || !isMobi(file)) return@sequence
        try {
            val text = parse(file).text
            var start = 0
            while (start < text.length) {
                val end = minOf(start + CHUNK_CHARS, text.length)
                val chunk = text.substring(start, end)
                if (chunk.isNotBlank()) yield(chunk)
                if (end >= text.length) break
                start = end
            }
        } catch (_: Exception) { /* yield phần đã có */ }
    }

    fun chapters(file: File, defaultTitle: String = "Nội dung"): List<Chapter> {
        if (!file.exists()) return listOf(Chapter(defaultTitle, ""))
        return try {
            val text = parse(file).text
            if (text.isBlank()) return listOf(Chapter(defaultTitle, ""))
            val out = ArrayList<Chapter>()
            var start = 0
            var idx = 1
            while (start < text.length && out.size < 500) {
                val end = minOf(start + CHUNK_CHARS, text.length)
                // Cắt ở ranh giới dòng cho sạch
                var cut = end
                if (end < text.length) {
                    val nl = text.lastIndexOf('\n', end - 1)
                    if (nl > start) cut = nl + 1
                }
                val part = text.substring(start, cut)
                if (part.isNotBlank()) out.add(Chapter("$defaultTitle $idx", part))
                idx++
                if (cut <= start) break
                start = cut
            }
            out.ifEmpty { listOf(Chapter(defaultTitle, "")) }
        } catch (_: Exception) {
            listOf(Chapter(defaultTitle, ""))
        }
    }

    fun readMobi(file: File): MobiResult {
        return try {
            if (!file.exists()) return MobiResult("Unknown", null, listOf(), true, "File not found")
            if (!isMobi(file)) return MobiResult("Unknown", null, listOf(), true, "Không phải MOBI")
            val p = parse(file)
            val chaps = chapters(file, p.meta.title.ifBlank { "Nội dung" }).map { it.text }
            val scanned = chaps.isEmpty() || chaps.sumOf { it.length } < 200
            MobiResult(
                p.meta.title.ifBlank { "Unknown" },
                p.meta.author.ifBlank { null },
                chaps, scanned, null
            )
        } catch (e: Exception) {
            MobiResult("Unknown", null, listOf(), true, e.message ?: "Unknown error")
        }
    }

    /**
     * MOBI → EPUB tối thiểu để reuse renderer ([EpubReader]): mimetype (stored)
     * + container.xml + content.opf + XHTML chia ~200KB/chunk. Ghi vào [outDir].
     * @return file EPUB hoặc null khi MOBI lỗi/DRM/HuffCdic.
     */
    fun convertToEpub(mobi: File, outDir: File, defaultTitle: String = "Nội dung"): File? {
        return try {
            if (!mobi.exists() || !isMobi(mobi)) return null
            val p = parse(mobi)
            if (p.text.isBlank()) return null
            outDir.mkdirs()
            val safe = mobi.nameWithoutExtension.take(60).replace(Regex("[^A-Za-z0-9À-ỹ_\\-]+"), "_")
            val epub = File(outDir, "$safe.mobi.epub")
            // Cache: MOBI cũ hơn EPUB có sẵn → tái dùng
            if (epub.exists() && epub.length() > 0 && epub.lastModified() >= mobi.lastModified()) return epub

            val title = p.meta.title.ifBlank { defaultTitle }.escapeXml()
            val author = p.meta.author.escapeXml()
            val parts = ArrayList<String>()
            var start = 0
            while (start < p.text.length) {
                val end = minOf(start + 200 * 1024, p.text.length)
                var cut = end
                if (end < p.text.length) {
                    val nl = p.text.lastIndexOf('\n', end - 1)
                    if (nl > start) cut = nl + 1
                }
                parts.add(p.text.substring(start, cut))
                if (cut <= start) break
                start = cut
            }
            ZipOutputStream(epub.outputStream().buffered()).use { zip ->
                // mimetype PHẢI là entry đầu, stored (EPUB spec)
                val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
                val mimeCrc = CRC32().also { it.update(mimeBytes) }.value
                zip.putNextEntry(ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimeBytes.size.toLong()
                    crc = mimeCrc
                })
                zip.write(mimeBytes)
                zip.closeEntry()

                fun add(name: String, content: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
                add(
                    "META-INF/container.xml",
                    """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray(Charsets.UTF_8)
                )
                val manifest = StringBuilder()
                val spine = StringBuilder()
                parts.forEachIndexed { i, _ ->
                    manifest.append("""<item id="t${i + 1}" href="t${i + 1}.xhtml" media-type="application/xhtml+xml"/>""")
                    spine.append("""<itemref idref="t${i + 1}"/>""")
                }
                add(
                    "OEBPS/content.opf",
                    """<?xml version="1.0" encoding="UTF-8"?>
<package version="2.0" xmlns="http://www.idpf.org/2007/opf" unique-identifier="bid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>$title</dc:title>
    <dc:creator>$author</dc:creator>
    <dc:language>und</dc:language>
    <dc:identifier id="bid">mobi-${safe.hashCode()}</dc:identifier>
  </metadata>
  <manifest>$manifest</manifest>
  <spine>$spine</spine>
</package>""".toByteArray(Charsets.UTF_8)
                )
                parts.forEachIndexed { i, part ->
                    val body = StringBuilder()
                    for (para in part.split('\n')) {
                        val t = para.trim()
                        if (t.isEmpty()) continue
                        body.append("<p>").append(t.escapeXml()).append("</p>")
                    }
                    add(
                        "OEBPS/t${i + 1}.xhtml",
                        """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body>$body</body></html>""".toByteArray(Charsets.UTF_8)
                    )
                }
            }
            epub.takeIf { it.exists() && it.length() > 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
