package com.xudong.wubitrainer

import com.xudong.wubitrainer.data.AcceptScope
import com.xudong.wubitrainer.data.CharEntry
import com.xudong.wubitrainer.data.CharProgress
import com.xudong.wubitrainer.data.FreqSource
import com.xudong.wubitrainer.data.ProgressAccess
import com.xudong.wubitrainer.data.SessionMode
import com.xudong.wubitrainer.engine.AddResult
import com.xudong.wubitrainer.engine.EngineConfig
import com.xudong.wubitrainer.engine.PracticeEngine
import com.xudong.wubitrainer.engine.PrefixState
import com.xudong.wubitrainer.engine.Verdict
import com.xudong.wubitrainer.engine.WrongReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The product lives in these rules, so they are tested directly and exhaustively: passing,
 * the reset-on-mistake rule, the two axes of the 错题集, accept scopes, prefix classification
 * and the sampling contract.
 */
class PracticeEngineTest {

    private class FakeProgress : ProgressAccess {
        val map = HashMap<String, CharProgress>()
        override fun get(char: String): CharProgress = map[char] ?: CharProgress(char)
        override fun put(progress: CharProgress) {
            map[progress.char] = progress
        }

        override fun snapshot(): List<CharProgress> = map.values.toList()
    }

    private class Harness(entries: List<CharEntry>, var config: EngineConfig = EngineConfig()) {
        val progress = FakeProgress()
        val engine = PracticeEngine(
            poolModern = entries,
            poolOverall = entries,
            lookup = { char -> entries.firstOrNull { it.char == char } },
            progress = progress,
            config = { config },
            random = Random(20260929),
            clock = { 1_700_000_000_000L },
        )
    }

    private fun entry(char: String, codes: List<String>, freq: Long = 0L) = CharEntry(
        char = char,
        codes = codes.sortedWith(compareBy({ it.length }, { it })),
        rankModern = if (freq > 0) 1 else 0,
        freqModern = freq,
        rankOverall = if (freq > 0) 1 else 0,
        freqOverall = freq,
    )

    private val yi = entry("一", listOf("ggll", "ggl", "g"))
    private val gong = entry("工", listOf("aaaa", "aaa", "a"))
    private val liao = entry("了", listOf("bnh", "b"))
    private val zhai = entry("齰", listOf("hbaj", "hwwj", "hb"))
    private val all = listOf(yi, gong, liao, zhai)

    // --------------------------------------------------------------------------- passing

    @Test
    fun `N correct answers in independent samplings pass a character`() {
        val h = Harness(all, EngineConfig(passCount = 2))

        val first = h.engine.submit("ggll", yi, SessionMode.REGULAR) as Verdict.Correct
        assertFalse(first.passedNow)
        assertEquals(1, first.progress.correct)
        assertFalse(h.progress.get("一").passed)

        val second = h.engine.submit("ggll", yi, SessionMode.REGULAR) as Verdict.Correct
        assertTrue(second.passedNow)
        assertEquals(2, second.progress.correct)
        assertTrue(h.progress.get("一").passed)
    }

    @Test
    fun `a wrong answer before passing resets the accumulated count`() {
        val h = Harness(all, EngineConfig(passCount = 3))
        h.engine.submit("ggll", yi, SessionMode.REGULAR)
        h.engine.submit("ggll", yi, SessionMode.REGULAR)
        assertEquals(2, h.progress.get("一").correct)

        val wrong = h.engine.submit("zzzz", yi, SessionMode.REGULAR) as Verdict.Wrong
        assertEquals(WrongReason.DEAD_PREFIX, wrong.reason)
        assertEquals(0, h.progress.get("一").correct)
        assertFalse(h.progress.get("一").passed)
        assertEquals(1, h.progress.get("一").wrong)

        // So the next correct answer starts the count from scratch, not from 3.
        val next = h.engine.submit("ggll", yi, SessionMode.REGULAR) as Verdict.Correct
        assertEquals(1, next.progress.correct)
        assertFalse(next.passedNow)
    }

