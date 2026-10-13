package com.bibot.app.core.text

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * A2.2 MOBI — parser PalmDB/MOBI thật (không phải draft zip-sai-format cũ).
 * Dựng file MOBI tổng hợp trong test (PalmDoc raw + EXTH title/author),
 * verify: detect/meta/chapters/iterText/PalmDoc-decompress/convertToEpub→EpubReader.
 * Chạy: :app:testDemoDebugUnitTest. Pure-JVM (không cần Context).
 */
class MobiReaderTest {

    private fun buildMobi(
        text: String,
        title: String = "Sach Test",
        author: String = "Tac Gia",
        compression: Int = 1,
        encryption: Int = 0
    ): File {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        val authorBytes = author.toByteArray(Charsets.UTF_8)

        // EXTH: 2 records (503=title, 100=author)
        val exthBody = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { d ->
                d.writeInt(503); d.writeInt(8 + titleBytes.size); d.write(titleBytes)
                d.writeInt(100); d.writeInt(8 + authorBytes.size); d.write(authorBytes)
            }
        }.toByteArray()
        val exth = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { d ->
                d.write("EXTH".toByteArray(Charsets.US_ASCII))
                d.writeInt(8 + exthBody.size)
                d.writeInt(2)
                d.write(exthBody)
            }
        }.toByteArray()

        val rec0 = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { d ->
                // PalmDoc header 16B
                d.writeShort(compression)
                d.writeShort(0)
                d.writeInt(textBytes.size)
                d.writeShort(1) // 1 text record
                d.writeShort(4096)
                d.writeShort(encryption)
                d.writeShort(0)
                // MOBI header (đủ 232B)
                d.write("MOBI".toByteArray(Charsets.US_ASCII))
                d.writeInt(232)
                d.writeInt(2) // mobiType book
                d.writeInt(65001) // UTF-8
                // pad tới exthFlags ở +0x80 trong MOBI header (spec),
                // rồi pad tiếp cho đủ 232B.
                repeat(112) { d.writeByte(0) }
                d.writeInt(0x40) // EXTH present
                repeat(100) { d.writeByte(0) }
                d.write(exth)
            }
        }.toByteArray()

        val rec0Off = 78 + 2 * 8
        val rec1Off = rec0Off + rec0.size
        val f = File.createTempFile("test", ".mobi")
        DataOutputStream(f.outputStream().buffered()).use { d ->
            val name = ByteArray(32)
            "TestBook".toByteArray(Charsets.US_ASCII).copyInto(name)
            d.write(name)
            d.writeShort(0); d.writeShort(0)
            d.writeInt(0); d.writeInt(0); d.writeInt(0); d.writeInt(0)
            d.writeInt(0); d.writeInt(0)
            d.write("BOOK".toByteArray(Charsets.US_ASCII))
            d.write("MOBI".toByteArray(Charsets.US_ASCII))
            d.writeInt(0); d.writeInt(0)
            d.writeShort(2)
            d.writeInt(rec0Off); d.writeByte(0); d.writeByte(0); d.writeShort(0)
            d.writeInt(rec1Off); d.writeByte(0); d.writeByte(0); d.writeShort(0)
            d.write(rec0)
            d.write(textBytes)
        }
        return f
    }

    @Test
    fun detect_realMobi() {
        val f = buildMobi("<p>Hello</p>")
        try {
            assertTrue(MobiReader.isMobi(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun detect_rejectNonMobi() {
        val f = File.createTempFile("test", ".mobi")
        try {
            f.writeText("khong phai mobi")
            assertFalse(MobiReader.isMobi(f))
        } finally {
            f.delete()
        }
        val txt = File.createTempFile("test", ".txt")
        try {
            txt.writeText("plain text")
            assertFalse(MobiReader.isMobi(txt))
        } finally {
            txt.delete()
        }
    }

    @Test
    fun meta_fromExth() {
        val f = buildMobi("<p>x</p>", title = "Truyen Kieu", author = "Nguyen Du")
        try {
            val m = MobiReader.readMeta(f)
            assertEquals("Truyen Kieu", m.title)
            assertEquals("Nguyen Du", m.author)
        } finally {
            f.delete()
        }
    }

    @Test
    fun chapters_stripTagsAndEntities() {
        val f = buildMobi("<h1>Chuong 1</h1><p>Xin chao &amp; tam biet &#8212; het.</p>")
        try {
            val chaps = MobiReader.chapters(f, "Sach")
            assertEquals(1, chaps.size)
            assertTrue(chaps[0].text.contains("Xin chao & tam biet — het."))
            assertFalse(chaps[0].text.contains("<p>"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun iterText_roundTrip() {
        val f = buildMobi("<p>Doan mot.</p><p>Doan hai.</p>")
        try {
            val joined = MobiReader.iterText(f).joinToString("\n")
            assertTrue(joined.contains("Doan mot."))
            assertTrue(joined.contains("Doan hai."))
        } finally {
            f.delete()
        }
    }

    @Test
    fun palmdoc_literalsAndSpaceChar() {
        // literal "Hi" + 0xC1 = " " + ('A')
        val out = MobiReader.palmdocDecompress(byteArrayOf(0x48, 0x69, 0xC1.toByte()))
        assertEquals("Hi A", String(out, Charsets.ISO_8859_1))
    }

    @Test
    fun palmdoc_backReference() {
        // "AB" + ref(len=3, dist=2) → "ABABA"
        val out = MobiReader.palmdocDecompress(byteArrayOf(0x41, 0x42, 0x80.toByte(), 0x10))
        assertEquals("ABABA", String(out, Charsets.ISO_8859_1))
    }

    @Test
    fun convertToEpub_reusedByEpubReader() {
        val f = buildMobi("<p>Noi dung that.</p>", title = "Epub Hoa", author = "Tac Gia X")
        val dir = Files.createTempDir("mobiepub")
        try {
            val epub = MobiReader.convertToEpub(f, dir, "Epub Hoa")
            assertNotNull(epub)
            assertTrue(epub!!.exists())
            val chaps = EpubReader.chapters(epub, "Epub Hoa")
            assertTrue(chaps.any { it.text.contains("Noi dung that.") })
            val meta = EpubReader.readMeta(epub)
            assertEquals("Tac Gia X", meta.author)
        } finally {
            f.delete()
            dir.deleteRecursively()
        }
    }

    @Test
    fun drm_reportsError() {
        val f = buildMobi("<p>x</p>", encryption = 1)
        try {
            val r = MobiReader.readMobi(f)
            assertNotNull(r.error)
            assertTrue(r.error!!.contains("DRM"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun readMobi_fullResult() {
        // >200 ký tự để không rơi vào ngưỡng scan
        val body = "<p>Van ban day du. " + "Lap lai cau van de du dai. ".repeat(20) + "</p>"
        val f = buildMobi(body, title = "Day Du", author = "A")
        try {
            val r = MobiReader.readMobi(f)
            assertNull(r.error)
            assertEquals("Day Du", r.title)
            assertEquals("A", r.author)
            assertTrue(r.chapters.any { it.contains("Van ban day du.") })
            assertFalse(r.isScanned)
        } finally {
            f.delete()
        }
    }

    private object Files {
        fun createTempDir(prefix: String): File {
            val d = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
            d.mkdirs()
            return d
        }
    }
}
