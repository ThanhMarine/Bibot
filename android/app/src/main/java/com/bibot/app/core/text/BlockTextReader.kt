package com.bibot.app.core.text

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.zip.Inflater

/**
 * 2.4 Lazy Block Loading — nạp + làm sạch text theo khối (block/section) thay vì readText() toàn file.
 *
 * Mục tiêu chống đơ/lag khi mở sách nặng (3-5 MB) trên máy 1-2 GB:
 *  - Không bao giờ `readText()` toàn file khi đọc MD/TXT (tránh 2-3 lần copy + GC spike).
 *  - PDF ≤12MB nạp 1 lần để inflate FlateDecode (có trần + fallback streaming nếu quá cỡ).
 *  - `cleanForTts` chạy trên khối 16 KB thay vì chuỗi 3 MB (≈25 regex × 3 MB → 25 × 16 KB).
 *  - Tách chương ngay lúc stream, TRƯỚC khi `stripMarkdown` xóa dấu "#" (bản cũ parse sau khi
 *    clean nên heading biến mất → luôn rơi về 1 chương → tách toàn bộ sách 1 lần).
 *  - Giữ `carry` (CARRY_CHARS / PDF_CARRY_BYTES) ở cuối khối để pattern vượt khối không bị chia đôi.
 *
 * Peak RAM ≈ nội dung đã làm sạch (1× file) + 1 khối, thay vì 3× như cách cũ.
 * Port Pi book_reader.py:60 iter_book_sentences + text_cleaner.py preprocess.
 */
object BlockTextReader {

    /** Một chương đã làm sạch. */
    data class ChapterBlock(val title: String, val content: String)

    /**
     * E2.1 — text của MỘT content stream PDF kèm chỉ số trang (0-based).
     * `page = -1` khi không dựng được page-tree (stream-only fallback) →
     * caller (PdfPageMap/anchor) rơi về map tỉ lệ như cũ.
     */
    data class PdfPageText(val page: Int, val text: String)

    /**
     * E2.3 — 1 mục TOC từ `/Outlines` (bookmark) của PDF: tiêu đề + trang đích
     * (0-based; -1 = không map được trang — dest theo tên / thiếu trang).
     */
    data class PdfOutline(val title: String, val page: Int)

    /** Cách nhận diện heading. */
    enum class Heading { NONE, MARKDOWN, MARKDOWN_OR_STORY, PARAGRAPH }

    // Khối tích lũy thô trước khi clean 1 lần
    private const val BLOCK_CHARS = 16 * 1024
    // Số ký tự giữ lại cuối khối (ranh giới cắt luôn ở whitespace gần nhất ≤ limit)
    private const val CARRY_CHARS = 256
    // PDF: đọc binary theo block + overlap để `(text) Tj` không bị chia đôi (tầng fallback)
    private const val PDF_BLOCK_BYTES = 64 * 1024
    private const val PDF_CARRY_BYTES = 4 * 1024
    // PDF nén FlateDecode: chỉ nạp nguyên file khi ≤ ngưỡng này (truyện thường vài MB)
    private const val PDF_MAX_INFLATE_BYTES = 12 * 1024 * 1024
    // Trần stream object > ngưỡng (thường là ảnh/font nhúng, không phải text)
    private const val PDF_MAX_STREAM_BYTES = 2 * 1024 * 1024
    // Trần output inflate mỗi stream (chống zip-bomb trên máy yếu)
    private const val PDF_MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    // Chặn file có hàng nghìn heading → ScrollableTabRow treo máy
    private const val MAX_CHAPTERS = 500
    // Trần buffer phần mở đầu (tựa/mục lục/số trang...): quá là sách không có mốc
    // → xả cũ nhất ra đọc như cũ, không nuốt sách
    private const val MAX_FRONT_CHARS = 64 * 1024
    // P6: dòng dài hơn 120 ký tự là câu văn, không phải heading
    private const val STORY_HEADING_MAX_CHARS = 120

/** Cache block text đã clean tránh readText() toàn file lại.
     * Giới hạn kích thước 64MB, key là SHA-1 nội dung block.
     * Lưu block thường dùng, tránh readText() toàn file khi quay lại.
     */
    private val cache: MutableMap<String, String> = mutableMapOf()

    /** Giải phóng cache (gọi khi thoát file/trở về library). */
    fun free() {
        cache.clear()
    }

