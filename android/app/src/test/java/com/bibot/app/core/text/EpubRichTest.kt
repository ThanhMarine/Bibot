package com.bibot.app.core.text

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Scope B (EPUB rich, giữ bản gốc): heading/b/i/img/caption parse thành khối,
 * titles rich KHỚP 1-1 chapters text, ảnh đọc được bytes.
 * Chạy: :app:testDemoDebugUnitTest. Pure-JVM.
 */
class EpubRichTest {

    @Test
    fun richParse_headingsAndInline() {
        val html = "<h2>TITLE</h2><p>Hello <b>bold</b> and <i>it</i> end.</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        assertEquals(2, blocks.size)
        val h = blocks[0] as EpubReader.RichBlock.Para
        assertEquals(EpubReader.RichStyle.H2, h.style)
        assertEquals("TITLE", h.text)
        val p = blocks[1] as EpubReader.RichBlock.Para
        assertEquals(EpubReader.RichStyle.BODY, p.style)
        assertTrue(p.lines.any { it.text == "bold" && it.bold && !it.italic })
        assertTrue(p.lines.any { it.text == "it" && it.italic })
        assertTrue(p.text.contains("Hello"))
    }

    @Test
    fun richParse_imageAndCaption() {
        val html = "<div class=\"figcenter\"><img src=\"images/f1.jpg\" alt=\"Fig. 1\"/>" +
            "<span class=\"caption\">Fig. 1. Caption here.</span></div><p>After.</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        val fig = blocks.filterIsInstance<EpubReader.RichBlock.Figure>().firstOrNull()
        assertNotNull(fig)
        assertEquals("OEBPS/images/f1.jpg", fig!!.path)
        val cap = blocks.filterIsInstance<EpubReader.RichBlock.Para>()
            .firstOrNull { it.style == EpubReader.RichStyle.CAPTION }
        assertNotNull(cap)
        assertTrue(cap!!.text.contains("Caption here."))
    }

    @Test
    fun richParse_tableAndRule() {
        val html = "<table><tr><td>A</td><td>B</td></tr></table><hr/><p>Next.</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        assertTrue(blocks.any { it is EpubReader.RichBlock.Rule })
        val txt = blocks.filterIsInstance<EpubReader.RichBlock.Para>().joinToString("\n") { it.text }
        assertTrue(txt.contains("A"))
        assertTrue(txt.contains("Next."))
    }

    private fun buildEpub(): File {
        val f = File.createTempFile("rich", ".epub")
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            fun add(name: String, data: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            add("mimetype", "application/epub+zip".toByteArray())
            add(
                "META-INF/container.xml",
                """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray()
            )
            add(
                "OEBPS/content.opf",
                """<package version="2.0" xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>T</dc:title></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="i" href="pic.jpg" media-type="image/jpeg"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""".toByteArray()
            )
            add(
                "OEBPS/a.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>A</title></head><body><h2>Chap A</h2><p>Text <b>bold</b>.</p><img src="pic.jpg" alt="P"/></body></html>""".toByteArray()
            )
            add(
                "OEBPS/b.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>B</title></head><body><p>Second.</p></body></html>""".toByteArray()
            )
            // JPEG tối thiểu (SOI + EOI) — đủ để readImageBytes trả bytes.
            add("OEBPS/pic.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        }
        return f
    }

    @Test
    fun titlesParity_chaptersVsRich() {
        val f = buildEpub()
        try {
            assertTrue(EpubReader.isEpub(f))
            val chaps = EpubReader.chapters(f, "T")
            val titles = EpubReader.richTitles(f, "T")
            assertEquals(chaps.map { it.title }, titles)
            assertEquals(2, titles.size)
        } finally {
            f.delete()
        }
    }

    @Test
    fun richChapterAt_imagesResolved() {
        val f = buildEpub()
        try {
            val blocks = EpubReader.richChapterAt(f, "T", 0)
            val fig = blocks.filterIsInstance<EpubReader.RichBlock.Figure>().firstOrNull()
            assertNotNull(fig)
            val bytes = EpubReader.readImageBytes(f, fig!!.path)
            assertNotNull(bytes)
            assertTrue(bytes!!.isNotEmpty())
        } finally {
            f.delete()
        }
    }

    @Test
    fun richChapterAt_oobEmpty() {
        val f = buildEpub()
        try {
            assertTrue(EpubReader.richChapterAt(f, "T", 9).isEmpty())
        } finally {
            f.delete()
        }
    }
}