    @Test
    fun `once passed, a later mistake does not un-pass the character`() {
        val h = Harness(all, EngineConfig(passCount = 1))
        h.engine.submit("g", yi, SessionMode.REGULAR)
        assertTrue(h.progress.get("一").passed)

        h.engine.submit("x", yi, SessionMode.REGULAR)
        val after = h.progress.get("一")
        assertTrue("passing is terminal", after.passed)
        assertEquals("a mistake still resets the next-pass counter", 0, after.correct)
        assertEquals(1, after.wrong)
        assertTrue("and it does join the 错题集", after.inMistake)
    }

    // ------------------------------------------------------------------------ 错题集

    @Test
    fun `a wrong answer joins the mistake set and K correct answers leave it`() {
        // passCount is deliberately out of reach: this test is about the K path alone, and a
        // character that passes leaves the set immediately instead (see the next test).
        val h = Harness(all, EngineConfig(passCount = 99, mistakeClearCount = 3))
        val wrong = h.engine.submit("q", gong, SessionMode.REGULAR) as Verdict.Wrong
        assertTrue(wrong.progress.inMistake)
        assertEquals(0, wrong.progress.mistakeCorrect)

        // Two correct answers are not enough.
        repeat(2) { h.engine.submit("aaaa", gong, SessionMode.REGULAR) }
        assertTrue(h.progress.get("工").inMistake)
        assertEquals(2, h.progress.get("工").mistakeCorrect)

        val third = h.engine.submit("aaaa", gong, SessionMode.REGULAR) as Verdict.Correct
        assertTrue(third.leftMistakeSet)
        assertFalse(h.progress.get("工").inMistake)
        assertEquals(0, h.progress.get("工").mistakeCorrect)
    }

    @Test
    fun `a mistake in the middle of clearing the mistake set restarts the count`() {
        val h = Harness(all, EngineConfig(passCount = 99, mistakeClearCount = 3))
        h.engine.submit("q", gong, SessionMode.REGULAR)
        h.engine.submit("aaaa", gong, SessionMode.REGULAR)
        h.engine.submit("aaaa", gong, SessionMode.REGULAR)
        assertEquals(2, h.progress.get("工").mistakeCorrect)

        h.engine.submit("qq", gong, SessionMode.REGULAR)
        assertEquals(0, h.progress.get("工").mistakeCorrect)
        assertTrue(h.progress.get("工").inMistake)
    }

    @Test
    fun `passing a character removes it from the mistake set immediately`() {
        // K is high on purpose: passing must not wait for it.
        val h = Harness(all, EngineConfig(passCount = 2, mistakeClearCount = 5))
        h.engine.submit("q", liao, SessionMode.REGULAR)
        assertTrue(h.progress.get("了").inMistake)

        // One correct answer is not enough to pass, so the character stays in the set.
        val first = h.engine.submit("bnh", liao, SessionMode.REGULAR) as Verdict.Correct
        assertFalse(first.passedNow)
        assertFalse(first.leftMistakeSet)
        assertTrue(h.progress.get("了").inMistake)
        assertEquals(1, h.progress.get("了").mistakeCorrect)

        // The second passes it, and the pass discharges the 错题集 entry outright.
        val second = h.engine.submit("bnh", liao, SessionMode.REGULAR) as Verdict.Correct
        assertTrue(second.passedNow)
        assertTrue(second.leftMistakeSet)
        assertTrue(h.progress.get("了").passed)
        assertFalse(h.progress.get("了").inMistake)
        assertEquals(0, h.progress.get("了").mistakeCorrect)
        assertFalse(h.engine.mistakeRows().any { it.char == "了" })

        // And it stays passed and out of the set.
        assertTrue(h.engine.submit("bnh", liao, SessionMode.REGULAR) is Verdict.Correct)
        assertTrue(h.progress.get("了").passed)
        assertFalse(h.progress.get("了").inMistake)
    }

    @Test
    fun `a passed character that is missed again returns to the mistake set`() {
        val h = Harness(all, EngineConfig(passCount = 1, mistakeClearCount = 2))
        h.engine.submit("q", liao, SessionMode.REGULAR)
        h.engine.submit("bnh", liao, SessionMode.REGULAR)   // passes, leaves the set
        assertTrue(h.progress.get("了").passed)
        assertFalse(h.progress.get("了").inMistake)

        h.engine.submit("zzz", liao, SessionMode.REGULAR)   // missed again
        assertTrue("passing is still terminal", h.progress.get("了").passed)
        assertTrue("but the 错题集 takes it back", h.progress.get("了").inMistake)
    }

