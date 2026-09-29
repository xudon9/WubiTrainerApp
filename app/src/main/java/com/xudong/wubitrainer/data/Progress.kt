package com.xudong.wubitrainer.data

/**
 * Everything the app remembers about one character (PRD §3.2).
 *
 * Two independent axes:
 *  - **passing** — [correct] counts correct answers accumulated since the last mistake and
 *    resets to 0 on a wrong answer; reaching the configured N flips [passed], which is
 *    terminal (a later mistake never un-passes a character).
 *  - **the 错题集** — [inMistake] is set by any wrong answer (or by hand), and cleared when
 *    [mistakeCorrect] reaches the configured K. Wrong answers reset [mistakeCorrect] to 0.
 */
data class CharProgress(
    val char: String,
    val correct: Int = 0,
    val wrong: Int = 0,
    val passed: Boolean = false,
    val inMistake: Boolean = false,
    val mistakeCorrect: Int = 0,
    val lastSeenMs: Long = 0L,
) {
    val untouched: Boolean get() = correct == 0 && wrong == 0 && !inMistake
}

/** Aggregate figures derived from the rows — never stored, always recomputed (FR-32). */
data class ProgressStats(
    val distinctPractised: Int = 0,
    val totalCorrect: Int = 0,
    val totalWrong: Int = 0,
    val passed: Int = 0,
    val inMistake: Int = 0,
) {
    val totalAnswers: Int get() = totalCorrect + totalWrong
    val accuracy: Double get() = if (totalAnswers == 0) 0.0 else totalCorrect.toDouble() / totalAnswers
}

/** Per-session tallies shown in the practice header (FR-30). */
data class SessionStats(
    val answered: Int = 0,
    val correct: Int = 0,
    val passedThisSession: Int = 0,
    val leftMistakeThisSession: Int = 0,
) {
    val accuracy: Double get() = if (answered == 0) 0.0 else correct.toDouble() / answered
}

/**
 * The on-disk shape of `progress.tsv`.
 *
 * `extras` carries any trailing columns a *future* version of this app may have written:
 * we do not understand them, but silently dropping a learner's data because we are older
 * than the file is not acceptable, so they are round-tripped verbatim.
 */
class ProgressFile(
    val rows: MutableMap<String, CharProgress> = LinkedHashMap(),
    val extras: MutableMap<String, String> = HashMap(),
    val warnings: List<String> = emptyList(),
)

/**
 * `progress.tsv` codec (PRD §3.2). Format:
 *
 * ```
 * # wubi-trainer progress v1
 * # saved_at\t<epochMillis>
 * # char\tcorrect\twrong\tpassed\tin_mistake\tmistake_correct\tlast_seen_ms
 * 的\t2\t5\t1\t0\t0\t1759132794123
 * ```
 */
object ProgressTsv {

    const val VERSION = 1
    const val FILE_NAME = "progress.tsv"
    const val BACKUP_NAME = "progress.tsv.bak"
    const val TEMP_NAME = "progress.tsv.tmp"
    const val HEADER = "char\tcorrect\twrong\tpassed\tin_mistake\tmistake_correct\tlast_seen_ms"

    fun encode(rows: Collection<CharProgress>, extras: Map<String, String>, savedAt: Long): String {
        val sb = StringBuilder(64 * 1024)
        sb.append("# wubi-trainer progress v").append(VERSION).append('\n')
        sb.append("# saved_at\t").append(savedAt).append('\n')
        sb.append("# ").append(HEADER).append('\n')
        for (p in rows.sortedBy { it.char }) {
            sb.append(p.char).append('\t')
                .append(p.correct).append('\t')
                .append(p.wrong).append('\t')
                .append(if (p.passed) 1 else 0).append('\t')
                .append(if (p.inMistake) 1 else 0).append('\t')
                .append(p.mistakeCorrect).append('\t')
                .append(p.lastSeenMs)
            extras[p.char]?.takeIf { it.isNotEmpty() }?.let { sb.append('\t').append(it) }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * Parse a progress file. Never throws: a row that cannot be understood is skipped and
     * reported in [ProgressFile.warnings], because a half-readable file is still valuable.
     */
    fun decode(text: String): ProgressFile {
        val rows = LinkedHashMap<String, CharProgress>()
        val extras = HashMap<String, String>()
        val warnings = ArrayList<String>()
        var lineNo = 0
        for (raw in text.lineSequence()) {
            lineNo++
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size < 2) {
                warnings += "line $lineNo: too few columns"
                continue
            }
            val char = f[0]
            if (char.codePointCount(0, char.length) != 1) {
                warnings += "line $lineNo: not a single character"
                continue
            }
            val correct = f.getOrNull(1)?.trim()?.toIntOrNull()
            if (correct == null || correct < 0) {
                warnings += "line $lineNo: bad correct count"
                continue
            }
            rows[char] = CharProgress(
                char = char,
                correct = correct,
                wrong = f.getOrNull(2)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                passed = (f.getOrNull(3)?.trim()?.toIntOrNull() ?: 0) != 0,
                inMistake = (f.getOrNull(4)?.trim()?.toIntOrNull() ?: 0) != 0,
                mistakeCorrect = f.getOrNull(5)?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                lastSeenMs = f.getOrNull(6)?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            )
            if (f.size > 7) {
                extras[char] = f.subList(7, f.size).joinToString("\t")
            }
        }
        return ProgressFile(rows, extras, warnings)
    }
}
