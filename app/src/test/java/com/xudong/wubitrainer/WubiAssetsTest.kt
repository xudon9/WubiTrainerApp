package com.xudong.wubitrainer

import com.xudong.wubitrainer.data.CharEntry
import com.xudong.wubitrainer.data.WubiAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Parser tests, plus an integrity check against the **real generated assets**. The latter is the
 * point: it is the only automated guard that the TSV shipped in the APK still has the shape the
 * engine assumes (length-ascending codes, every row a single character drawn from a–z).
 *
 * Unit tests run with the module directory as the working directory, so `src/main/assets` is
 * reachable directly.
 */
class WubiAssetsTest {

    private val assetsDir = File("src/main/assets")

    @Test
    fun `parses chars tsv and sorts codes by length then lexically`() {
        val text = """
            # wubi_chars v1
            # char	codes	rank_modern	freq_modern	rank_overall	freq_overall
            一	ggll,ggl,g	2	3050722	3	677676
            工	aaaa,aaa,a	1	1	1	1
        """.trimIndent()
        val entries = WubiAssets.parseCharsTsv(text)
        assertEquals(2, entries.size)
        assertEquals(listOf("g", "ggl", "ggll"), entries[0].codes)
        assertEquals(listOf("a", "aaa", "aaaa"), entries[1].codes)
        assertEquals(2, entries[0].rankModern)
        assertEquals(3050722L, entries[0].freqModern)
        assertEquals(677676L, entries[0].freqOverall)
    }

    @Test
    fun `keeps z codes, which six pool characters genuinely have`() {
        val entries = WubiAssets.parseCharsTsv("彡\tzzpp,ettt,ett\t1\t1\t1\t1")
        assertEquals(listOf("ett", "ettt", "zzpp"), entries[0].codes)
        assertEquals(4, entries[0].maxCodeLength)
        assertTrue(entries[0].hasZCode)
    }

    @Test
    fun `skips malformed rows instead of inventing data`() {
        val text = """
            # char	codes
            一	ggll
            好	not-a-code
            too	many,chars,here,indeed,xx
            ab	two
            的	rqyy
        """.trimIndent()
        val entries = WubiAssets.parseCharsTsv(text)
        // "too" is three code points, "ab" is two, and "not-a-code" fails the letter check.
        assertEquals(listOf("一", "的"), entries.map { it.char })
    }

    @Test
    fun `parses the full table and splits user input`() {
        val full = WubiAssets.parseFullTsv(
            """
            # wubi_full v1
            # char	codes
            齰	hbaj,hwwj,hb
            了	bnh,b
            """.trimIndent(),
        )
        assertEquals(listOf("hb", "hbaj", "hwwj"), full["齰"])
        assertEquals(listOf("b", "bnh"), full["了"])

        assertEquals(listOf("一", "丁", "了"), WubiAssets.splitCharacters("一丁了"))
        assertEquals(listOf("的", "𬯀"), WubiAssets.splitCharacters("的𬯀"))
        assertEquals(emptyList<String>(), WubiAssets.splitCharacters("   "))
    }

    @Test
    fun `full code is the longest code, and a 3-letter full code is legitimate`() {
        val liao = CharEntry("了", listOf("b", "bnh"), 0, 0, 0, 0)
        assertEquals(listOf("bnh"), liao.fullCodes)
        assertEquals("全码", liao.labelOf("bnh"))
        assertEquals("一级简码", liao.labelOf("b"))
        assertEquals("b", liao.levelOneCode)

        val zhai = CharEntry("齰", listOf("hb", "hbaj", "hwwj"), 0, 0, 0, 0)
        assertEquals(listOf("hbaj", "hwwj"), zhai.fullCodes)
        assertNull(zhai.levelOneCode)

        val yi = CharEntry("一", listOf("g", "ggl", "ggll"), 0, 0, 0, 0)
        assertEquals("三级简码", yi.labelOf("ggl"))
        assertEquals("全码", yi.labelOf("ggll"))
    }

