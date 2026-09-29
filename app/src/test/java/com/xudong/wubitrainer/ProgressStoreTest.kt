package com.xudong.wubitrainer

import com.xudong.wubitrainer.data.CharProgress
import com.xudong.wubitrainer.data.LoadSource
import com.xudong.wubitrainer.data.ProgressStore
import com.xudong.wubitrainer.data.ProgressTsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The progress record is the one thing a learner cannot re-derive, so its format and its
 * recovery behaviour are tested harder than anything else (PRD §3.3).
 */
class ProgressStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun row(
        char: String,
        correct: Int = 0,
        wrong: Int = 0,
        passed: Boolean = false,
        inMistake: Boolean = false,
        mistakeCorrect: Int = 0,
    ) = CharProgress(char, correct, wrong, passed, inMistake, mistakeCorrect, 1_700_000_000_000L)

    @Test
    fun `tsv round trip preserves every column`() {
        val rows = listOf(
            row("的", correct = 2, wrong = 5, passed = true),
            row("工", correct = 1, inMistake = true, mistakeCorrect = 2),
        )
        val text = ProgressTsv.encode(rows, emptyMap(), 1_759_132_800_000L)
        assertTrue(text.startsWith("# wubi-trainer progress v1"))
        assertTrue(text.contains("# char\tcorrect\twrong\tpassed\tin_mistake\tmistake_correct\tlast_seen_ms"))

        val decoded = ProgressTsv.decode(text)
        assertEquals(2, decoded.rows.size)
        assertEquals(rows[0], decoded.rows.getValue("的"))
        assertEquals(rows[1], decoded.rows.getValue("工"))
        assertTrue(decoded.warnings.isEmpty())
    }

    @Test
    fun `unknown future columns are preserved verbatim`() {
        val text = """
            # wubi-trainer progress v1
            # char	correct	wrong	passed	in_mistake	mistake_correct	last_seen_ms	streak	daily
            的	2	5	1	0	0	1759132794123	7	monday
        """.trimIndent()
        val decoded = ProgressTsv.decode(text)
        assertEquals("7\tmonday", decoded.extras["的"])

        val reencoded = ProgressTsv.encode(decoded.rows.values, decoded.extras, 1L)
        assertTrue(reencoded.contains("1759132794123\t7\tmonday"))
    }

    @Test
    fun `damaged rows are reported, not invented`() {
        val text = """
            # char	correct	wrong	passed	in_mistake	mistake_correct	last_seen_ms
            的	2	5	1	0	0	1759132794123
            工	notanumber	0	0	0	0	0
            好
            了	1	0	0	0	0	0
        """.trimIndent()
        val decoded = ProgressTsv.decode(text)
        assertEquals(setOf("的", "了"), decoded.rows.keys)
        assertEquals(2, decoded.warnings.size)
    }

    @Test
    fun `write then load reproduces the rows`() {
        val dir = temp.newFolder()
        val store = ProgressStore(dir)
        store.put(row("的", correct = 2, passed = true))
        store.put(row("工", inMistake = true, mistakeCorrect = 1))
        assertTrue(store.writeNow())

        val reopened = ProgressStore(dir)
        val report = reopened.load()
        assertEquals(LoadSource.MAIN, report.source)
        assertEquals(2, report.rows)
        assertEquals(2, reopened.get("的").correct)
        assertTrue(reopened.get("的").passed)
        assertTrue(reopened.get("工").inMistake)
        assertEquals(1, reopened.get("工").mistakeCorrect)
    }

    @Test
    fun `a corrupt main file falls back to the backup and quarantines the original`() {
        val dir = temp.newFolder()
        val store = ProgressStore(dir)
        store.put(row("的", correct = 1))
        store.writeNow()

        // Second write creates progress.tsv.bak holding the first snapshot.
        store.put(row("的", correct = 2, passed = true))
        store.put(row("工", wrong = 3, inMistake = true))
        store.writeNow()
        assertTrue(File(dir, ProgressTsv.BACKUP_NAME).isFile)

        File(dir, ProgressTsv.FILE_NAME).writeText("this is not a progress file at all\n")

        val reopened = ProgressStore(dir)
        val report = reopened.load()
        assertEquals(LoadSource.BACKUP, report.source)
        assertEquals(1, report.rows)
        assertNotNull(report.quarantinePath)
        assertTrue("the damaged file must be kept for inspection",
            File(report.quarantinePath!!).isFile)
        assertEquals(1, reopened.get("的").correct)
    }

    @Test
    fun `when both files are unusable the app starts empty without destroying anything`() {
        val dir = temp.newFolder()
        File(dir, ProgressTsv.FILE_NAME).writeText("garbage\n")
        File(dir, ProgressTsv.BACKUP_NAME).writeText("also garbage\n")

        val store = ProgressStore(dir)
        val report = store.load()
        assertEquals(LoadSource.EMPTY_AFTER_FAILURE, report.source)
        assertEquals(0, report.rows)
        assertTrue(report.warnings.isNotEmpty())
        assertNotNull(report.quarantinePath)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `an absent file is not an error`() {
        val dir = temp.newFolder()
        val store = ProgressStore(dir)
        val report = store.load()
        assertEquals(LoadSource.NONE, report.source)
        assertEquals(0, report.rows)
        assertEquals(CharProgress("的"), store.get("的"))
        assertFalse(File(dir, ProgressTsv.FILE_NAME).exists())
    }
}
