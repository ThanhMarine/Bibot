package com.bibot.app.core.text

import com.bibot.app.feature.reader.findSentenceBlock
import com.bibot.app.feature.reader.blockLineCount
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
        assertTrue(txt.contains("Next."))
        // Cell text "A" lives in a Table block (not Para) under the Table-block design.
        assertTrue(blocks.filterIsInstance<EpubReader.RichBlock.Table>().any { t ->
            t.rows.any { r -> r.cells.any { it.text.contains("A") } }
        })
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

    // ---- B6.16: karaoke match có thứ tự ----

    @Test
    fun sentenceBlock_orderedRepeat() {
        // Đoạn lặp nguyên văn ở 2 khối → câu sau match khối sau (không nhầm khối trước).
        val blocks = listOf("Mở đầu.", "Lặp lại.", "Giữa.", "Lặp lại.", "Kết.")
        val sents = listOf("Mở đầu.", "Lặp lại.", "Giữa.", "Lặp lại.", "Kết.")
        assertEquals(1, findSentenceBlock(blocks, sents, 1, 0))
        // Thực tế start = khối của câu trước (cache) → câu sau match khối sau.
        assertEquals(2, findSentenceBlock(blocks, sents, 2, 1))
        assertEquals(3, findSentenceBlock(blocks, sents, 3, 2))
    }

    @Test
    fun sentenceBlock_prefixAndFallback() {
        val blocks = listOf("Alpha beta gamma.", "Delta epsilon.")
        // Tiền tố câu (chunker trim) vẫn khớp.
        assertEquals(1, findSentenceBlock(blocks, listOf("  Delta epsilon.  "), 0, 0))
        // Không thấy → -1; hết biên → -1.
        assertEquals(-1, findSentenceBlock(blocks, listOf("Không có."), 0, 0))
        assertEquals(-1, findSentenceBlock(blocks, listOf("Alpha."), 5, 0))
        assertEquals(-1, findSentenceBlock(emptyList(), listOf("A."), 0, 0))
    }

    // ---- E2.5 Week 1: CSS parsing tests ----

    @Test
    fun parseInlineStyle_basic() {
        val style = EpubReader.parseInlineStyle("<p style=\"color:red;background:#fff;font-family:Arial;text-align:center;\">")
        assertEquals("red", style["color"])
        assertEquals("#fff", style["backgroundcolor"])
        assertEquals("Arial", style["fontfamily"])
        assertEquals("center", style["textalign"])
    }

    @Test
    fun parseInlineStyle_noStyle() {
        val style = EpubReader.parseInlineStyle("<p>Hello</p>")
        assertTrue(style.isEmpty())
    }

    @Test
    fun parseInlineStyle_empty() {
        val style = EpubReader.parseInlineStyle("<p style=\"\">")
        assertTrue(style.isEmpty())
    }

    @Test
    fun fontFamilyToStyle_mapping() {
        assertEquals(EpubReader.RichStyle.H1, EpubReader.fontFamilyToStyle("Arial"))
        assertEquals(EpubReader.RichStyle.BODY, EpubReader.fontFamilyToStyle("Times New Roman"))
        assertEquals(EpubReader.RichStyle.BODY, EpubReader.fontFamilyToStyle("Courier New"))
        assertEquals(EpubReader.RichStyle.H1, EpubReader.fontFamilyToStyle("Heading Font"))
        assertEquals(EpubReader.RichStyle.BODY, EpubReader.fontFamilyToStyle(null))
    }

    @Test
    fun marginToLines_conversion() {
        assertEquals(0, EpubReader.marginToLines(null))
        assertEquals(0, EpubReader.marginToLines("0px"))
        assertEquals(5, EpubReader.marginToLines("20px"))
        assertEquals(10, EpubReader.marginToLines("40px"))
        assertEquals(0, EpubReader.marginToLines("invalid"))
    }

    @Test
    fun richParse_cssStyle_populated() {
        val html = "<p style=\"color:red;font-family:Times New Roman;text-align:center;\">Hello <b>bold</b> world.</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        assertEquals(1, blocks.size)
        val para = blocks[0] as EpubReader.RichBlock.Para
        assertEquals("red", para.cssStyle.color)
        assertEquals("Times New Roman", para.cssStyle.fontFamily)
        assertEquals("center", para.cssStyle.textAlign)
    }

    @Test
    fun richParse_cssStyle_defaults() {
        val html = "<p>Plain text</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        assertEquals(1, blocks.size)
        val para = blocks[0] as EpubReader.RichBlock.Para
        assertNull(para.cssStyle.color)
        assertNull(para.cssStyle.fontFamily)
        assertNull(para.cssStyle.textAlign)
    }

    @Test
    fun richParse_headingsWithCss() {
        val html = "<h2 style=\"color:blue;\">TITLE</h2>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        assertEquals(1, blocks.size)
        val h = blocks[0] as EpubReader.RichBlock.Para
        assertEquals(EpubReader.RichStyle.H2, h.style)
        assertEquals("blue", h.cssStyle.color)
    }

    // ---- E2.5 Week 2: Table parsing tests ----

    @Test
    fun richParse_table_basic() {
        val html = """
            <table>
                <tr><th>Header 1</th><th>Header 2</th></tr>
                <tr><td>Cell 1</td><td>Cell 2</td></tr>
                <tr><td>Cell 3</td><td>Cell 4</td></tr>
            </table>
        """.trimIndent()
        
        val blocks = EpubReader.richParse(html, "OEBPS/")
        val tables = blocks.filterIsInstance<EpubReader.RichBlock.Table>()
        
        assertEquals(1, tables.size)
        val table = tables.first()
        assertEquals(3, table.rows.size)
        assertEquals(2, table.rows.first().cells.size)
        assertTrue(table.rows.first().cells.first().isHeader)
    }

    @Test
    fun richParse_table_with_caption() {
        val html = """
            <table>
                <caption>Bảng dữ liệu</caption>
                <tr><td>A</td><td>B</td></tr>
            </table>
        """.trimIndent()
        
        val blocks = EpubReader.richParse(html, "OEBPS/")
        val tables = blocks.filterIsInstance<EpubReader.RichBlock.Table>()
        
        assertEquals(1, tables.size)
        assertEquals("Bảng dữ liệu", tables.first().caption)
    }

    @Test
    fun richParse_table_with_colspan() {
        val html = """
            <table>
                <tr><td colspan="2">Merged Cell</td></tr>
                <tr><td>Cell 1</td><td>Cell 2</td></tr>
            </table>
        """.trimIndent()
        
        val blocks = EpubReader.richParse(html, "OEBPS/")
        val tables = blocks.filterIsInstance<EpubReader.RichBlock.Table>()
        
        assertEquals(1, tables.size)
        val table = tables.first()
        assertEquals(2, table.rows.first().cells.size)
        assertEquals(2, table.rows.first().cells.first().colspan)
    }

    @Test
    fun richParse_mixed_table_and_text() {
        val html = "<p>Before table.</p><table><tr><td>A</td><td>B</td></tr></table><p>After table.</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        
        val paragraphs = blocks.filterIsInstance<EpubReader.RichBlock.Para>()
        val tables = blocks.filterIsInstance<EpubReader.RichBlock.Table>()
        
        assertEquals(2, paragraphs.size)
        assertEquals("Before table.", paragraphs.first().text)
        assertEquals("After table.", paragraphs.last().text)
        assertEquals(1, tables.size)
    }

    // ---- E2.5 Week 3: Footnote & Dropcap tests ----

    @Test
    fun richParse_footnote_basic() {
        val html = "<p>Text with footnote<fn id='1'>This is footnote text</fn> here.</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        val footnotes = blocks.filterIsInstance<EpubReader.RichBlock.Footnote>()
        
        assertEquals(1, footnotes.size)
        assertEquals("1", footnotes.first().id)
        assertEquals("This is footnote text", footnotes.first().text)
    }

    @Test
    fun richParse_superscript() {
        val html = "<p>x<sup>2</sup> = 4</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        assertEquals(1, blocks.size)
        val para = blocks[0] as EpubReader.RichBlock.Para
        assertTrue(para.text.contains("ˢ2"))
    }

    @Test
    fun richParse_subscript() {
        val html = "<p>H<sub>2</sub>O</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        assertEquals(1, blocks.size)
        val para = blocks[0] as EpubReader.RichBlock.Para
        assertTrue(para.text.contains("ₛ2"))
    }

    @Test
    fun richParse_dropcap_span() {
        val html = "<p><span class=\"dropcap\">L</span>orem ipsum dolor</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        val dropcaps = blocks.filterIsInstance<EpubReader.RichBlock.Dropcap>()
        
        assertEquals(1, dropcaps.size)
        assertEquals("L", dropcaps.first().text)
    }

    @Test
    fun richParse_mixed_footnote_and_dropcap() {
        val html = "<p><span class=\"dropcap\">L</span>orem ipsum<fn id=\"fn1\">Note text</fn> dolor.</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        val dropcaps = blocks.filterIsInstance<EpubReader.RichBlock.Dropcap>()
        val footnotes = blocks.filterIsInstance<EpubReader.RichBlock.Footnote>()
        
        assertEquals(1, dropcaps.size)
        assertEquals("L", dropcaps.first().text)
        assertEquals(1, footnotes.size)
        assertEquals("fn1", footnotes.first().id)
        assertEquals("Note text", footnotes.first().text)
    }

    // ---- E2.5 Week 5: Pagination integration tests ----

    @Test
    fun blockLineCount_para() {
        val para = EpubReader.RichBlock.Para(
            lines = listOf(
                EpubReader.RichInline("Hello world", false, false),
                EpubReader.RichInline(" test", false, false)
            ),
            style = EpubReader.RichStyle.BODY,
            cssStyle = EpubReader.RichBlock.RichStyleCSS()
        )
        // Should return at least 1 line for any content
        val lines = blockLineCount(para, 1200, 18f)
        assertTrue(lines >= 1)
    }

    @Test
    fun blockLineCount_figure() {
        val figure = EpubReader.RichBlock.Figure("path/to/img.jpg", "Alt text")
        val lines = blockLineCount(figure, 1200, 18f)
        assertEquals(5, lines)
    }

    @Test
    fun blockLineCount_table() {
        val table = EpubReader.RichBlock.Table(
            rows = listOf(
                EpubReader.RichBlock.TableRow(cells = listOf(EpubReader.RichBlock.TableCell("A", isHeader = true))),
                EpubReader.RichBlock.TableRow(cells = listOf(EpubReader.RichBlock.TableCell("B")))
            ),
            caption = "Test table"
        )
        val lines = blockLineCount(table, 1200, 18f)
        assertEquals(4, lines) // 2 rows + 2
    }

    @Test
    fun blockLineCount_rule() {
        val rule = EpubReader.RichBlock.Rule
        val lines = blockLineCount(rule, 1200, 18f)
        assertEquals(1, lines)
    }

    @Test
    fun blockLineCount_footnote() {
        val footnote = EpubReader.RichBlock.Footnote("1", "Footnote text")
        val lines = blockLineCount(footnote, 1200, 18f)
        assertEquals(1, lines)
    }

    @Test
    fun blockLineCount_dropcap() {
        val dropcap = EpubReader.RichBlock.Dropcap("L")
        val lines = blockLineCount(dropcap, 1200, 18f)
        assertEquals(1, lines)
    }

    @Test
    fun richParse_large_content_creates_multiple_blocks() {
        // Create content with many paragraphs to test pagination logic
        val html = (1..20).joinToString("") { i -> "<p>Paragraph $i with some text content.</p>" }
        val blocks = EpubReader.richParse(html, "OEBPS/")
        
        val paragraphs = blocks.filterIsInstance<EpubReader.RichBlock.Para>()
        assertTrue(paragraphs.size >= 10) // Should have multiple paragraphs
        assertTrue(paragraphs.all { it.text.isNotEmpty() })
    }

    @Test
    fun richParse_footnote_in_para() {
        val html = "<p>Text before<fn id='fn2'>Footnote content</fn>text after.</p>"
        val blocks = EpubReader.richParse(html, "OEBPS/")
        val footnotes = blocks.filterIsInstance<EpubReader.RichBlock.Footnote>()
        
        assertEquals(1, footnotes.size)
        assertEquals("fn2", footnotes.first().id)
        assertEquals("Footnote content", footnotes.first().text)
    }

    @Test
    fun richParse_css_and_table_combined() {
        val html = """
            <p style="color:red;text-align:center;">Styled paragraph</p>
            <table>
                <tr><th>Col 1</th><th>Col 2</th></tr>
                <tr><td>Data 1</td><td>Data 2</td></tr>
            </table>
        """.trimIndent()
        
        val blocks = EpubReader.richParse(html, "OEBPS/")
        val paragraphs = blocks.filterIsInstance<EpubReader.RichBlock.Para>()
        val tables = blocks.filterIsInstance<EpubReader.RichBlock.Table>()
        
        assertEquals(1, paragraphs.size)
        assertEquals("red", paragraphs.first().cssStyle.color)
        assertEquals("center", paragraphs.first().cssStyle.textAlign)
        assertEquals(1, tables.size)
    }
}
