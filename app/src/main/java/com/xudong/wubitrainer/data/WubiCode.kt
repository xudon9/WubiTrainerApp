package com.xudong.wubitrainer.data

/** Which frequency list drives ordering, sampling weight and the pool's top-N slice. */
enum class FreqSource { MODERN, OVERALL }

/**
 * Which of a character's codes count as a correct answer (PRD §6.1).
 *
 * Wubi86 gives many characters several codes: a one-letter 一级简码, sometimes a
 * 二级/三级简码, and the full code. Short codes are genuinely usable input in a real IME,
 * so by default they are accepted here too — but a learner who wants to be held to the
 * full decomposition can say so.
 */
enum class AcceptScope { ANY_CODE, EXCLUDE_LEVEL_ONE, FULL_CODE_ONLY }

/** When an answer is adjudicated (FR-04 / FR-05). */
enum class AcceptMode { ON_THE_FLY, ON_ENTER }

/** The two things a session can drill. */
enum class SessionMode { REGULAR, MISTAKE }

/** Where the app currently is. Deliberately a plain enum: three screens need no router. */
enum class Screen { PRACTICE, MISTAKES, SETTINGS }

/**
 * A single Chinese character together with every Wubi86 code it can be typed with.
 *
 * Invariants:
 *  - [codes] is sorted by length ascending, ties broken lexically, and is never empty.
 *  - `codes.last().length` is therefore the character's **full-code length**. It is usually
 *    4, but a character that decomposes into fewer than four radicals genuinely has no
 *    4-letter code (了 = b / bnh, 有 = e / def), and for those the 3-letter code *is* the
 *    full code. Requiring 4 letters would make such characters unpassable.
 *  - A character may have several full codes (齰 = hbaj / hwwj): two valid decompositions.
 */
class CharEntry(
    val char: String,
    val codes: List<String>,
    val rankModern: Int,
    val freqModern: Long,
    val rankOverall: Int,
    val freqOverall: Long,
) {
    /** Length of the longest code available for this character. */
    val maxCodeLength: Int get() = codes.last().length

    /** Every code that is as long as the longest one — i.e. every acceptable 全码. */
    val fullCodes: List<String> = codes.filter { it.length == codes.last().length }

    /** The 一级简码, when the character has one (only 25 characters do). */
    val levelOneCode: String? = codes.firstOrNull { it.length == 1 }

    /**
     * True when any code contains 'z'. Only 6 characters in the frequency pool do
     * (匚 钅 肀 攵 尢 彡, all with deprecated `zzxx` radical codes); for those, and only
     * those, the 'z' reveal shortcut has to yield to ordinary input (FR-27).
     */
    val hasZCode: Boolean = codes.any { 'z' in it }

    fun freq(source: FreqSource): Long = if (source == FreqSource.MODERN) freqModern else freqOverall

    fun rank(source: FreqSource): Int = if (source == FreqSource.MODERN) rankModern else rankOverall

    fun isFullCode(code: String): Boolean = code.length == maxCodeLength

    /** Human label for a code, used on the correction card. */
    fun labelOf(code: String): String = when {
        code.length == maxCodeLength -> "全码"
        code.length == 1 -> "一级简码"
        code.length == 2 -> "二级简码"
        else -> "三级简码"
    }

    /**
     * The codes accepted under [scope]. `EXCLUDE_LEVEL_ONE` falls back to the full set for
     * the (rare, out-of-pool) character whose only code is one letter, so that no character
     * can ever become unanswerable.
     */
    fun allowedCodes(scope: AcceptScope): List<String> = when (scope) {
        AcceptScope.ANY_CODE -> codes
        AcceptScope.EXCLUDE_LEVEL_ONE -> codes.filter { it.length > 1 }.ifEmpty { codes }
        AcceptScope.FULL_CODE_ONLY -> fullCodes
    }

    override fun toString(): String = "$char[${codes.joinToString("/")}]"
}

/** A character manufactured from the full table for a character outside the frequency pool. */
fun CharEntry.asOutOfPool(): CharEntry =
    if (freqModern == 0L && freqOverall == 0L) this else CharEntry(char, codes, 0, 0, 0, 0)
