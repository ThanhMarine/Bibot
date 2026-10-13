package com.bibot.app.data.library

import com.bibot.app.data.books.BookMeta
import com.bibot.app.data.db.BookProgress
import com.bibot.app.data.db.LibraryMeta

/**
 * Mô hình + lọc thư viện (tách từ DocBrowserScreen — tab Documents đã gỡ khỏi
 * nav, giữ lại làm module quản lý dùng chung cho Shell manager sau này).
 * Pure Kotlin (không Context/Compose) → unit-test được.
 */
enum class LibFilter { READING, ALL, FAV, TOREAD, READ, DOWNLOADED, TRASH }

enum class LibGroupKind { COLLECTION, AUTHOR, SERIES, FORMAT, FOLDER }

data class LibGroup(val kind: LibGroupKind, val key: String, val label: String)

sealed interface LibSel {
    data class F(val f: LibFilter) : LibSel
    data class G(val g: LibGroup) : LibSel
}

object LibraryQuery {

    fun libFormatOf(book: BookMeta): String {
        val ext = book.localFile?.extension?.lowercase()
            ?: book.title.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "PDF"
            "epub" -> "EPUB"
            "md", "markdown" -> "MD"
            "txt", "text" -> "TXT"
            "mobi", "azw", "azw3" -> "MOBI"
            else -> when {
                book.mimeType.contains("pdf") -> "PDF"
                book.mimeType.contains("epub") -> "EPUB"
                book.mimeType.contains("markdown") -> "MD"
                book.mimeType.contains("plain") -> "TXT"
                book.mimeType.contains("mobipocket") -> "MOBI"
                else -> "OTHER"
            }
        }
    }

    /** Nhóm folder theo source (dùng chung matchGroup FOLDER + drawer đếm). */
    fun folderKeyOf(source: String): String = when (source) {
        "pdrive", "drive" -> "pdrive"
        "local", "demo" -> "local"
        "url" -> "url"
        else -> "other"
    }

    fun isTrash(metas: Map<String, LibraryMeta>, b: BookMeta): Boolean =
        metas[b.id]?.trashed == true

    fun matchGroup(
        b: BookMeta,
        metaAuthor: String?,
        metaSeries: String?,
        g: LibGroup,
        unknownLabel: String
    ): Boolean {
        return when (g.kind) {
            LibGroupKind.COLLECTION -> when (g.key) {
                "story" -> b.collection == "story"
                "cotich" -> b.collection == "cotich"
                "en" -> b.collection == "en" || (b.collection.isBlank() && b.lang == "en")
                "thamkhao" -> b.collection == "thamkhao"
                else -> b.collection.isBlank() && b.lang != "en" // local
            }
            LibGroupKind.AUTHOR -> (metaAuthor?.ifBlank { null } ?: unknownLabel) == g.key
            LibGroupKind.SERIES -> (metaSeries?.ifBlank { null } ?: unknownLabel) == g.key
            LibGroupKind.FORMAT -> libFormatOf(b) == g.key
            LibGroupKind.FOLDER -> when (g.key) {
                "pdrive" -> b.source == "pdrive" || b.source == "drive"
                "local" -> b.source == "local" || b.source == "demo"
                "url" -> b.source == "url"
                else -> folderKeyOf(b.source) == "other"
            }
        }
    }

    fun matchQuery(b: BookMeta, metaAuthor: String?, q: String): Boolean {
        if (q.isBlank()) return true
        return b.title.contains(q, ignoreCase = true) ||
            (metaAuthor ?: "").contains(q, ignoreCase = true) ||
            libFormatOf(b).contains(q, ignoreCase = true) ||
            b.source.contains(q, ignoreCase = true)
    }

    /**
     * Lọc kệ theo drawer + search (trash ẩn mọi nơi trừ Thùng rác).
     * Y nguyên semantics DocBrowserScreen cũ.
     */
    fun selectBooks(
        books: List<BookMeta>,
        metas: Map<String, LibraryMeta>,
        progress: Map<String, BookProgress>,
        sel: LibSel,
        query: String,
        unknownLabel: String = ""
    ): List<BookMeta> {
        val nonTrash = books.filter { !isTrash(metas, it) }
        val trashBooks = books.filter { isTrash(metas, it) }
        val base: List<BookMeta> = when (sel) {
            is LibSel.F -> when (sel.f) {
                LibFilter.READING -> nonTrash.mapNotNull { b ->
                    progress[b.id]?.takeIf { it.position > 0 && (it.total <= 0 || it.position < it.total) }
                        ?.let { b to it.updatedAt }
                }.sortedByDescending { it.second }.map { it.first }
                LibFilter.ALL -> nonTrash
                LibFilter.FAV -> nonTrash.filter { metas[it.id]?.shelfFav == true }
                LibFilter.TOREAD -> nonTrash.filter { metas[it.id]?.shelfToRead == true }
                LibFilter.READ -> nonTrash.filter { b ->
                    metas[b.id]?.shelfRead == true ||
                        progress[b.id]?.let { it.total > 0 && it.position >= it.total } == true
                }
                LibFilter.DOWNLOADED -> nonTrash.filter { it.isDownloaded }
                LibFilter.TRASH -> trashBooks
            }
            is LibSel.G -> nonTrash.filter {
                matchGroup(it, metas[it.id]?.author, metas[it.id]?.series, sel.g, unknownLabel)
            }
        }
        return if (query.isBlank()) base
        else base.filter { matchQuery(it, metas[it.id]?.author, query) }
    }
}