    @Test
    fun `characters can be added to and removed from the mistake set by hand`() {
        val h = Harness(all)
        assertTrue((h.engine.addToMistakeSet("齰") as AddResult.Added).alreadyInSet.not())
        assertTrue(h.progress.get("齰").inMistake)
        // Adding twice is idempotent, and never resets a counter that is already running.
        h.progress.put(h.progress.get("齰").copy(mistakeCorrect = 2))
        assertTrue((h.engine.addToMistakeSet("齰") as AddResult.Added).alreadyInSet)
        assertEquals(2, h.progress.get("齰").mistakeCorrect)

        h.engine.removeFromMistakeSet("齰")
        assertFalse(h.progress.get("齰").inMistake)
        assertEquals(0, h.progress.get("齰").mistakeCorrect)
    }

    @Test
    fun `a hand-added character with no Wubi code is refused, not silently dropped`() {
        val h = Harness(all)
        val result = h.engine.addToMistakeSet("한")
        assertTrue(result is AddResult.Rejected)
        assertEquals("한", (result as AddResult.Rejected).char)
        assertFalse(h.progress.get("한").inMistake)
    }

    @Test
    fun `the mistake list is most recently seen first`() {
        val h = Harness(all)
        var clock = 1_000L
        val engine = PracticeEngine(
            poolModern = all, poolOverall = all,
            lookup = { c -> all.firstOrNull { it.char == c } },
            progress = h.progress, config = { h.config },
            random = Random(1), clock = { clock },
        )
        engine.submit("q", yi, SessionMode.REGULAR)
        clock = 2_000L
        engine.submit("q", gong, SessionMode.REGULAR)
        clock = 3_000L
        engine.submit("q", liao, SessionMode.REGULAR)

        assertEquals(listOf("了", "工", "一"), engine.mistakeRows().map { it.char })
    }

    // -------------------------------------------------------------------- accept scopes

    @Test
    fun `any-code scope accepts short codes as well as the full code`() {
        val h = Harness(all, EngineConfig(acceptScope = AcceptScope.ANY_CODE, passCount = 99))
        listOf("a", "aaa", "aaaa").forEach { code ->
            val v = h.engine.submit(code, gong, SessionMode.REGULAR)
            assertTrue("$code should be accepted", v is Verdict.Correct)
        }
    }

    @Test
    fun `full-code-only accepts only the longest code`() {
        val h = Harness(all, EngineConfig(acceptScope = AcceptScope.FULL_CODE_ONLY, passCount = 99))
        assertTrue(h.engine.submit("a", gong, SessionMode.REGULAR) is Verdict.Wrong)
        assertTrue(h.engine.submit("aaa", gong, SessionMode.REGULAR) is Verdict.Wrong)
        assertTrue(h.engine.submit("aaaa", gong, SessionMode.REGULAR) is Verdict.Correct)
    }

    @Test
    fun `full-code-only still accepts a 3-letter full code`() {
        // 了 genuinely has no 4-letter code; requiring one would make it unpassable (PRD D2).
        val h = Harness(all, EngineConfig(acceptScope = AcceptScope.FULL_CODE_ONLY, passCount = 99))
        assertTrue(h.engine.submit("b", liao, SessionMode.REGULAR) is Verdict.Wrong)
        assertTrue(h.engine.submit("bnh", liao, SessionMode.REGULAR) is Verdict.Correct)
    }

    @Test
    fun `full-code-only accepts either of two full codes`() {
        val h = Harness(all, EngineConfig(acceptScope = AcceptScope.FULL_CODE_ONLY, passCount = 99))
        assertTrue(h.engine.submit("hbaj", zhai, SessionMode.REGULAR) is Verdict.Correct)
        assertTrue(h.engine.submit("hwwj", zhai, SessionMode.REGULAR) is Verdict.Correct)
        assertTrue(h.engine.submit("hb", zhai, SessionMode.REGULAR) is Verdict.Wrong)
    }