    @Test
    fun `shipped pool asset has the documented shape`() {
        val file = File(assetsDir, "wubi_chars.tsv")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val entries = WubiAssets.parseCharsTsv(file.readText(Charsets.UTF_8))

        assertTrue("pool should hold the union of both frequency lists", entries.size > 11_000)
        assertTrue("every row must be a single character", entries.all { it.char.codePointCount(0, it.char.length) == 1 })
        assertTrue(
            "codes must come back length-ascending",
            entries.all { e -> e.codes.zipWithNext().all { (a, b) -> a.length <= b.length } },
        )
        assertTrue("every code is 1..4 letters", entries.all { e -> e.codes.all { it.length in 1..4 } })
        assertTrue("codes are lowercase a-z", entries.all { e -> e.codes.all { c -> c.all { it in 'a'..'z' } } })
        assertTrue("codes are unique per character", entries.all { e -> e.codes.distinct().size == e.codes.size })

        // Spot-check characters whose encodings are well known, so a silently wrong table fails.
        val byChar = entries.associateBy { it.char }
        assertEquals(listOf("g", "ggl", "ggll"), byChar.getValue("一").codes)
        assertEquals(listOf("b", "bnh"), byChar.getValue("了").codes)
        assertEquals(listOf("e", "def"), byChar.getValue("有").codes)
        assertEquals(listOf("a", "aaa", "aaaa"), byChar.getValue("工").codes)

        // Rank 1 of the Modern list is 的; of the site's default list it is 之. Rows absent from
        // a list carry rank 0, so the ranking has to be read from the ranked rows only.
        assertEquals("的", entries.filter { it.rankModern > 0 }.minByOrNull { it.rankModern }?.char)
        assertEquals("之", entries.filter { it.rankOverall > 0 }.minByOrNull { it.rankOverall }?.char)
        assertTrue("characters outside the Modern list still have a Wubi code",
            entries.any { it.rankModern == 0 && it.codes.isNotEmpty() })

        // Exactly the six documented pool characters carry a code containing z.
        val withZ = entries.filter { it.hasZCode }.map { it.char }.toSet()
        assertEquals(setOf("匚", "钅", "肀", "攵", "尢", "彡"), withZ)
    }

    @Test
    fun `shipped full table asset covers every single-character encoding`() {
        val file = File(assetsDir, "wubi_full.tsv")
        assertTrue("missing ${file.absolutePath}", file.isFile)
        val full = WubiAssets.parseFullTsv(file.readText(Charsets.UTF_8))
        assertEquals(70_944, full.size)
        assertEquals(listOf("g", "ggl", "ggll"), full["一"])
        assertFalse("words must not be present", full.containsKey("你好"))
        assertTrue("rare characters survive", full.containsKey("齰"))
    }

    @Test
    fun `hand-added characters resolve from the full table, and only if they have a code`() {
        // This is the exact resolution path the 错题集 add box uses: pool first, then the
        // 70,944-character table, then refuse.
        val pool = WubiAssets.parseCharsTsv(File(assetsDir, "wubi_chars.tsv").readText(Charsets.UTF_8))
            .associateBy { it.char }
        val full = WubiAssets.parseFullTsv(File(assetsDir, "wubi_full.tsv").readText(Charsets.UTF_8))

        // In the pool: comes back with its ranks intact.
        val common = WubiAssets.resolve("工", pool, full)
        assertNotNull(common)
        assertEquals(listOf("a", "aaa", "aaaa"), common!!.codes)
        assertTrue(common.rankModern > 0)

        // 58,903 characters carry a Wubi86 code but are nowhere near either frequency list, so
        // they are reachable only through the full table — with no rank, which is exactly what
        // makes the sampling weight fall back to 1.0 instead of 0.
        assertFalse("廾 must not be in the pool for this test to mean anything", pool.containsKey("廾"))
        val rare = WubiAssets.resolve("廾", pool, full)
        assertNotNull(rare)
        assertEquals(listOf("agt", "agth", "zzpp"), rare!!.codes)
        assertEquals(0, rare.rankModern)
        assertEquals(0L, rare.freqModern)

        // The dictionary also carries punctuation and Latin-1 symbols with zz… placeholder
        // codes; those must be refused even though the table knows them.
        assertTrue(full.containsKey("à"))
        assertNull(WubiAssets.resolve("à", pool, full))
        assertNull(WubiAssets.resolve("¤", pool, full))

        // Nothing at all: refused rather than added with no way to answer it.
        assertNull(WubiAssets.resolve("x", pool, full))
        assertNull(WubiAssets.resolve("한", pool, full))
        assertNull(WubiAssets.resolve("廾", pool, null))
        assertNull(WubiAssets.resolve("廾x", pool, full))

        // 齰 is rarer than it looks: it really is in the pooled frequency lists.
        assertTrue(pool.containsKey("齰"))
        assertEquals("齰", WubiAssets.resolve("齰", pool, full)?.char)
    }

    @Test
    fun `only Han characters are considered addable`() {
        assertTrue(WubiAssets.isHan("的"))
        assertTrue(WubiAssets.isHan("廾"))
        assertTrue(WubiAssets.isHan("𬯀"))
        assertFalse(WubiAssets.isHan("x"))
        assertFalse(WubiAssets.isHan("à"))
        assertFalse(WubiAssets.isHan("한"))
        assertFalse(WubiAssets.isHan(""))
        assertFalse(WubiAssets.isHan("字字"))
    }

    @Test
    fun `manifest and attribution ship with the app`() {
        assertTrue(File(assetsDir, "ASSET_MANIFEST.txt").isFile)
        assertTrue(File(assetsDir, "ATTRIBUTION.txt").isFile)
    }
}
