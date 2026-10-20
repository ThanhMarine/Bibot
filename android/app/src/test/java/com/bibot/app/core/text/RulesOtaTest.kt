package com.bibot.app.core.text

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * H3.2 OTA rulepack — verify checksum/version gate của RulesOta.
 * Neo quan trọng: `realPack_checksumMatchesPython` chạy canonical SHA-256 của Kotlin
 * trên JSON do compile_rules.py sinh THẬT → 2 phía (Python/Kotlin) phải khớp byte.
 * Pure-JVM (không Context/mạng). Chạy: :app:testDemoDebugUnitTest.
 */
class RulesOtaTest {

    private fun realJson(): String {
        val candidates = listOf(
            File("src/main/assets/reading-rules.json"),
            File("app/src/main/assets/reading-rules.json")
        )
        val f = requireNotNull(candidates.firstOrNull { it.exists() }) {
            "thiếu assets reading-rules.json (chạy từ module :app)"
        }
        return f.readText(Charsets.UTF_8)
    }

    /** Đổi version + tính lại checksum đúng quy trình (để gate tự kiểm chứng nội bộ). */
    private fun bumpVersion(text: String, v: Long): String {
        val bumped = text.replace(Regex(""""version":\s*\d+"""), """"version": $v""")
        val root = ReadingRules.MiniJson.parse(bumped) as Map<*, *>
        val payload = linkedMapOf<String, Any?>("rules" to root["rules"], "version" to root["version"])
        val sum = RulesOta.sha256Hex(RulesOta.canonicalJson(payload))
        return bumped.replace(Regex(""""checksum":\s*"[0-9a-fA-F]+""""), """"checksum": "$sum"""")
    }

    @Test
    fun realPack_checksumMatchesPython() {
        val text = realJson()
        // compile_rules.py: sha256(canonical {"rules","version"}) == checksum trong file.
        assertTrue("checksum không khớp canonical của Kotlin", RulesOta.isValidPack(text))
        assertEquals(4L, RulesOta.versionOf(text))
        assertTrue(ReadingRules.parse(text).isNotEmpty())
    }

    @Test
    fun pickPack_versionAndChecksumGate() {
        val assets = realJson()
        val newer = bumpVersion(assets, 5L)
        val same = bumpVersion(assets, 4L)
        val older = bumpVersion(assets, 3L)
        val corrupt = newer.replace(Regex(""""checksum":\s*"[0-9a-fA-F]+""""), """"checksum": "${"0".repeat(64)}"""")

        assertEquals(newer, RulesOta.pickPack(assets, newer))
        assertEquals(assets, RulesOta.pickPack(assets, same))
        assertEquals(assets, RulesOta.pickPack(assets, older))
        assertEquals(assets, RulesOta.pickPack(assets, corrupt))
        assertEquals(assets, RulesOta.pickPack(assets, null))

        assertTrue(RulesOta.isValidPack(newer))
        assertFalse(RulesOta.isValidPack(corrupt))
        assertFalse(RulesOta.isValidPack("""{"version":9,"rules":[],"checksum":"x"}"""))
        assertFalse(RulesOta.isValidPack("không phải json"))
        assertNull(RulesOta.versionOf("không phải json"))
    }

    @Test
    fun canonical_likePythonSortAndEscape() {
        // sort_keys TOÀN BỘ map (kể cả lồng nhau), mảng giữ thứ tự; ngăn cách Python
        // mặc định = ", " và ": " (json.dumps không compact).
        assertEquals(
            """{"a": "x", "b": 1}""",
            RulesOta.canonicalJson(linkedMapOf<String, Any?>("b" to 1L, "a" to "x"))
        )
        assertEquals(
            """{"rules": [{"id": "r", "priority": 3}], "version": 4}""",
            RulesOta.canonicalJson(
                linkedMapOf<String, Any?>(
                    "version" to 4L,
                    "rules" to listOf(linkedMapOf<String, Any?>("priority" to 3L, "id" to "r"))
                )
            )
        )
        // Escape y hệt json.dumps: " \ \b \f \n \r \t, điều khiển còn lại → \u00xx hex thường.
        assertEquals(
            """"a\"b\\c\nd\te\u0001f"""",
            RulesOta.canonicalJson("a\"b\\c\nd\te\u0001f")
        )
        // ensure_ascii=False: tiếng Việt để nguyên UTF-8.
        assertEquals("\"tiếng Việt\"", RulesOta.canonicalJson("tiếng Việt"))
        assertEquals("true", RulesOta.canonicalJson(true))
        assertEquals("null", RulesOta.canonicalJson(null))
        assertEquals("[1, 2]", RulesOta.canonicalJson(listOf(1L, 2L)))
    }

    @Test
    fun sha256_hexLowercase_knownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RulesOta.sha256Hex("abc")
        )
    }
}