    @Test
    fun `excluding level-one codes rejects the single-letter code only`() {
        val h = Harness(all, EngineConfig(acceptScope = AcceptScope.EXCLUDE_LEVEL_ONE, passCount = 99))
        assertTrue(h.engine.submit("a", gong, SessionMode.REGULAR) is Verdict.Wrong)
        assertTrue(h.engine.submit("aaa", gong, SessionMode.REGULAR) is Verdict.Correct)
        assertTrue(h.engine.submit("aaaa", gong, SessionMode.REGULAR) is Verdict.Correct)
    }

    @Test
    fun `the correction card is offered every code the character has`() {
        val h = Harness(all, EngineConfig(acceptScope = AcceptScope.FULL_CODE_ONLY))
        val wrong = h.engine.submit("a", gong, SessionMode.REGULAR) as Verdict.Wrong
        // Accepted set is only the full code…
        assertEquals(listOf("aaaa"), wrong.allowed)
        // …but the entry itself still knows about all three, which is what the card renders.
        assertEquals(listOf("a", "aaa", "aaaa"), wrong.entry.codes)
        assertEquals("一级简码", wrong.entry.labelOf("a"))
    }

    // ---------------------------------------------------------- prefix classification

    @Test
    fun `prefix classification under any-code scope`() {
        val h = Harness(all, EngineConfig(acceptScope = AcceptScope.ANY_CODE))
        assertEquals(PrefixState.EMPTY, h.engine.classify("", yi))
        assertEquals(PrefixState.ACCEPTABLE, h.engine.classify("g", yi))
        assertEquals(PrefixState.VIABLE, h.engine.classify("gg", yi))
        assertEquals(PrefixState.ACCEPTABLE, h.engine.classify("ggl", yi))
        assertEquals(PrefixState.ACCEPTABLE, h.engine.classify("ggll", yi))
        assertEquals(PrefixState.DEAD, h.engine.classify("x", yi))
        assertEquals(PrefixState.DEAD, h.engine.classify("ggx", yi))
        assertEquals(PrefixState.DEAD, h.engine.classify("gglll", yi))
        assertEquals("input is case-insensitive", PrefixState.ACCEPTABLE, h.engine.classify("GGLL", yi))
    }

    @Test
    fun `prefix classification follows the active scope`() {
        val h = Harness(all, EngineConfig(acceptScope = AcceptScope.FULL_CODE_ONLY))
        assertEquals("a short code is no longer acceptable, but still a viable prefix",
            PrefixState.VIABLE, h.engine.classify("g", yi))
        assertEquals(PrefixState.ACCEPTABLE, h.engine.classify("ggll", yi))
        val lenient = Harness(all, EngineConfig(acceptScope = AcceptScope.ANY_CODE))
        assertEquals(PrefixState.ACCEPTABLE, lenient.engine.classify("g", yi))
    }

    @Test
    fun `a wrong answer submitted while still viable is not a dead prefix`() {
        val h = Harness(all)
        val wrong = h.engine.submit("gg", yi, SessionMode.REGULAR) as Verdict.Wrong
        assertEquals(WrongReason.NOT_A_CODE, wrong.reason)
    }

    @Test
    fun `an empty submission never passes`() {
        val h = Harness(all, EngineConfig(passCount = 1))
        val wrong = h.engine.submit("", yi, SessionMode.REGULAR) as Verdict.Wrong
        assertEquals(WrongReason.NOT_A_CODE, wrong.reason)
        assertFalse(h.progress.get("一").passed)
    }

    // -------------------------------------------------------------------------- sampling

    @Test
    fun `consecutive samples never repeat the same character`() {
        val h = Harness(all)
        var previous: String? = null
        repeat(5_000) {
            val picked = h.engine.sample(SessionMode.REGULAR)
            assertNotNull(picked)
            assertEquals("sampled ${picked!!.char} right after $previous", false, picked.char == previous)
            previous = picked.char
        }
    }