    // Đúng thứ tự parse bản cũ: chỉ `##` (không phải `#`/`###`)
    private val mdHeading = Regex("""^##\s+(.+?)\s*#*$""")
    // Port P6 pdf_to_md.py HEADING_PATTERN
    private val storyHeading = Regex(
        """^(Chương|Chuong|Phần|Phan|Hồi|Hoi|Quyển|Quyen|Tập|Tap|Mục|Muc|Truyện|Truyen|Chuyện|Chuyen|Sự tích|Su tich)\b\s*.{0,100}$""",
        RegexOption.IGNORE_CASE
    )
    private val storyHeadingEndPunct = ".!?…:;"
    private val romanOrNum = Regex("""^(thứ\s+\S+|\d+|[IVXLCDM]+\b)""", RegexOption.IGNORE_CASE)
    private val pdfTextOp = Regex("""\(([^)]+)\) Tj""")
    // Toán tử text PDF đầy đủ: (literal)Tj, [(kerned)]TJ, <hex>Tj
    private val pdfTjParen = Regex("""\(((?:\\.|[^\\()])*)\)\s*Tj""")
    private val pdfTjArray = Regex("""\[(.*?)\]\s*TJ""", RegexOption.DOT_MATCHES_ALL)
    private val pdfTjHex = Regex("""<([0-9A-Fa-f\s]+)>\s*Tj""")
    private val pdfParenInner = Regex("""\(((?:\\.|[^\\()])*)\)""")
    private val pdfHexInner = Regex("""<([0-9A-Fa-f\s]+)>""")
    // ---- Font PDF: nhận diện để giải mã đúng ----
    // ToUnicode (Type0/Identity-H, vd Việt Nam Sử Lược) → map CID→Unicode.
    // TCVN3 (font ABC đời cũ .VnTime/.VnArial…): byte đơn + H-font HOA.
    // Type3 bitmap + không ToUnicode (vd grimm10 Gutenberg scan) → SCANNED.
    private enum class PdfFontKind { SIMPLE, TCVN3_LOWER, TCVN3_UPPER, TO_UNICODE }
    private data class PdfFont(val kind: PdfFontKind, val tou: Map<Int, String>? = null) {
        companion object { val SIMPLE = PdfFont(PdfFontKind.SIMPLE) }
    }
    // Ref trong dict resource font: `/Font << /F0 727 0 R /F1 733 0 R >>`
    // (chỉ quét TRONG dict /Font — quét cả file dính `/FontDescriptor`,
    // `/FontFile2` và rác binary trong stream font nhúng).
    private val fontResDict = Regex("""/Font\s*<<(.*?)>>""", RegexOption.DOT_MATCHES_ALL)
    private val fontResRef = Regex("""/([A-Za-z0-9*']+)\s+(\d+)\s+0\s+R""")
    private val fontObjStart = Regex("""(\d+)\s+0\s+obj\b""")
    // E2.1: nhận diện obj trang/page-tree + content stream để gán text theo TRANG.
    private val pdfObjHeadRe = Regex("""(\d+)\s+\d+\s+obj\b""")
    private val pdfPageTypeRe = Regex("""/Type\s*/Page(?![A-Za-z])""")
    private val pdfPagesTypeRe = Regex("""/Type\s*/Pages\b""")
    private val pdfCatalogRe = Regex("""/Type\s*/Catalog\b""")
    private val pdfPagesRefRe = Regex("""/Pages\s+(\d+)\s+0\s+R""")
    private val pdfKidsRe = Regex("""/Kids\s*\[([^\]]*)\]""")
    private val pdfContentsRefRe = Regex("""/Contents\s+(\d+)\s+0\s+R""")
    private val pdfContentsArrRe = Regex("""/Contents\s*\[([^\]]*)\]""")
    private val pdfRefIn = Regex("""(\d+)\s+0\s+R""")
    // E2.3: outline (bookmark) → TOC từ /Outlines: DFS /First → cây con → /Next.
    private val pdfOutlineTypeRe = Regex("""/Type\s*/Outlines\b""")
    private val pdfOutlineFirstRe = Regex("""/First\s+(\d+)\s+\d+\s+R""")
    private val pdfOutlineNextRe = Regex("""/Next\s+(\d+)\s+\d+\s+R""")
    private val pdfOutlineDestRe = Regex("""(?:/Dest|/D)\s*\[\s*(\d+)\s+\d+\s+R""")
    private val pdfTitleLitRe = Regex("""/Title\s*\(""")
    private val pdfTitleHexRe = Regex("""/Title\s*<([0-9A-Fa-f\s]+)>""")
    private val typeFontRe = Regex("""/Type\s*/Font\b""")
    private val baseFontRe = Regex("""/BaseFont\s*/([^\s/<>\[\]()]+)""")
    private val touRefRe = Regex("""/ToUnicode\s+(\d+)\s+0\s+R""")
    private val type3Re = Regex("""/Subtype\s*/Type3""")
    private val bfCharSec = Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL)
    private val bfRangeSec = Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL)
    private val bfCharEntry = Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""")
    private val bfRangeEntry =
        Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(?:<([0-9A-Fa-f]+)>|\[([^\]]*)\])""")
    // Marker chương do converter EPUB/PDF→MD sinh ra: `<!-- CHAPTER-BREAK index=6 title="X" pages=8-10 -->`
    private val chapterBreak = Regex(
        """(?i)^<!--\s*CHAPTER-BREAK\b.*?title\s*=\s*["']([^"']+)["'].*?-->\s*$"""
    )
    private val chapterBreakIndex = Regex(
        """(?i)^<!--\s*CHAPTER-BREAK\b.*?\bindex\s*=\s*(\d+).*?-->\s*$"""
    )

    /** Tiêu đề từ dòng `<!-- CHAPTER-BREAK ... -->`, không có → null (TextCleaner sẽ tẩy dòng này). */
    private fun chapterBreakTitle(line: String): String? {
        val t = line.trim()
        chapterBreak.find(t)?.let { m -> return m.groupValues[1].trim().ifBlank { null } }
        chapterBreakIndex.find(t)?.let { m -> return "Chương ${m.groupValues[1]}" }
        return null
    }

    // ---- Nguyên tắc đọc sách ----
    // Tựa/tác giả đọc từ metadata (UI/Detail), nội dung đọc từ Lời giới thiệu →
    // Chương 1 → hết sách. Bỏ: số trang/header/footer đứng riêng, mục lục dòng
    // ("Chương 1 .... 5"), phần mở đầu liệt kê số (buffer rồi bỏ khi gặp mốc).
    // Không thấy mốc → giữ hành vi cũ (đọc từ đầu), không nuốt sách.

    // Dòng số trang đứng riêng: "12", "- 12 -", "Trang 12", "[12]"
    private val pageNumberLineRegex = Regex(
        """^\s*(?:[-–—\[\({]*\s*)?(?:trang|page)\s+\d{1,4}\s*(?:[-–—\]\)}]*\s*)?$""",
        RegexOption.IGNORE_CASE
    )
    private val pureNumberLineRegex = Regex("""^\s*[-–—\[\({]*\s*\d{1,4}\s*[-–—\]\)}]*\s*$""")
    // Mục lục kiểu chấm lãnh đạo + số trang cuối dòng: "Chương 1 .... 5"
    private val tocLeaderRegex = Regex(""".{4,}?\.{3,}\s*\d{1,4}\s*$""")
    // Mốc bắt đầu đọc: lời giới thiệu/dẫn/tựa/mở đầu (kể cả "## "), hoặc
    // chương/phần/tập... số 1 ("Chương 1", "Phần I", "Truyện thứ nhất")
    private val introHeadingRegex = Regex(
        """(?i)^\s*(?:#+\s*)?(lời\s+(giới thiệu|nói đầu|dẫn|tựa|tác giả|mở đầu)|mở đầu|phần mở đầu|prologue|preface|foreword|introduction)\b"""
    )
    private val firstChapterRegex = Regex(
        """(?i)^\s*(?:#+\s*)?(chương|phần|tập|hồi|quyển|truyện|chuyện|mục|book|part|chapter)\s+(1|01|một|nhất|đầu|thứ nhất|first|one|i)\b"""
    )
    // Trang bìa/mục lục/bản quyền của EPUB không chữ (spine đã bỏ nav/linear=no).
    // H2.2: GIỮ code có lý do — điều kiện kép (content blank + title match) gắn với
    // cấu trúc spine, không biểu diễn được bằng rule dòng của rulepack (không có
    // rule nào khớp tiêu đề spine). Extract hàm pure để test được.
    private val epubSkippableTitleRegex =
        Regex("""(?i)^\s*(bìa|cover|mục lục|table of contents|contents|toc|copyright|bản quyền)\b""")

    /** Spine EPUB/MOBI không chữ mà tiêu đề là bìa/mục lục/bản quyền → bỏ, khỏi đọc trang trắng. */
    fun isSkippableEpubTitle(title: String): Boolean =
        epubSkippableTitleRegex.containsMatchIn(title.trim())

    private fun isPageNumberLine(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        if (pureNumberLineRegex.matches(trimmed)) return true
        return pageNumberLineRegex.matches(trimmed)
    }

    /**
     * Số trang/header/footer/mục-lục-dòng → bỏ ở ĐƯỜNG TTS (mọi mode).
     * Public để producer TTS lọc speak-time (display giữ nguyên bản).
     */
    fun isDroppableLine(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        // H2 ruler: thử rulepack trước (drop.page_number + drop.toc_leader);
        // chưa init → fallback code cứng (giữ nguyên hành vi).
        ReadingRules.isDropLineReady(trimmed)?.let { return it }
        if (isPageNumberLine(trimmed)) return true
        return tocLeaderRegex.containsMatchIn(trimmed)
    }

    /** True nếu dòng/tiêu đề là mốc bắt đầu đọc (lời giới thiệu hoặc chương 1). Public cho producer TTS. */
    fun isReadingStartMarker(text: String): Boolean {
        if (text.isBlank()) return false
        // H2 ruler: thử rulepack trước (start.intro + start.first_chapter).
        ReadingRules.isStartMarkerReady(text)?.let { return it }
        if (introHeadingRegex.containsMatchIn(text)) return true
        return firstChapterRegex.containsMatchIn(text)
    }

    /** Làm sạch 1 khối (16 KB) — chạy đủ pipeline 2.5 nhưng trên chuỗi nhỏ. */
    fun cleanBlock(s: String): String {
        if (s.isBlank()) return ""
        return try {
            TextCleaner.cleanForTts(PdfTextCleaner.cleanPdfText(dropPageNumberLines(s)))
        } catch (_: Exception) {
            s
        }
    }

    /**
     * Làm sạch 1 khối cho HIỂN THỊ nguyên bản: giữ chữ/số/dấu (—, …, TOC chấm,
     * số trang), chỉ bỏ tag/entities rác + TCVN3 + ký tự điều khiển.
     * Dùng cho `.prep`/display; TTS-smart chạy riêng ở speak-time.
     */
    fun cleanBlockDisplay(s: String): String {
        if (s.isBlank()) return ""
        return try {
            TextCleaner.cleanForDisplay(s)
        } catch (_: Exception) {
            s
        }
    }

    /**
     * Text để TTS đọc 1 câu display: bỏ các dòng rác (số trang đứng riêng,
     * mục-lục-dòng) còn sót trong câu; "" = lướt qua không đọc.
     * Chạy ở speak-time (producer) trên câu display-faithful.
     */
    fun ttsSpeakable(rawSentence: String): String {
        if (rawSentence.isBlank()) return ""
        if ('\n' !in rawSentence) {
            return if (isDroppableLine(rawSentence.trim())) "" else rawSentence
        }
        val kept = rawSentence.lineSequence()
            .filter { !isDroppableLine(it.trim()) }
            .joinToString("\n")
        return if (kept.isBlank()) "" else kept
    }

    /**
     * Bỏ dòng số trang/header/footer trong khối (PDF dồn nhiều trang 1 khối,
     * EPUB) TRƯỚC khi clean — nếu không TTS đọc "mười hai" giữa truyện.
     * Chỉ bỏ dòng số trang (không bỏ mục-lục-dòng ở đây: ngoài phần đầu,
     * dòng "... 100" có thể là nội dung) — nên CỐ Ý giữ code cứng, không dùng
     * ruler ở vòng này (rule drop.toc_leader chỉ dùng ở vòng dòng).
     */
    fun dropPageNumberLines(s: String): String {
        if (s.isEmpty()) return s
        var hasDigit = false
        for (c in s) {
            if (c.isDigit()) { hasDigit = true; break }
        }
        if (!hasDigit) return s
        var dropped = false
        val sb = StringBuilder(s.length)
        for (line in s.lineSequence()) {
            if (isPageNumberLine(line.trim())) { dropped = true; continue }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return if (dropped) sb.toString() else s
    }

    /**
     * Nạp sách thành chương theo khối.
     * - `.pdf` → inflate FlateDecode (file ≤12MB) rồi trích text, fallback quét raw → 1 chương.
     * - `.md`/`.txt` → đọc từng dòng, tích lũy 16 KB rồi clean; heading detect trên RAW.
     *
     * P1: `onChapter` (sink) — mỗi chương xong thì đẩy ra ngoài ngay; khi sink != null
     * KHÔNG tích lũy content trong list trả về (chỉ title) → build `.prep` không giữ cả sách RAM.
     */
    fun readChapters(
        file: File,
        defaultTitle: String = "Nội dung",
        heading: Heading = Heading.MARKDOWN,
        onChapter: ((String, String) -> Unit)? = null,
        /**
         * A — tách display/TTS: faithful=true giữ nguyên bản cho HIỂN THỊ
         * (không drop dòng số trang/TOC, không bỏ phần đầu trước mốc, không
         * rewrite smart). TTS-smart chạy riêng ở speak-time
         * ([ttsSpeakable] + `cleanForTts` trong producer). Titles giữ nguyên
         * shaping (cùng headings) nên resume/progress không vỡ.
         */
        faithful: Boolean = false
    ): List<ChapterBlock> {
        if (!file.exists()) return listOf(ChapterBlock(defaultTitle, ""))
        // EPUB: chương = spine item (đã đúng thứ tự sách), clean từng chương
        // qua pipeline 2.5 (kể cả TCVN3 text-level cho EPUB đời cũ).
        if (file.extension.equals("epub", true)) {
            val out = ArrayList<ChapterBlock>()
            for (ch in EpubReader.chapters(file, defaultTitle).take(MAX_CHAPTERS)) {
                val c = if (faithful) cleanBlockDisplay(ch.text) else cleanBlock(ch.text)
                // Trang bìa/mục lục/bản quyền không chữ → bỏ, khỏi đọc trang trắng
                if (c.isBlank() && isSkippableEpubTitle(ch.title)) continue
                val content = if (c.isNotBlank()) c else ""
                onChapter?.invoke(ch.title, content)
                out.add(ChapterBlock(ch.title, if (onChapter != null) "" else content))
            }
            if (out.isEmpty()) {
                onChapter?.invoke(defaultTitle, "")
                out.add(ChapterBlock(defaultTitle, ""))
            }
            return out
        }
        // A2.2 MOBI: chương = chunk PalmDoc đã strip tag (Mobi6 không có NCX như
        // EPUB), clean từng chương qua pipeline 2.5 rồi reuse luồng EPUB.
        if (MobiReader.isMobiExt(file.name)) {
            val out = ArrayList<ChapterBlock>()
            for (ch in MobiReader.chapters(file, defaultTitle).take(MAX_CHAPTERS)) {
                val c = if (faithful) cleanBlockDisplay(ch.text) else cleanBlock(ch.text)
                if (c.isBlank() && isSkippableEpubTitle(ch.title)) continue
                val content = if (c.isNotBlank()) c else ""
                onChapter?.invoke(ch.title, content)
                out.add(ChapterBlock(ch.title, if (onChapter != null) "" else content))
            }
            if (out.isEmpty()) {
                onChapter?.invoke(defaultTitle, "")
                out.add(ChapterBlock(defaultTitle, ""))
            }
            return out
        }
        if (file.extension.equals("pdf", true)) {
            val sb = StringBuilder()
            for (blk in iterPdfText(file)) {
                val c = if (faithful) cleanBlockDisplay(blk) else cleanBlock(blk)
                if (c.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(c)
                }
            }
            val text = sb.toString().trim()
            val title = file.nameWithoutExtension.ifBlank { defaultTitle }
            onChapter?.invoke(title, text)
            return listOf(ChapterBlock(title, if (onChapter != null) "" else text))
        }

        val mode = resolveHeading(file, heading)
        val out = ArrayList<ChapterBlock>()
        var title = defaultTitle
        var paraIdx = 0
        val content = StringBuilder()
        val raw = StringBuilder(BLOCK_CHARS + CARRY_CHARS + 64)

        fun flushFullBlocks() {
            while (raw.length >= BLOCK_CHARS) {
                val part = cutPart(raw) ?: break
                appendClean(part, content, faithful)
            }
        }

        fun closeChapter() {
            drain(raw, content, faithful)
            val c = content.toString().trim()
            if (c.isNotBlank() || out.isEmpty()) {
                onChapter?.invoke(title, c)
                out.add(ChapterBlock(title, if (onChapter != null) "" else c))
            }
            content.setLength(0)
            raw.setLength(0)
        }

        // Nguyên tắc đọc sách: phần mở đầu (tựa/mục lục/số trang/liệt kê số)
        // buffer lại, gặp mốc (Lời giới thiệu/Chương 1) thì bỏ hết, đọc từ đó.
        // PARAGRAPH (không heading chuẩn) thì không áp — chỉ bỏ dòng số trang.
        // A: faithful (display nguyên bản) bỏ qua toàn bộ cơ chế này — TTS lọc
        // riêng ở speak-time, display giữ từ đầu sách.
        val useFrontMatter = mode != Heading.PARAGRAPH && !faithful
        var readingStarted = false
        val pendingFront = ArrayDeque<String>()
        var pendingChars = 0

        fun bufferFrontLine(line: String) {
            pendingFront.addLast(line)
            pendingChars += line.length + 1
            // Phần đầu quá lớn = sách không có mốc → xả cũ nhất ra đọc như cũ
            while (pendingChars > MAX_FRONT_CHARS && pendingFront.isNotEmpty()) {
                val old = pendingFront.removeFirst()
                pendingChars -= old.length + 1
                raw.append(old).append('\n')
            }
            flushFullBlocks()
        }

        fun discardFront() {
            pendingFront.clear()
            pendingChars = 0
            raw.setLength(0)
            content.setLength(0)
        }

        try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 8 * 1024).use { r ->
                // lookahead 1 dòng — P6 cần dòng kế tiếp để chặn heading bịa ở dòng wrap dở câu
                var cur: String? = r.readLine()
                var nxt: String? = r.readLine()
                while (cur != null) {
                    val trimmed = cur.trim()
                    val h = if (mode == Heading.PARAGRAPH) null else detectHeading(cur, nxt, mode)
                    when {
                        mode == Heading.PARAGRAPH -> {
                            // Không có ## cũng không có "Chương..." → tách theo đoạn trống ("Đoạn N")
                            if (trimmed.isEmpty() && out.size < MAX_CHAPTERS) {
                                drain(raw, content, faithful)
                                if (content.isNotBlank()) {
                                    title = "Đoạn ${++paraIdx}"
                                    val c = content.toString().trim()
                                    onChapter?.invoke(title, c)
                                    out.add(ChapterBlock(title, if (onChapter != null) "" else c))
                                }
                                content.setLength(0)
                                raw.setLength(0)
                            } else if (trimmed.isNotEmpty()) {
                                // PARAGRAPH: chỉ bỏ dòng số trang, còn lại đọc từ đầu như cũ
                                // (faithful giữ hết cho display).
                                if (faithful || !isDroppableLine(trimmed)) {
                                    raw.append(cur).append('\n')
                                    flushFullBlocks()
                                }
                            }
                        }
                        // Quá MAX_CHAPTERS → gộp phần còn lại vào chương trước (tránh TabRow treo máy)
                        h != null && out.size < MAX_CHAPTERS -> {
                            if (useFrontMatter && !readingStarted) {
                                if (isReadingStartMarker(h)) {
                                    // Gặp Lời giới thiệu/Chương 1 → bỏ hết phần đầu, đọc từ đây
                                    readingStarted = true
                                    discardFront()
                                    closeChapter()
                                    title = h
                                } else {
                                    // Heading phần đầu (Tác giả, NXB...) → buffer, chưa tách chương
                                    bufferFrontLine(cur)
                                }
                            } else {
                                closeChapter() // chốt chương CŨ trước khi đổi title
                                title = h
                            }
                        }
                        else -> {
                            // A: display-faithful giữ mọi dòng (kể cả TOC/số trang);
                            // TTS bỏ ở speak-time qua ttsSpeakable.
                            if (!faithful && isDroppableLine(trimmed)) {
                                // số trang/header/footer/mục-lục-dòng → bỏ mọi lúc
                            } else if (useFrontMatter && !readingStarted) {
                                if (trimmed.isEmpty()) {
                                    raw.append(cur).append('\n')
                                } else if (isReadingStartMarker(trimmed)) {
                                    // Lời giới thiệu/Chương 1 dạng dòng thường → đọc từ đây
                                    readingStarted = true
                                    discardFront()
                                    raw.append(cur).append('\n')
                                    flushFullBlocks()
                                } else {
                                    bufferFrontLine(cur)
                                }
                            } else {
                                raw.append(cur).append('\n')
                                flushFullBlocks()
                            }
                        }
                    }
                    cur = nxt
                    nxt = r.readLine()
                }
            }
        } catch (_: Exception) {
            // file hỏng/encoding lạ → trả phần đã nạp được
        }
        if (mode == Heading.PARAGRAPH) {
            drain(raw, content, faithful)
            if (content.isNotBlank()) {
                title = "Đoạn ${++paraIdx}"
                val c = content.toString().trim()
                onChapter?.invoke(title, c)
                out.add(ChapterBlock(title, if (onChapter != null) "" else c))
            }
        } else {
            if (useFrontMatter && !readingStarted) {
                // Không thấy mốc bắt đầu → giữ hành vi cũ: đọc từ đầu, không nuốt sách
                for (l in pendingFront) raw.append(l).append('\n')
                pendingFront.clear()
            }
            closeChapter()
        }

        // Sink mode: content đã đẩy ra ngoài hết, out chỉ còn title → không filter (filter sẽ xóa sạch)
        val nonBlank = if (onChapter == null) out.filter { it.content.isNotBlank() } else out
        return nonBlank.ifEmpty { listOf(ChapterBlock(defaultTitle, "")) }
    }

    /**
     * A — đọc nguyên bản cho HIỂN THỊ + `.prep` (display-faithful).
     * Cùng shaping titles với [readChapters], content giữ nguyên
     * (dấu —, TOC chấm, số trang). TTS-smart chạy riêng ở speak-time.
     */
    fun readOriginalChapters(
        file: File,
        defaultTitle: String = "Nội dung",
        heading: Heading = Heading.MARKDOWN,
        onChapter: ((String, String) -> Unit)? = null
    ): List<ChapterBlock> = readChapters(file, defaultTitle, heading, onChapter, faithful = true)

    /**
     * Chọn mode trước khi tách — đúng thứ tự parse của bản cũ (BookScreen/StoryScreen):
     * file có `##` → chỉ tách theo `##`; không có `##` → fallback "Chương/Phần/Hồi";
     * không cả hai → tách đoạn trống. Stream 1 lượt, KHÔNG readText() (chỉ dừng sớm khi gặp `##`).
     */
    private fun resolveHeading(file: File, mode: Heading): Heading {
        if (mode != Heading.MARKDOWN_OR_STORY) return mode
        var hasStoryHeading = false
        try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 8 * 1024).use { r ->
                var cur: String? = r.readLine()
                var nxt: String? = r.readLine()
                while (cur != null) {
                    val t = cur.trim()
                    if (t.isNotEmpty()) {
                        // `##` hoặc marker CHAPTER-BREAK xuất hiện ở đâu trong file → thắng hết
                        if (chapterBreakTitle(t) != null || mdHeading.matches(t)) return Heading.MARKDOWN
                        if (!hasStoryHeading && detectStoryHeading(t, nxt) != null) hasStoryHeading = true
                    }
                    cur = nxt
                    nxt = r.readLine()
                }
            }
        } catch (_: Exception) { /* không xác định được → mặc định PARAGRAPH */ }
        return if (hasStoryHeading) Heading.MARKDOWN_OR_STORY else Heading.PARAGRAPH
    }

    /** Stream khối text đã clean cho file md/txt — không giữ cả file trong RAM. */
    fun iterCleanedBlocks(file: File): Sequence<String> = sequence {
        if (!file.exists()) return@sequence
        val raw = StringBuilder(BLOCK_CHARS + CARRY_CHARS + 64)
        val buf = CharArray(8 * 1024)
        try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 8 * 1024).use { r ->
                while (true) {
                    val n = r.read(buf)
                    if (n < 0) break
                    raw.append(buf, 0, n)
                    while (raw.length >= BLOCK_CHARS) {
                        val part = cutPart(raw) ?: break
                        val c = cleanBlock(part)
                        if (c.isNotBlank()) yield(c)
                    }
                }
            }
        } catch (_: Exception) { /* yield phần đã có */ }
        if (raw.isNotEmpty()) {
            val c = cleanBlock(raw.toString())
            if (c.isNotBlank()) yield(c)
        }
    }

    /**
     * Stream PDF → text, 2 tầng (pure JDK, không thêm dependency):
     * 1) File ≤ 12MB: quét `stream…endstream`, inflate FlateDecode từng object rồi trích
     *    `(…)Tj`, `[…]TJ`, `<hex>Tj` **bám font hiện tại** (`/Fx Tf`):
     *    ToUnicode (Type0/Identity-H) → CID→Unicode; TCVN3 (font ABC) → bảng
     *    TCVN3 (H-font ra HOA); còn lại giữ hành vi Latin-1 cũ.
     *    PDF hiện đại hầu hết nén content stream nên tầng này mới đọc được PDF thật.
     * 2) Fallback: quét raw `(text) Tj` theo block 64KB + overlap (PDF không nén / file
     *    quá lớn / inflate thất bại).
     * PDF scan/ảnh (font Type3 bitmap, không ToUnicode — vd grimm10 Gutenberg):
     * byte trích ra chỉ là index glyph → chặn từ đây, trả rỗng để UI báo rõ
     * (xem [isScannedPdf]) thay vì hiển thị chuỗi chữ cái vô nghĩa.
     * Quy tắc dedup tầng 2: chấp nhận match bắt đầu TRƯỚC vùng carry; match nằm trong carry được
     * nhận lại ở vòng đọc sau (overlap 4 KB) → không trùng, không mất.
     */
    fun iterPdfText(file: File): Sequence<String> = iterPdfTextPages(file).map { it.text }

    /**
     * E2.1 — như [iterPdfText] nhưng gắn chỉ số TRANG (0-based; -1 = không biết).
     * Trang lấy từ page-tree (Catalog → Pages → Kids); stream không có obj/pages-tree
     * (file >12MB, inflate fail) → page=-1 để caller fallback tỉ lệ.
     */
    fun iterPdfTextPages(file: File): Sequence<PdfPageText> = sequence {
        if (!file.exists() || file.length() == 0L) return@sequence
        if (isScannedPdf(file)) return@sequence
        if (file.length() <= PDF_MAX_INFLATE_BYTES) {
            try {
                val raw = String(file.readBytes(), Charsets.ISO_8859_1)
                val fonts = parsePdfFonts(raw)
                val pageOf = buildContentPageMap(raw)
                if (iterPdfInflated(raw, fonts, pageOf)) return@sequence
            } catch (_: Exception) { /* rơi xuống raw-scan */ }
        }
        for (blk in iterPdfTextRaw(file)) yield(PdfPageText(-1, blk))
    }

    /** Loại PDF text: TEXT (trích được) hay SCANNED (font Type3 bitmap, không ToUnicode). */
    enum class PdfKind { TEXT, SCANNED }

    // Quét marker font tối đa 8MB đầu (đủ cho font dict; file scan lớn không cần đọc hết).
    private const val PDF_KIND_SCAN_BYTES = 8 * 1024 * 1024

    fun pdfKind(file: File): PdfKind {
        if (!file.exists() || file.length() == 0L) return PdfKind.TEXT
        var hasType3 = false
        var hasTou = false
        try {
            BufferedInputStream(FileInputStream(file), 65536).use { input ->
                val buf = ByteArray(65536)
                var carry = ByteArray(0)
                var scanned: Long = 0
                while (scanned < PDF_KIND_SCAN_BYTES) {
                    val r = input.read(buf)
                    if (r < 0) break
                    scanned += r
                    val combined = carry + buf.copyOf(r)
                    val s = String(combined, Charsets.ISO_8859_1)
                    if (!hasType3 && type3Re.containsMatchIn(s)) hasType3 = true
                    if (!hasTou && s.contains("ToUnicode")) hasTou = true
                    if (hasType3 && hasTou) break
                    carry = combined.takeLast(32).toByteArray()
                }
            }
        } catch (_: Exception) {
            return PdfKind.TEXT
        }
        return if (hasType3 && !hasTou) PdfKind.SCANNED else PdfKind.TEXT
    }

    /** True nếu PDF là bản scan/ảnh (Type3 bitmap, không text) — UI báo rõ thay vì đọc rác. */
    fun isScannedPdf(file: File): Boolean =
        try {
            pdfKind(file) == PdfKind.SCANNED
        } catch (_: Exception) {
            false
        }

    /** Tầng 1: inflate FlateDecode từng stream object. Trả true nếu trích được text. */
    private suspend fun SequenceScope<PdfPageText>.iterPdfInflated(
        raw: String,
        fonts: Map<String, PdfFont>,
        pageOf: Map<Int, Int>
    ): Boolean {
        // E2.1: obj head positions để gán stream → objNum → chỉ số trang (binary search).
        val objHeads = ArrayList<Int>()
        val objNumList = ArrayList<Int>()
        for (m in pdfObjHeadRe.findAll(raw)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            objNumList.add(n)
            objHeads.add(m.range.first)
        }
        fun objNumAt(pos: Int): Int {
            var lo = 0
            var hi = objHeads.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (objHeads[mid] <= pos) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return if (ans >= 0) objNumList[ans] else -1
        }
        var pos = 0
        var found = false
        while (true) {
            val si = raw.indexOf("stream", pos)
            if (si < 0) break
            // Bỏ match nằm trong "endstream"
            if (si >= 3 && raw.regionMatches(si - 3, "end", 0, 3)) { pos = si + 6; continue }
            // Theo spec "stream" phải kết thúc dòng — lọc match trong binary
            var ds = si + 6
            if (ds < raw.length && raw[ds] == '\r') ds++
            if (ds >= raw.length || raw[ds] != '\n') { pos = si + 6; continue }
            ds++
            val ei = raw.indexOf("endstream", ds)
            if (ei < 0) break
            val segBytes = raw.substring(ds, ei).toByteArray(Charsets.ISO_8859_1)
            val sb = StringBuilder()
            val inflated = inflatePdfStream(segBytes)
            if (inflated != null) {
                extractPdfTextOpsTracked(String(inflated, Charsets.ISO_8859_1), fonts, sb)
            } else {
                extractPdfTextOpsTracked(raw.substring(ds, ei), fonts, sb) // stream không nén
            }
            if (sb.isNotBlank()) {
                yield(PdfPageText(pageOf[objNumAt(si)] ?: -1, sb.toString()))
                found = true
            }
            pos = ei + 9
        }
        return found
    }

    /**
     * E2.1 — map content-obj-number → chỉ số trang (0-based) đọc từ page-tree:
     * Catalog → /Pages → /Kids (đệ quy, chống cycle); fallback thứ tự obj number
     * khi không có catalog. Trang không có /Contents vẫn chiếm chỉ số (không
     * sinh map entry). Không dựng được → map rỗng → mọi stream page=-1.
     */
    internal fun buildContentPageMap(raw: String): Map<Int, Int> {
        val objNums = ArrayList<Int>(256)
        val objPos = ArrayList<Int>(256)
        for (m in pdfObjHeadRe.findAll(raw)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            objNums.add(n)
            objPos.add(m.range.first)
        }
        if (objNums.isEmpty()) return emptyMap()
        fun dictWindow(i: Int): String {
            val start = objPos[i]
            val end = if (i + 1 < objPos.size) objPos[i + 1] else raw.length
            return raw.substring(start, minOf(end, start + 8192))
        }
        fun refsIn(s: String): List<Int> =
            pdfRefIn.findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()

        val pageContents = HashMap<Int, List<Int>>() // page obj → content refs (rỗng = trang ảnh)
        val pagesKids = HashMap<Int, List<Int>>()    // pages obj → kids refs
        var catalogPages = -1
        for (i in objNums.indices) {
            val w = dictWindow(i)
            when {
                catalogPages < 0 && pdfCatalogRe.containsMatchIn(w) -> {
                    catalogPages = pdfPagesRefRe.find(w)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                }
                pdfPageTypeRe.containsMatchIn(w) -> {
                    val refs = pdfContentsArrRe.find(w)?.groupValues?.get(1)?.let(::refsIn)
                        ?: pdfContentsRefRe.find(w)?.groupValues?.get(1)?.toIntOrNull()?.let { listOf(it) }
                        ?: emptyList()
                    pageContents[objNums[i]] = refs
                }
                pdfPagesTypeRe.containsMatchIn(w) -> {
                    val kids = pdfKidsRe.find(w)?.groupValues?.get(1)?.let(::refsIn) ?: emptyList()
                    if (kids.isNotEmpty()) pagesKids[objNums[i]] = kids
                }
            }
        }
        if (pageContents.isEmpty()) return emptyMap()
        val ordered = ArrayList<Int>(pageContents.size)
        val visited = HashSet<Int>()
        fun walk(n: Int, depth: Int) {
            if (depth > 32 || !visited.add(n)) return
            if (pageContents.containsKey(n)) { ordered.add(n); return }
            val kids = pagesKids[n] ?: return
            for (k in kids) walk(k, depth + 1)
        }
        if (catalogPages >= 0) walk(catalogPages, 0)
        if (ordered.isEmpty()) {
            // Không có page-tree (hoặc cycle) → obj number thường = thứ tự trang.
            ordered.addAll(pageContents.keys.sorted())
        }
        val map = HashMap<Int, Int>(ordered.size * 2)
        for ((idx, pageObj) in ordered.withIndex()) {
            for (c in pageContents[pageObj].orEmpty()) map[c] = idx
        }
        return map
    }

    // ------------------------------------------------------------------ outline / TOC (E2.3)

    private const val MAX_OUTLINE_ENTRIES = 2000

    /**
     * Đọc bookmark `/Outlines` của PDF → TOC (tiêu đề + trang đích).
     * DFS-preorder đúng thứ tự hiển thị: `/First` (con đầu) trước, rồi `/Next` (kế).
     * File >12MB → rỗng (giới hạn nạp raw một lần — cùng ngưỡng inflate;
     * E2.6 sẽ ghi nhận). Lỗi parse → rỗng, không crash.
     */
    fun parsePdfOutline(file: File): List<PdfOutline> =
        try {
            if (!file.exists() || file.length() > PDF_MAX_INFLATE_BYTES) emptyList()
            else parsePdfOutlineRaw(String(file.readBytes(), Charsets.ISO_8859_1))
        } catch (_: Exception) {
            emptyList()
        }

    internal fun parsePdfOutlineRaw(raw: String): List<PdfOutline> {
        val objNums = ArrayList<Int>(256)
        val objPos = ArrayList<Int>(256)
        for (m in pdfObjHeadRe.findAll(raw)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            objNums.add(n)
            objPos.add(m.range.first)
        }
        if (objNums.isEmpty()) return emptyList()
        val bodies = HashMap<Int, String>(objNums.size * 2)
        for (i in objNums.indices) {
            val start = objPos[i]
            val end = if (i + 1 < objPos.size) objPos[i + 1] else raw.length
            bodies[objNums[i]] = raw.substring(start, minOf(end, start + 16384))
        }
        // Trang đích của dest trỏ tới PAGE obj → chỉ số theo page-tree (fallback obj order).
        val pageIdx = HashMap<Int, Int>(64)
        for ((idx, n) in outlinePageOrder(bodies, objNums).withIndex()) pageIdx[n] = idx
        val root = bodies.values.firstOrNull { pdfOutlineTypeRe.containsMatchIn(it) }
            ?: return emptyList()
        val first = pdfOutlineFirstRe.find(root)?.groupValues?.get(1)?.toIntOrNull()
            ?: return emptyList()
        val out = ArrayList<PdfOutline>()
        val seen = HashSet<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(first)
        while (stack.isNotEmpty() && out.size < MAX_OUTLINE_ENTRIES) {
            val n = stack.removeLast()
            if (!seen.add(n)) continue
            val w = bodies[n] ?: continue
            val title = pdfTitleOf(w)?.trim()
            if (!title.isNullOrEmpty()) {
                val dest = pdfOutlineDestRe.find(w)?.groupValues?.get(1)?.toIntOrNull()
                out.add(PdfOutline(title, dest?.let { pageIdx[it] } ?: -1))
            }
            // Đảo thứ tự khi push: LIFO → con (/First) chạy trước kế (/Next) = preorder.
            pdfOutlineNextRe.find(w)?.groupValues?.get(1)?.toIntOrNull()?.let { stack.addLast(it) }
            pdfOutlineFirstRe.find(w)?.groupValues?.get(1)?.toIntOrNull()?.let { stack.addLast(it) }
        }
        return out
    }

    /** Thứ tự chỉ số trang (0-based) cho map dest → page: Catalog → Pages → Kids, fallback obj order. */
    private fun outlinePageOrder(bodies: Map<Int, String>, objNums: List<Int>): List<Int> {
        val pageObjs = HashSet<Int>(64)
        val pagesKids = HashMap<Int, List<Int>>(16)
        for (n in objNums) {
            val w = bodies[n] ?: continue
            when {
                pdfPageTypeRe.containsMatchIn(w) -> pageObjs.add(n)
                pdfPagesTypeRe.containsMatchIn(w) -> {
                    pdfKidsRe.find(w)?.groupValues?.get(1)?.let { s ->
                        val refs = pdfRefIn.findAll(s).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                        if (refs.isNotEmpty()) pagesKids[n] = refs
                    }
                }
            }
        }
        if (pageObjs.isEmpty()) return emptyList()
        var catalogPages = -1
        for (n in objNums) {
            val w = bodies[n] ?: continue
            if (pdfCatalogRe.containsMatchIn(w)) {
                catalogPages = pdfPagesRefRe.find(w)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                break
            }
        }
        val ordered = ArrayList<Int>(pageObjs.size)
        val visited = HashSet<Int>()
        fun walk(n: Int, depth: Int) {
            if (depth > 32 || !visited.add(n)) return
            if (pageObjs.contains(n)) { ordered.add(n); return }
            pagesKids[n]?.forEach { walk(it, depth + 1) }
        }
        if (catalogPages >= 0) walk(catalogPages, 0)
        if (ordered.isEmpty()) ordered.addAll(objNums.filter { pageObjs.contains(it) }.sorted())
        return ordered
    }

    /** `/Title (…)` (escape + ngoặc lồng) hoặc `/Title <hex>` (UTF-16BE có BOM). */
    private fun pdfTitleOf(w: String): String? {
        pdfTitleHexRe.find(w)?.let { m ->
            val hex = m.groupValues[1].replace(Regex("""\s"""), "")
            if (hex.length >= 4 && hex.length % 2 == 0) {
                val bytes = ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                return when {
                    b0 == 0xFE && b1 == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                    b0 == 0xFF && b1 == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                    else -> String(bytes, Charsets.ISO_8859_1)
                }
            }
        }
        pdfTitleLitRe.find(w)?.let { m ->
            val open = m.range.last // index của '('
            val end = matchPdfString(w, open) ?: return null
            return unescapePdfString(w.substring(open + 1, end))
        }
        return null
    }

    /** Vị trí ')' đóng chuỗi PDF bắt đầu tại `open` == '(' — tôn trọng escape + đếm ngoặc lồng. */
    private fun matchPdfString(s: String, open: Int): Int? {
        var i = open + 1
        var depth = 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '(' -> { depth++; i++ }
                ')' -> { depth--; if (depth == 0) return i; i++ }
                else -> i++
            }
        }
        return null
    }

    private fun unescapePdfString(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append(0x0C.toChar()); i += 2 }
                '(' , ')' , '\\' -> { sb.append(n); i += 2 }
                in '0'..'7' -> {
                    var j = i + 1
                    var v = 0
                    var cnt = 0
                    while (j < s.length && cnt < 3 && s[j] in '0'..'7') {
                        v = v * 8 + (s[j] - '0')
                        j++
                        cnt++
                    }
                    sb.append(v.toChar())
                    i = j
                }
                '\n', '\r' -> i += 2 // nối dòng (line continuation)
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ font PDF

    private data class PdfFontInfo(val base: String, val touObj: Int)

    private fun tcvnFont(base: String): PdfFont = when (Tcvn3.fontKind(base)) {
        2 -> PdfFont(PdfFontKind.TCVN3_UPPER)
        1 -> PdfFont(PdfFontKind.TCVN3_LOWER)
        else -> PdfFont.SIMPLE
    }

    /**
     * Dựng map tên resource font (/F0, /F1…) → cách giải mã:
     * TO_UNICODE (có CMap) > TCVN3 (tên font ABC, H→HOA) > SIMPLE (Latin-1 cũ).
     * Trùng tên giữa các trang → giữ loại tốt nhất.
     */
    private fun parsePdfFonts(raw: String): Map<String, PdfFont> {
        val resObjs = HashMap<String, MutableList<Int>>()
        for (d in fontResDict.findAll(raw)) {
            for (m in fontResRef.findAll(d.groupValues[1])) {
                val obj = m.groupValues[2].toIntOrNull() ?: continue
                resObjs.getOrPut(m.groupValues[1]) { mutableListOf() }.add(obj)
            }
        }
        if (resObjs.isEmpty()) return emptyMap()
        val infos = HashMap<Int, PdfFontInfo>()
        var pos = 0
        while (pos < raw.length) {
            val om = fontObjStart.find(raw, pos) ?: break
            val num = om.groupValues[1].toIntOrNull()
            if (num == null) {
                pos = om.range.last + 1
                continue
            }
            // 'endobj' là biên object; dict font nhỏ (CharProcs chỉ là ref) nên trần 8KB
            val eo = raw.indexOf("endobj", om.range.last + 1)
            val end = if (eo < 0) raw.length else minOf(eo, om.range.first + 8192)
            val seg = raw.substring(om.range.first, end)
            if (typeFontRe.containsMatchIn(seg)) {
                val base = baseFontRe.find(seg)?.groupValues?.get(1) ?: ""
                val tou = touRefRe.find(seg)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                infos[num] = PdfFontInfo(base, tou)
            }
            pos = om.range.last + 1
        }
        val touCache = HashMap<Int, Map<Int, String>?>()
        fun touOf(n: Int): Map<Int, String>? {
            if (touCache.containsKey(n)) return touCache[n]
            var parsed: Map<Int, String>? = null
            try {
                val bytes = inflateObjStream(raw, n)
                if (bytes != null) {
                    val m = parseToUnicode(String(bytes, Charsets.ISO_8859_1))
                    if (m.isNotEmpty()) parsed = m
                }
            } catch (_: Exception) {
            }
            touCache[n] = parsed
            return parsed
        }
        fun rank(f: PdfFont): Int = when (f.kind) {
            PdfFontKind.TO_UNICODE -> 3
            PdfFontKind.TCVN3_LOWER, PdfFontKind.TCVN3_UPPER -> 2
            PdfFontKind.SIMPLE -> 1
        }
        val out = HashMap<String, PdfFont>()
        for ((res, objs) in resObjs) {
            var best: PdfFont? = null
            for (o in objs) {
                val info = infos[o] ?: continue
                val cand = if (info.touObj >= 0) {
                    val m = touOf(info.touObj)
                    if (m != null) PdfFont(PdfFontKind.TO_UNICODE, m) else tcvnFont(info.base)
                } else {
                    tcvnFont(info.base)
                }
                if (best == null || rank(cand) > rank(best)) best = cand
            }
            if (best != null) out[res] = best
        }
        return out
    }

    /** Inflate stream của object N (dùng cho ToUnicode CMap). */
    private fun inflateObjStream(raw: String, objNum: Int): ByteArray? {
        val head = Regex("""\b$objNum\s+0\s+obj\b""").find(raw) ?: return null
        val si = raw.indexOf("stream", head.range.last + 1)
        if (si < 0) return null
        var ds = si + 6
        if (ds < raw.length && raw[ds] == '\r') ds++
        if (ds >= raw.length || raw[ds] != '\n') return null
        ds++
        val ei = raw.indexOf("endstream", ds)
        if (ei < 0) return null
        return inflatePdfStream(raw.substring(ds, ei).toByteArray(Charsets.ISO_8859_1))
    }

    private fun hexToBytesOrNull(hex: String): ByteArray? {
        val h = hex.filter { !it.isWhitespace() }
        if (h.isEmpty()) return ByteArray(0)
        val padded = if (h.length % 2 == 1) h + "0" else h
        return try {
            ByteArray(padded.length / 2) { i ->
                padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToIntOrNull(hex: String): Int? {
        val h = hex.filter { !it.isWhitespace() }
        if (h.isEmpty() || h.length > 8) return null
        return try {
            h.toLong(16).toInt()
        } catch (_: Exception) {
            null
        }
    }

    /** Chuỗi đích CMap `<0041…>` → text (UTF-16BE, lẻ byte → Latin-1). */
    private fun hexToUnicodeStr(hex: String): String {
        val bytes = hexToBytesOrNull(hex) ?: return ""
        if (bytes.isEmpty()) return ""
        return if (bytes.size >= 2 && bytes.size % 2 == 0) {
            try {
                String(bytes, Charsets.UTF_16BE)
            } catch (_: Exception) {
                String(bytes, Charsets.ISO_8859_1)
            }
        } else {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    /** Parse ToUnicode CMap: beginbfchar + beginbfrange (cả dạng array). */
    private fun parseToUnicode(s: String): Map<Int, String> {
        val map = HashMap<Int, String>()
        for (sec in bfCharSec.findAll(s)) {
            for (m in bfCharEntry.findAll(sec.groupValues[1])) {
                val src = hexToIntOrNull(m.groupValues[1]) ?: continue
                val dst = hexToUnicodeStr(m.groupValues[2])
                if (dst.isNotEmpty()) map[src] = dst
            }
        }
        for (sec in bfRangeSec.findAll(s)) {
            for (m in bfRangeEntry.findAll(sec.groupValues[1])) {
                val lo = hexToIntOrNull(m.groupValues[1]) ?: continue
                val hi = hexToIntOrNull(m.groupValues[2]) ?: continue
                if (hi < lo || hi - lo > 4096) continue
                val single = m.groupValues[3]
                val arr = m.groupValues[4]
                if (arr.isNotEmpty()) {
                    val dsts = pdfHexInner.findAll(arr).map { hexToUnicodeStr(it.groupValues[1]) }.toList()
                    for ((k, d) in dsts.withIndex()) {
                        if (d.isNotEmpty()) map[lo + k] = d
                    }
                } else if (single.isNotEmpty()) {
                    val base = hexToUnicodeStr(single)
                    if (base.isEmpty()) continue
                    if (base.length == 1) {
                        val b0 = base[0].code
                        for (cid in lo..hi) map[cid] = (b0 + (cid - lo)).toChar().toString()
                    } else {
                        map[lo] = base
                    }
                }
            }
        }
        return map
    }

    /** Giải escape literal string PDF: \( \) \\ \n \r \t \b \f + octal \ddd. */
    private fun pdfUnescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                '(', ')', '\\' -> { sb.append(n); i += 2 }
                in '0'..'7' -> {
                    var j = i + 1; var v = 0; var k = 0
                    while (k < 3 && j < s.length && s[j] in '0'..'7') {
                        v = v * 8 + (s[j] - '0'); j++; k++
                    }
                    sb.append(v.toChar()); i = j
                }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    /** Giải hex string PDF <0041…> → text (1 byte/ký tự Latin-1). */
    private fun pdfHexToText(hex: String): String {
        val h = hex.filter { !it.isWhitespace() }
        if (h.isEmpty()) return ""
        val padded = if (h.length % 2 == 1) h + "0" else h
        val bytes = ByteArray(padded.length / 2)
        try {
            for (i in bytes.indices) {
                bytes[i] = padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) { return "" }
        return String(bytes, Charsets.ISO_8859_1)
    }

    /** Trích text từ 1 content stream: gom (… )Tj, […]TJ (nối liền, giữ từ kerned), <hex>Tj. */
    private fun extractPdfTextOps(s: String, out: StringBuilder) {
        for (m in pdfTjArray.findAll(s)) {
            val inner = StringBuilder()
            for (g in pdfParenInner.findAll(m.groupValues[1])) inner.append(pdfUnescape(g.groupValues[1]))
            for (g in pdfHexInner.findAll(m.groupValues[1])) inner.append(pdfHexToText(g.groupValues[1]))
            val t = inner.toString().trim()
            if (t.isNotEmpty()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(t)
            }
        }
        for (m in pdfTjParen.findAll(s)) {
            val t = pdfUnescape(m.groupValues[1]).trim()
            if (t.isNotEmpty()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(t)
            }
        }
        for (m in pdfTjHex.findAll(s)) {
            val t = pdfHexToText(m.groupValues[1]).trim()
            if (t.isNotEmpty()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(t)
            }
        }
    }

    /** Unescape literal PDF sang BYTES (giữ nguyên giá trị byte cho CID/TCVN3). */
    private fun pdfUnescapedBytes(s: String): ByteArray {
        val bos = ByteArrayOutputStream(s.length.coerceAtLeast(16))
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                bos.write(c.code and 0xFF)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                'n' -> { bos.write(10); i += 2 }
                'r' -> { bos.write(13); i += 2 }
                't' -> { bos.write(9); i += 2 }
                'b' -> { bos.write(8); i += 2 }
                'f' -> { bos.write(12); i += 2 }
                '(', ')', '\\' -> { bos.write(n.code and 0xFF); i += 2 }
                in '0'..'7' -> {
                    var j = i + 1
                    var v = 0
                    var k = 0
                    while (k < 3 && j < s.length && s[j] in '0'..'7') {
                        v = v * 8 + (s[j] - '0')
                        j++
                        k++
                    }
                    bos.write(v and 0xFF)
                    i = j
                }
                else -> { bos.write(n.code and 0xFF); i += 2 }
            }
        }
        return bos.toByteArray()
    }

    /** Hex string PDF → Latin-1 (dùng cho font SIMPLE và TCVN3 trước khi decode). */
    private fun hexToLatin1(hex: String): String {
        val bytes = hexToBytesOrNull(hex) ?: return ""
        return String(bytes, Charsets.ISO_8859_1)
    }

    /** Nhóm byte thành CID 2-byte (Identity-H) rồi tra ToUnicode. CID lạ → bỏ (không đọc rác). */
    private fun decodeCidBytes(bytes: ByteArray, map: Map<Int, String>): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size / 2 + 1)
        var i = 0
        while (i + 1 < bytes.size) {
            val cid = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            map[cid]?.let { sb.append(it) }
            i += 2
        }
        return sb.toString()
    }

    /** Giải mã 1 mảnh text theo font hiện tại (paren=true: literal `(…)`; false: hex `<…>`). */
    private fun decodePdfPart(content: String, paren: Boolean, font: PdfFont): String {
        return when (font.kind) {
            PdfFontKind.SIMPLE ->
                if (paren) pdfUnescape(content) else pdfHexToText(content)
            PdfFontKind.TCVN3_LOWER ->
                Tcvn3.decode(if (paren) pdfUnescape(content) else hexToLatin1(content), upper = false)
            PdfFontKind.TCVN3_UPPER ->
                Tcvn3.decode(if (paren) pdfUnescape(content) else hexToLatin1(content), upper = true)
            PdfFontKind.TO_UNICODE -> {
                val map = font.tou ?: return ""
                val bytes = if (paren) pdfUnescapedBytes(content) else hexToBytesOrNull(content) ?: return ""
                decodeCidBytes(bytes, map)
            }
        }
    }

    private fun normPart(decoded: String): String {
        if (decoded.isEmpty()) return ""
        // Op chỉ là glyph cách (vd <0003>) → giữ 1 space, không trim mất
        return if (decoded.isBlank()) " " else decoded.trim()
    }

    /**
     * Trích text từ 1 content stream, BÁM font (`/Fx Tf`) và vị trí:
     * - Td ngang nhỏ (kern từng glyph, vd Việt Nam Sử Lược) → nối liền, KHÔNG cách.
     * - Td dọc / Tm / T* / BT → xuống dòng trước text kế (gộp nhiều lần liền nhau).
     * - Không có toán tử vị trí giữa 2 op (PDF cũ) → cách như bản cũ.
     *
     * Scanner viết tay 1 pass O(n), KHÔNG regex trên cả stream (regex DOTALL trên
     * stream MBs gây StackOverflowError — đã gặp thật với Việt Nam Sử Lược).
     */
    private fun extractPdfTextOpsTracked(s: String, fonts: Map<String, PdfFont>, out: StringBuilder) {
        var font = PdfFont.SIMPLE
        var positioned = false
        var needNl = false
        fun push(t: String) {
            if (t.isEmpty()) return
            val blank = t.isBlank()
            if (needNl) {
                if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                needNl = false
                positioned = false
            } else if (!positioned && !blank) {
                if (out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') out.append(' ')
            } else {
                positioned = false
            }
            out.append(if (blank) " " else t)
        }
        // Cửa sổ số gần nhất (toán hạng cho Td/Tm); token khác reset để số cũ
        // (vd kern trong TJ) không dính vào toán tử sau.
        val nums = DoubleArray(6)
        var nCount = 0
        fun pushNum(v: Double) {
            if (nCount < 6) nums[nCount++] = v
            else {
                nums[0] = nums[1]; nums[1] = nums[2]; nums[2] = nums[3]
                nums[3] = nums[4]; nums[4] = nums[5]; nums[5] = v
            }
        }
        fun isWs(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000C' || c.code == 0
        fun isDelim(c: Char): Boolean =
            isWs(c) || c == '(' || c == ')' || c == '<' || c == '>' ||
                c == '[' || c == ']' || c == '/' || c == '%'
        // Đọc literal `(…)` từ pos (đang ở '('). Trả content + index sau ')', null nếu dở.
        fun readLiteral(pos: Int): Pair<String, Int>? {
            val sb = StringBuilder()
            var depth = 1
            var j = pos + 1
            while (j < s.length) {
                val d = s[j]
                if (d == '\\' && j + 1 < s.length) {
                    sb.append(d)
                    sb.append(s[j + 1])
                    j += 2
                    continue
                }
                if (d == '(') depth++
                else if (d == ')') {
                    depth--
                    if (depth == 0) return sb.toString() to (j + 1)
                }
                sb.append(d)
                j++
            }
            return null
        }
        // Đọc hex `<…>` từ pos (đang ở '<', KHÔNG phải '<<'). Null nếu dở.
        fun readHex(pos: Int): Pair<String, Int>? {
            val e = s.indexOf('>', pos + 1)
            if (e < 0) return null
            return s.substring(pos + 1, e) to (e + 1)
        }
        // Bỏ qua dict `<<…>>` lồng nhau (có string/hex/array bên trong).
        fun skipDict(pos: Int): Int {
            var j = pos + 2
            var depth = 1
            while (j < s.length && depth > 0) {
                if (s.startsWith("<<", j)) {
                    depth++
                    j += 2
                } else if (s.startsWith(">>", j)) {
                    depth--
                    j += 2
                } else if (s[j] == '(') {
                    val r = readLiteral(j) ?: return s.length
                    j = r.second
                } else if (s[j] == '<') {
                    val r = readHex(j) ?: return s.length
                    j = r.second
                } else if (s[j] == '[') {
                    val e = s.indexOf(']', j + 1)
                    if (e < 0) return s.length
                    j = e + 1
                } else {
                    j++
                }
            }
            return j
        }
        fun skipWs(pos: Int): Int {
            var j = pos
            while (j < s.length && isWs(s[j])) j++
            return j
        }
        // Đọc số từ pos. Trả (value, index sau số), null nếu không phải số.
        fun readNum(pos: Int): Pair<Double, Int>? {
            var j = pos
            if (j < s.length && (s[j] == '+' || s[j] == '-')) j++
            var dot = false
            var digits = 0
            val start = j
            while (j < s.length && (s[j].isDigit() || (!dot && s[j] == '.'))) {
                if (s[j] == '.') dot = true else digits++
                j++
            }
            if (digits == 0 || j == start) return null
            if (j < s.length && !isDelim(s[j])) return null
            return try {
                s.substring(pos, j).toDouble() to j
            } catch (_: Exception) {
                null
            }
        }
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                isWs(c) -> i++
                c == '%' -> {
                    // Comment PDF → bỏ tới cuối dòng (không reset số)
                    val e = s.indexOf('\n', i)
                    i = if (e < 0) n else e + 1
                }
                c == '(' -> {
                    val r = readLiteral(i)
                    if (r == null) break
                    var k = skipWs(r.second)
                    if (k + 1 < n && s[k] == 'T' && s[k + 1] == 'j' &&
                        (k + 2 >= n || (!s[k + 2].isLetterOrDigit() && s[k + 2] != '_'))
                    ) {
                        push(normPart(decodePdfPart(r.first, true, font)))
                        i = k + 2
                    } else {
                        i = r.second
                    }
                    nCount = 0
                }
                c == '<' -> {
                    if (s.startsWith("<<", i)) {
                        i = skipDict(i)
                    } else {
                        val r = readHex(i)
                        if (r == null) break
                        var k = skipWs(r.second)
                        if (k + 1 < n && s[k] == 'T' && s[k + 1] == 'j' &&
                            (k + 2 >= n || (!s[k + 2].isLetterOrDigit() && s[k + 2] != '_'))
                        ) {
                            push(normPart(decodePdfPart(r.first, false, font)))
                            i = k + 2
                        } else {
                            i = r.second
                        }
                    }
                    nCount = 0
                }
                c == '[' -> {
                    // Mảng TJ: giải mã từng phần tử THEO THỨ TỰ (số kern bỏ qua)
                    val inner = StringBuilder()
                    var j = i + 1
                    var closed = false
                    while (j < n) {
                        while (j < n && isWs(s[j])) j++
                        if (j >= n) break
                        val d = s[j]
                        if (d == ']') {
                            j++
                            closed = true
                            break
                        } else if (d == '(') {
                            val r = readLiteral(j) ?: break
                            inner.append(decodePdfPart(r.first, true, font))
                            j = r.second
                        } else if (d == '<') {
                            if (s.startsWith("<<", j)) break
                            val r = readHex(j) ?: break
                            inner.append(decodePdfPart(r.first, false, font))
                            j = r.second
                        } else {
                            val rn = readNum(j)
                            j = rn?.second ?: (j + 1)
                        }
                    }
                    if (!closed) break
                    var k = skipWs(j)
                    if (k + 1 < n && s[k] == 'T' && s[k + 1] == 'J' &&
                        (k + 2 >= n || (!s[k + 2].isLetterOrDigit() && s[k + 2] != '_'))
                    ) {
                        push(normPart(inner.toString()))
                        i = k + 2
                    } else {
                        i = j
                    }
                    nCount = 0
                }
                c == '/' -> {
                    var j = i + 1
                    while (j < n && !isDelim(s[j])) j++
                    val name = s.substring(i + 1, j)
                    var k = skipWs(j)
                    var consumedTf = false
                    val rn = readNum(k)
                        if (rn != null) {
                        val k2 = skipWs(rn.second)
                        if (k2 + 1 < n && s[k2] == 'T' && s[k2 + 1] == 'f' &&
                            (k2 + 2 >= n || (!s[k2 + 2].isLetterOrDigit() && s[k2 + 2] != '_'))
                        ) {
                            font = fonts[name] ?: PdfFont.SIMPLE
                            i = k2 + 2
                            consumedTf = true
                        }
                    }
                    if (!consumedTf) i = j
                    nCount = 0
                }
                c.isLetter() || c == '*' || c == '\'' || c == '"' -> {
                    var j = i
                    while (j < n && (s[j].isLetter() || s[j] == '*')) j++
                    // Toán tử ' và " đứng một mình (không gộp vào word vì quote không phải letter)
                    if (j == i) j = i + 1
                    val op = s.substring(i, j)
                    var nextI = j
                    when (op) {
                        "BT" -> needNl = true
                        "T*" -> needNl = true
                        "Td", "TD" -> {
                            if (nCount >= 2) {
                                val dy = nums[nCount - 1]
                                if (kotlin.math.abs(dy) > 0.5) needNl = true else positioned = true
                            }
                        }
                        "Tm" -> if (nCount >= 6) needNl = true
                        "BI" -> {
                            // Ảnh inline: bỏ tới EI đứng riêng (binary có thể chứa mọi byte)
                            var e = -1
                            var p = j
                            while (true) {
                                val f = s.indexOf("EI", p)
                                if (f < 0) break
                                val after = f + 2
                                val beforeOk = f == 0 || isWs(s[f - 1])
                                val afterOk = after >= n || isWs(s[after])
                                if (beforeOk && afterOk) {
                                    e = after
                                    break
                                }
                                p = f + 2
                            }
                            nextI = if (e < 0) n else e
                        }
                        // Còn lại (Tj/TJ lẻ, Do, Q/q, cm, ET, …): bỏ qua
                    }
                    i = nextI
                    nCount = 0
                }
                else -> {
                    val rn = readNum(i)
                    if (rn != null) {
                        pushNum(rn.first)
                        i = rn.second
                    } else {
                        // Ký tự lạ (kể cả quote đứng riêng): bỏ qua
                        i++
                        nCount = 0
                    }
                }
            }
        }
    }

    /** Thử giải nén 1 stream object (FlateDecode zlib, fallback raw-deflate). Quá ngưỡng → null. */
    private fun inflatePdfStream(data: ByteArray): ByteArray? {
        if (data.isEmpty() || data.size > PDF_MAX_STREAM_BYTES) return null
        for (nowrap in listOf(false, true)) {
            val inf = Inflater(nowrap)
            try {
                inf.setInput(data)
                val buf = ByteArray(32 * 1024)
                val bos = ByteArrayOutputStream(data.size.coerceAtMost(256 * 1024))
                while (!inf.finished()) {
                    if (inf.needsInput()) break
                    val n = inf.inflate(buf)
                    if (n <= 0) break
                    if (bos.size() + n > PDF_MAX_OUTPUT_BYTES) return null
                    bos.write(buf, 0, n)
                }
                if (bos.size() > 0) return bos.toByteArray()
            } catch (_: Exception) {
                // thử chế độ còn lại
            } finally {
                try { inf.end() } catch (_: Exception) {}
            }
        }
        return null
    }

    /** Tầng 2 (fallback): quét raw `(text) Tj` theo block — cho PDF không nén. */
    private fun iterPdfTextRaw(file: File): Sequence<String> = sequence {
        if (!file.exists() || file.length() == 0L) return@sequence
        val chunk = ByteArray(PDF_BLOCK_BYTES)
        val carryArr = ByteArray(PDF_CARRY_BYTES)
        var carryLen = 0
        try {
            BufferedInputStream(FileInputStream(file), PDF_BLOCK_BYTES).use { input ->
                var eof = false
                while (!eof) {
                    var got = 0
                    while (got < chunk.size) {
                        val r = input.read(chunk, got, chunk.size - got)
                        if (r < 0) { eof = true; break }
                        got += r
                    }
                    val combined = ByteArray(carryLen + got)
                    System.arraycopy(carryArr, 0, combined, 0, carryLen)
                    if (got > 0) System.arraycopy(chunk, 0, combined, carryLen, got)
                    if (combined.isEmpty()) break
                    // Vòng cuối (eof): nhận toàn bộ để không sót phần tail (limit = ∞)
                    val limit = if (eof) Int.MAX_VALUE
                    else (combined.size - PDF_CARRY_BYTES).coerceAtLeast(0)
                    val s = String(combined, Charsets.ISO_8859_1)
                    val sb = StringBuilder()
                    for (m in pdfTextOp.findAll(s)) {
                        if (m.range.first >= limit) break
                        val t = m.groupValues[1].trim()
                        if (t.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(t)
                        }
                    }
                    if (sb.isNotEmpty()) yield(sb.toString())
                    val keep = minOf(PDF_CARRY_BYTES, combined.size)
                    System.arraycopy(combined, combined.size - keep, carryArr, 0, keep)
                    carryLen = keep
                }
            }
        } catch (_: Exception) { /* yield phần đã trích được */ }
    }

    /** Trích text PDF (block-wise) cho converter. Trả null nếu không có text operator nào. */
    fun extractPdfText(file: File): String? {
        val sb = StringBuilder()
        for (blk in iterPdfText(file)) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(blk)
        }
        return if (sb.isBlank()) null else sb.toString()
    }

    /**
     * Đọc tối đa `maxChars` ký tự đầu file — thay cho `readText().take(n)` (bản cũ nạp cả file
     * chỉ để hiện 300 ký tự preview, chạy trong item LazyColumn → lag khi cuộn).
     */
    fun readHead(file: File, maxChars: Int = 2000): String {
        if (!file.exists() || maxChars <= 0) return ""
        return try {
            BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8), 8 * 1024).use { r ->
                readHead(r, maxChars)
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** Đọc tối đa `maxChars` từ Reader (stream mạng) — không đóng stream (caller lo). */
    fun readHead(reader: java.io.Reader, maxChars: Int): String {
        if (maxChars <= 0) return ""
        return try {
            val sb = StringBuilder(maxChars + 32)
            val buf = CharArray(4 * 1024)
            while (sb.length < maxChars) {
                val n = reader.read(buf, 0, minOf(buf.size, maxChars - sb.length))
                if (n < 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun detectHeading(line: String, nextLine: String?, mode: Heading): String? {
        if (mode == Heading.NONE) return null
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (mode == Heading.MARKDOWN || mode == Heading.MARKDOWN_OR_STORY) {
            // Ưu tiên marker converter (được sinh từ chính cấu trúc sách, chính xác nhất)
            chapterBreakTitle(trimmed)?.let { return it }
            mdHeading.matchEntire(trimmed)?.let { m ->
                return m.groupValues[1].trim().ifBlank { null }
            }
            if (mode == Heading.MARKDOWN) return null
        }
        if (mode == Heading.MARKDOWN_OR_STORY) return detectStoryHeading(trimmed, nextLine)
        return null
    }

    /**
     * Port P6 pdf_to_md.py:_is_heading_line — chặn câu văn thường bị tách nhầm thành chương:
     * "Phần lớn...", "Hồi đó...", "Mục đích...", dòng dài >120 ký tự, dòng kết bằng .!?:…
     */
    private fun detectStoryHeading(s: String, nextLine: String?): String? {
        if (s.isEmpty() || s.length > STORY_HEADING_MAX_CHARS) return null
        if (s.last() in storyHeadingEndPunct) return null
        if (s.equals("mục lục", true)) return null
        if (!s[0].isUpperCase() && !s[0].isDigit()) return null
        val m = storyHeading.matchEntire(s)
        if (m == null) {
            // D7/G3: đề mục dòng ngắn không keyword ("Lời giới thiệu", "Tiểu sử tác giả"):
            // ngắn, không kết dấu câu → xuống hàng → tách chương để đọc có ngắt câu.
            return if (isShortHeading(s, nextLine)) s else null
        }
        val kwRange = m.groups[1]?.range ?: return null
        val rest = s.substring(kwRange.last + 1).trimStart()
        // Dấu phẩy/chấm phẩy ngay sau từ loại → câu văn ("Truyện, nhân hỏi...")
        if (rest.startsWith(",") || rest.startsWith(";")) return null
        // "Hồi"/"Mục" trần không số/thứ → câu văn ("Hồi đó", "Mục đích...")
        val kw = m.groupValues[1]
        if (kw.equals("hồi", true) || kw.equals("mục", true)) {
            if (!romanOrNum.containsMatchIn(rest)) return null
        }
        // Dòng wrap dở câu: dòng kế tiếp viết thường nối tiếp → không phải heading
        val ns = nextLine?.trim() ?: ""
        if (ns.isNotEmpty() && ns[0].isLowerCase()) return null
        return s
    }

    // D7/G3: đề mục ngắn — chuỗi ngắn (≤60 ký tự, ≤10 từ), không dấu chấm cuối,
    // mở đầu hoa/số; dòng sau tồn tại + mở đầu hoa/số (kế thừa guard P6 chống wrap).
    // Loại số trang đứng riêng, "mục lục", dòng chấm lãnh đạo TOC.
    // Rủi ro còn lại (ghi nhận): thơ/câu đối thoại ngắn hoa-đầu có thể tách nhầm —
    // hậu quả nhẹ (1 mục TOC + ngắt 450ms), MAX_CHAPTERS chặn thiệt hại.
    private const val SHORT_HEADING_MAX_CHARS = 60
    private const val SHORT_HEADING_MAX_WORDS = 10
    private val shortHeadingEndPunct = ".!?…:;,)]}'\"”’"

    private fun isShortHeading(s: String, nextLine: String?): Boolean {
        if (s.isEmpty() || s.length > SHORT_HEADING_MAX_CHARS) return false
        if (s.last() in shortHeadingEndPunct) return false
        if (s.split(Regex("\\s+")).size > SHORT_HEADING_MAX_WORDS) return false
        if (!s[0].isUpperCase() && !s[0].isDigit()) return false
        if (s.equals("mục lục", true)) return false
        if (pureNumberLineRegex.containsMatchIn(s)) return false
        if (tocLeaderRegex.containsMatchIn(s)) return false
        val ns = nextLine?.trim() ?: ""
        if (ns.isEmpty()) return false
        if (!ns[0].isUpperCase() && !ns[0].isDigit()) return false
        return true
    }

    private fun appendClean(part: String, out: StringBuilder, faithful: Boolean = false) {
        val c = if (faithful) cleanBlockDisplay(part) else cleanBlock(part)
        if (c.isNotBlank()) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(c)
        }
    }

    private fun drain(raw: StringBuilder, out: StringBuilder, faithful: Boolean = false) {
        while (true) {
            val part = cutPart(raw) ?: break
            appendClean(part, out, faithful)
        }
        if (raw.isNotEmpty()) {
            appendClean(raw.toString(), out, faithful)
            raw.setLength(0)
        }
    }

    /**
     * Cắt bỏ phần đầu `raw` để clean, giữ lại tail ≥ CARRY_CHARS.
     * Ranh giới cắt luôn là whitespace ≤ (length - CARRY) để URL/số không bị chia đôi qua 2 khối.
     * Trả null nếu khối chưa đủ lớn (không cắt → không có vòng lặp vô hạn).
     */
    private fun cutPart(raw: StringBuilder): String? {
        if (raw.length < BLOCK_CHARS) return null
        val maxCut = raw.length - CARRY_CHARS
        if (maxCut <= 1) return null
        var cut = -1
        for (i in maxCut downTo 1) {
            if (raw[i].isWhitespace()) { cut = i + 1; break }
        }
        if (cut <= 0) cut = maxCut
        if (cut <= 0 || cut >= raw.length) return null
        val part = raw.substring(0, cut)
        raw.delete(0, cut)
        return part
    }
}
