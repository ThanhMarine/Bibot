package com.bibot.app.data.library

import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.db.BookProgress
import com.bibot.app.data.db.LibraryMeta
import org.junit.Assert.*
import org.junit.Test

/**
 * Module quản lý thư viện (tách từ DocBrowserScreen): lọc/nhóm/tìm pure,
 * Shell manager dùng chung. Pure-JVM (không Context).
 */
class LibraryQueryTest {

    private fun book(
        id: String,
        title: String = "T",
        lang: String = "en",
        source: String = "pdrive",
        mimeType: String = "application/pdf",
        collection: String = ""
    ) = BookMeta(id, title, lang, source, mimeType, collection = collection)

    @Test
    fun formatOf_extAndMime() {
        assertEquals("PDF", LibraryQuery.libFormatOf(book("a", "x.pdf", mimeType = "x")))
        assertEquals("EPUB", LibraryQuery.libFormatOf(book("a", "x.epub", mimeType = "x")))
        assertEquals("MD", LibraryQuery.libFormatOf(book("a", "x.md", mimeType = "x")))
        assertEquals("TXT", LibraryQuery.libFormatOf(book("a", "x.txt", mimeType = "x")))
        assertEquals("MOBI", LibraryQuery.libFormatOf(book("a", "x.mobi", mimeType = "x")))
        assertEquals("PDF", LibraryQuery.libFormatOf(book("a", "x", mimeType = "application/pdf")))
        assertEquals("OTHER", LibraryQuery.libFormatOf(book("a", "x.bin", mimeType = "application/octet-stream")))
    }

    @Test
    fun folderKeyOf_sources() {
        assertEquals("pdrive", LibraryQuery.folderKeyOf("pdrive"))
        assertEquals("pdrive", LibraryQuery.folderKeyOf("drive"))
        assertEquals("local", LibraryQuery.folderKeyOf("local"))
        assertEquals("local", LibraryQuery.folderKeyOf("demo"))
        assertEquals("url", LibraryQuery.folderKeyOf("url"))
        assertEquals("other", LibraryQuery.folderKeyOf("xyz"))
    }

    @Test
    fun matchGroup_collection() {
        val g = { k: String -> LibGroup(LibGroupKind.COLLECTION, k, "") }
        assertTrue(LibraryQuery.matchGroup(book("a", collection = "story"), null, null, g("story"), "?"))
        assertTrue(LibraryQuery.matchGroup(book("a", collection = "cotich"), null, null, g("cotich"), "?"))
        assertTrue(LibraryQuery.matchGroup(book("a", "t", "en", collection = "en"), null, null, g("en"), "?"))
        assertTrue(LibraryQuery.matchGroup(book("a", "t", "en"), null, null, g("en"), "?"))
        assertTrue(LibraryQuery.matchGroup(book("a", "t", "vi"), null, null, g("local"), "?"))
        assertFalse(LibraryQuery.matchGroup(book("a", "t", "en"), null, null, g("local"), "?"))
    }

    @Test
    fun matchGroup_authorSeriesFormatFolder() {
        val u = "Unknown"
        assertTrue(
            LibraryQuery.matchGroup(
                book("a"), "Nguyen Du", null,
                LibGroup(LibGroupKind.AUTHOR, "Nguyen Du", ""), u
            )
        )
        assertTrue(
            LibraryQuery.matchGroup(
                book("a"), null, null, LibGroup(LibGroupKind.AUTHOR, u, ""), u
            )
        )
        assertTrue(
            LibraryQuery.matchGroup(
                book("a", "x.pdf"), null, null, LibGroup(LibGroupKind.FORMAT, "PDF", ""), u
            )
        )
        assertFalse(
            LibraryQuery.matchGroup(
                book("a", "x.pdf"), null, null, LibGroup(LibGroupKind.FORMAT, "EPUB", ""), u
            )
        )
        assertTrue(
            LibraryQuery.matchGroup(
                book("a", "t", "vi", "local", "x"), null, null,
                LibGroup(LibGroupKind.FOLDER, "local", ""), u
            )
        )
    }

    @Test
    fun matchQuery_fields() {
        val b = book("a", "Linh Nam Chich Quai", "vi", "local", "text/markdown")
        assertTrue(LibraryQuery.matchQuery(b, "Ly Te Xuyen", "linh nam"))
        assertTrue(LibraryQuery.matchQuery(b, "Ly Te Xuyen", "te xuyen"))
        assertTrue(LibraryQuery.matchQuery(b, null, "md"))
        assertTrue(LibraryQuery.matchQuery(b, null, ""))
        assertFalse(LibraryQuery.matchQuery(b, null, "grimm"))
    }

    @Test
    fun selectBooks_filters() {
        val a = book("a", "A", "en", "pdrive", "application/pdf")
        val b = book("b", "B", "vi", "local", "text/markdown")
        val books = listOf(a, b)
        val metas = mapOf(
            "a" to LibraryMeta("a", shelfFav = true),
            "b" to LibraryMeta("b", shelfToRead = true, trashed = true)
        )
        val prog = mapOf("a" to BookProgress("a", "p", 10, 100))
        // Trash ẩn mọi nơi trừ Thùng rác.
        assertEquals(listOf(a), LibraryQuery.selectBooks(books, metas, prog, LibSel.F(LibFilter.ALL), ""))
        assertEquals(listOf(b), LibraryQuery.selectBooks(books, metas, prog, LibSel.F(LibFilter.TRASH), ""))
        assertEquals(listOf(a), LibraryQuery.selectBooks(books, metas, prog, LibSel.F(LibFilter.FAV), ""))
        assertEquals(
            emptyList<BookMeta>(),
            LibraryQuery.selectBooks(books, metas, prog, LibSel.F(LibFilter.TOREAD), "")
        )
        // Đang đọc: position>0 và chưa xong, mới nhất trước.
        assertEquals(listOf(a), LibraryQuery.selectBooks(books, metas, prog, LibSel.F(LibFilter.READING), ""))
        // Đã đọc: cờ tay hoặc position>=total.
        assertEquals(
            emptyList<BookMeta>(),
            LibraryQuery.selectBooks(books, metas, prog, LibSel.F(LibFilter.READ), "")
        )
        val done = mapOf("a" to BookProgress("a", "p", 100, 100))
        assertEquals(listOf(a), LibraryQuery.selectBooks(books, metas, done, LibSel.F(LibFilter.READ), ""))
        // Query lọc tiếp.
        assertEquals(
            listOf(a),
            LibraryQuery.selectBooks(books, metas, prog, LibSel.F(LibFilter.ALL), "A")
        )
    }

    @Test
    fun selectBooks_group() {
        val a = book("a", "A", "en", "pdrive", "application/pdf")
        val b = book("b", "B", "vi", "local", "text/markdown")
        val books = listOf(a, b)
        val got = LibraryQuery.selectBooks(
            books, emptyMap(), emptyMap(),
            LibSel.G(LibGroup(LibGroupKind.FORMAT, "PDF", "")), "", "?"
        )
        assertEquals(listOf(a), got)
    }
}