    @Test
    fun `sampling alone never changes progress`() {
        // 跳过 is a sample with no submit, so it has to be observably free: no counter moves, no
        // character lands in the 错题集, and the session records no answer.
        val h = Harness(all)
        repeat(200) { h.engine.sample(SessionMode.REGULAR) }
        assertTrue(h.progress.snapshot().isEmpty())
        assertEquals(0, h.engine.stats().totalAnswers)
        assertEquals(0, h.engine.stats().inMistake)
    }

    @Test
    fun `a single-member pool is still samplable`() {
        val h = Harness(listOf(yi))
        repeat(5) {
            assertEquals("一", h.engine.sample(SessionMode.REGULAR)?.char)
        }
    }

    @Test
    fun `an exhausted pool yields nothing rather than repeating a passed character`() {
        val h = Harness(all, EngineConfig(passCount = 1))
        all.forEach { h.engine.submit(it.codes.last(), it, SessionMode.REGULAR) }
        assertNull(h.engine.sample(SessionMode.REGULAR))
        assertEquals(0, h.engine.eligibleCount(SessionMode.REGULAR))
    }

    @Test
    fun `sampling respects the pool-size slice`() {
        val entries = (1..100).map { entry("字", listOf("a")) .copyWithChar("字$it", it.toLong()) }
        val h = Harness(entries, EngineConfig(poolSize = 10))
        assertEquals(10, h.engine.eligibleCount(SessionMode.REGULAR))
    }

    @Test
    fun `pure-frequency weighting follows freq`() {
        val rare = entry("甲", listOf("aaaa"), freq = 1_000)
        val common = entry("乙", listOf("aaab"), freq = 1_000_000)
        val h = Harness(listOf(common, rare), EngineConfig(weightAlpha = 1.0))
        var commonHits = 0
        repeat(50_000) {
            if (h.engine.sample(SessionMode.REGULAR, avoidChar = null)?.char == "乙") commonHits++
        }
        val share = commonHits / 50_000.0
        assertTrue("expected ~0.999, got $share", share > 0.99)
    }

    @Test
    fun `dampened weighting keeps rare characters reachable`() {
        val rare = entry("甲", listOf("aaaa"), freq = 1_000)
        val common = entry("乙", listOf("aaab"), freq = 1_000_000)
        val h = Harness(listOf(common, rare), EngineConfig(weightAlpha = 0.5))
        var rareHits = 0
        repeat(200_000) {
            if (h.engine.sample(SessionMode.REGULAR, avoidChar = null)?.char == "甲") rareHits++
        }
        // sqrt(1000) / (sqrt(1000) + sqrt(1_000_000)) = 31.6 / 1031.6 ≈ 0.0307
        val share = rareHits / 200_000.0
        assertTrue("expected ~0.031, got $share", share in 0.024..0.038)
    }

    @Test
    fun `uniform weighting ignores frequency`() {
        val rare = entry("甲", listOf("aaaa"), freq = 1_000)
        val common = entry("乙", listOf("aaab"), freq = 1_000_000)
        val h = Harness(listOf(common, rare), EngineConfig(weightAlpha = 0.0))
        var rareHits = 0
        repeat(20_000) {
            if (h.engine.sample(SessionMode.REGULAR, avoidChar = null)?.char == "甲") rareHits++
        }
        val share = rareHits / 20_000.0
        assertTrue("expected ~0.5, got $share", share in 0.46..0.54)
    }

    @Test
    fun `characters with no count in the active list stay reachable`() {
        val noFreq = CharEntry("罕", listOf("aaaa"), 0, 0L, 0, 0L)
        val h = Harness(listOf(noFreq), EngineConfig(weightAlpha = 0.5))
        assertEquals("罕", h.engine.sample(SessionMode.REGULAR)?.char)
    }

    @Test
    fun `mistake mode samples only from the mistake set`() {
        val h = Harness(all)
        h.engine.addToMistakeSet("工")
        h.engine.addToMistakeSet("了")
        assertFalse(h.engine.mistakeRows().any { it.char == "一" })  // 一 is not in the set
        val seen = HashSet<String>()
        repeat(200) { seen += h.engine.sample(SessionMode.MISTAKE)!!.char }
        assertEquals(setOf("工", "了"), seen)
    }

    @Test
    fun `mistake mode shrinks as characters are cleared`() {
        val h = Harness(all, EngineConfig(mistakeClearCount = 1))
        h.engine.addToMistakeSet("工")
        h.engine.addToMistakeSet("了")
        assertEquals(2, h.engine.eligibleCount(SessionMode.MISTAKE))

        h.engine.submit("aaaa", gong, SessionMode.MISTAKE)
        assertEquals(1, h.engine.eligibleCount(SessionMode.MISTAKE))
        h.engine.submit("bnh", liao, SessionMode.MISTAKE)
        assertNull(h.engine.sample(SessionMode.MISTAKE))
    }

    // ----------------------------------------------------------------------------- stats

    @Test
    fun `stats aggregate the rows`() {
        val h = Harness(all, EngineConfig(passCount = 1))
        h.engine.submit("g", yi, SessionMode.REGULAR)      // correct + passed
        h.engine.submit("q", gong, SessionMode.REGULAR)    // wrong  + mistake
        val stats = h.engine.stats()
        assertEquals(2, stats.distinctPractised)
        assertEquals(1, stats.totalCorrect)
        assertEquals(1, stats.totalWrong)
        assertEquals(1, stats.passed)
        assertEquals(1, stats.inMistake)
        assertEquals(0.5, stats.accuracy, 1e-9)
    }

    @Test
    fun `reset helpers are transformations, not side effects`() {
        val h = Harness(all, EngineConfig(passCount = 1))
        h.engine.submit("g", yi, SessionMode.REGULAR)
        h.engine.submit("q", gong, SessionMode.REGULAR)

        val passedCleared = h.engine.clearPassed().associateBy { it.char }
        assertFalse(passedCleared.getValue("一").passed)
        assertEquals(0, passedCleared.getValue("一").correct)
        assertTrue("clearing passing leaves the mistake set alone", passedCleared.getValue("工").inMistake)

        val mistakesCleared = h.engine.clearMistakes().associateBy { it.char }
        assertFalse(mistakesCleared.getValue("工").inMistake)
        assertTrue("clearing mistakes leaves passing alone", mistakesCleared.getValue("一").passed)
        assertEquals(1, mistakesCleared.getValue("工").wrong)

        assertTrue(h.engine.clearAll().isEmpty())
    }

    @Test
    fun `the z shortcut yields to characters whose codes contain z`() {
        val h = Harness(all)
        assertFalse(h.engine.usesLetterZ(yi))
        assertTrue(h.engine.usesLetterZ(entry("彡", listOf("zzpp", "ettt", "ett"))))
    }

    @Test
    fun `the overall ranking can drive the pool order`() {
        // 甲 leads the site's default list; 乙 leads the Modern list. With a one-character slice,
        // the active ranking alone decides which of them is in range.
        val jia = CharEntry("甲", listOf("aaaa"), rankModern = 9, freqModern = 5, rankOverall = 1, freqOverall = 900)
        val yiChar = CharEntry("乙", listOf("aaab"), rankModern = 1, freqModern = 900, rankOverall = 9, freqOverall = 5)
        val progress = FakeProgress()
        var config = EngineConfig(poolSize = 1)
        val engine = PracticeEngine(
            poolModern = listOf(yiChar, jia),
            poolOverall = listOf(jia, yiChar),
            lookup = { c -> listOf(jia, yiChar).firstOrNull { it.char == c } },
            progress = progress,
            config = { config },
            random = Random(3),
            clock = { 0L },
        )
        assertEquals("乙", engine.sample(SessionMode.REGULAR)?.char)

        config = config.copy(freqSource = FreqSource.OVERALL)
        engine.forgetLastSampled()
        assertEquals("甲", engine.sample(SessionMode.REGULAR)?.char)
    }

    /** Test-only helper: a distinct character with a distinct frequency. */
    private fun CharEntry.copyWithChar(char: String, freq: Long) = CharEntry(
        char = char,
        codes = codes,
        rankModern = 1,
        freqModern = freq,
        rankOverall = 1,
        freqOverall = freq,
    )
}
