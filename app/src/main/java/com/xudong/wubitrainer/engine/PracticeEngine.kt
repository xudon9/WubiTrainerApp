package com.xudong.wubitrainer.engine

import com.xudong.wubitrainer.data.AcceptScope
import com.xudong.wubitrainer.data.CharEntry
import com.xudong.wubitrainer.data.CharProgress
import com.xudong.wubitrainer.data.FreqSource
import com.xudong.wubitrainer.data.ProgressAccess
import com.xudong.wubitrainer.data.ProgressStats
import com.xudong.wubitrainer.data.SessionMode
import com.xudong.wubitrainer.data.Settings
import kotlin.math.pow
import kotlin.random.Random

/** The subset of settings the drill arithmetic depends on. */
data class EngineConfig(
    val passCount: Int = 2,
    val mistakeClearCount: Int = 3,
    val poolSize: Int = 3000,
    val freqSource: FreqSource = FreqSource.MODERN,
    val weightAlpha: Double = 0.5,
    val acceptScope: AcceptScope = AcceptScope.ANY_CODE,
) {
    companion object {
        fun of(s: Settings) = EngineConfig(
            passCount = s.passCount,
            mistakeClearCount = s.mistakeClearCount,
            poolSize = s.poolSize,
            freqSource = s.freqSource,
            weightAlpha = s.weightAlpha,
            acceptScope = s.acceptScope,
        )
    }
}

/** How the current input buffer relates to what would be accepted (PRD §6.3). */
enum class PrefixState {
    /** Nothing typed yet. Never "wrong", even with fail-on-dead-prefix enabled. */
    EMPTY,

    /** Cannot become an accepted code any more — a dead end. */
    DEAD,

    /** A proper prefix of at least one accepted code. */
    VIABLE,

    /** Exactly an accepted code. */
    ACCEPTABLE,
}

enum class WrongReason {
    /** The committed buffer is not an accepted code (typically submitted with Enter). */
    NOT_A_CODE,

    /** The buffer already cannot become an accepted code, so it was failed early (FR-11). */
    DEAD_PREFIX,
}

sealed interface Verdict {
    data class Correct(
        val entry: CharEntry,
        val code: String,
        val progress: CharProgress,
        val correctBefore: Int,
        /** True when this answer is the one that flipped the character to passed. */
        val passedNow: Boolean,
        /** True when this answer is the one that took the character out of the 错题集. */
        val leftMistakeSet: Boolean,
    ) : Verdict

    data class Wrong(
        val entry: CharEntry,
        val input: String,
        /** Every code that would have been accepted under the active scope, shortest first. */
        val allowed: List<String>,
        val reason: WrongReason,
        val progress: CharProgress,
    ) : Verdict
}

sealed interface AddResult {
    data class Added(val entry: CharEntry, val alreadyInSet: Boolean) : AddResult
    data class Rejected(val char: String, val reason: String) : AddResult
}

/**
 * The whole drill rule set, with no Android dependency at all — which is what makes the
 * pass/mistake arithmetic unit-testable without a device (PRD §9).
 *
 * Counting rules (PRD §6.2), stated once here because they are the product:
 *  - a correct answer increments `correct`; reaching N flips `passed`, which is terminal;
 *  - a wrong answer resets `correct` to 0 and puts the character in the 错题集;
 *  - a correct answer for a character in the 错题集 increments `mistakeCorrect`; reaching K
 *    removes it from the set; **passing a character removes it immediately**, without waiting
 *    for K (owner's decision);
 *  - a wrong answer resets `mistakeCorrect` to 0 and puts the character back in the set.
 *
 * The owner's phrase "N correct in independent sampling, NOT N in a row" is honoured
 * structurally: [sample] never returns the character it just returned (FR-24), so a
 * character's correct answers always come from separate presentations.
 */
class PracticeEngine(
    private val poolModern: List<CharEntry>,
    private val poolOverall: List<CharEntry>,
    private val lookup: (String) -> CharEntry?,
    private val progress: ProgressAccess,
    private val config: () -> EngineConfig,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The character returned by the previous [sample] call; never returned twice in a row. */
    private var lastSampled: String? = null

    /** Bumped whenever anything that can change the eligible pool changes. */
    private var version: Int = 0
    private var cache: CachedPool? = null
    private var cacheKey: CacheKey? = null

    private data class CacheKey(
        val version: Int,
        val mode: SessionMode,
        val poolSize: Int,
        val source: FreqSource,
        val alpha: Double,
    )

    private class CachedPool(val items: List<CharEntry>, val cumulative: DoubleArray) {
        val total: Double get() = cumulative.lastOrNull() ?: 0.0
    }

    /** Called by the controller when settings change; progress mutations invalidate themselves. */
    fun invalidate() {
        version++
    }

    fun entryFor(char: String): CharEntry? = lookup(char)

    fun allowedFor(entry: CharEntry): List<String> = entry.allowedCodes(config().acceptScope)

    // ---------------------------------------------------------------- classification

    /**
     * Classify [input] against [entry] under the active scope.
     *
     * An empty buffer is [PrefixState.EMPTY]; a buffer equal to an accepted code is
     * [PrefixState.ACCEPTABLE]; a proper prefix of one is [PrefixState.VIABLE]; anything else
     * has nowhere to go and is [PrefixState.DEAD].
     */
    fun classify(input: String, entry: CharEntry): PrefixState {
        val s = input.lowercase()
        if (s.isEmpty()) return PrefixState.EMPTY
        val allowed = allowedFor(entry)
        if (allowed.any { it == s }) return PrefixState.ACCEPTABLE
        if (allowed.any { it.startsWith(s) }) return PrefixState.VIABLE
        return PrefixState.DEAD
    }

    /** True when the 'z' reveal shortcut must yield to ordinary input for this character. */
    fun usesLetterZ(entry: CharEntry): Boolean = entry.hasZCode

    // ---------------------------------------------------------------------- sampling

    /**
     * Draw the next character. Returns null only when the eligible pool is empty, which the
     * UI turns into the completion screen (FR-26) or the empty-错题集 state (FR-25).
     *
     * The previously sampled character is excluded whenever the pool has more than one member,
     * which is what makes the learner's correct answers independent samplings rather than a
     * streak (the owner's "N times, NOT N in a row"). The invariant lives here rather than in
     * the caller so it cannot be forgotten; [avoidChar] only exists so a test can force it.
     */
    fun sample(mode: SessionMode, avoidChar: String? = lastSampled): CharEntry? {
        val cfg = config()
        val cached = cachedPool(mode, cfg) ?: return null
        val items = cached.items
        if (items.isEmpty()) return null

        val picked = draw(cached, avoidChar)
        lastSampled = picked.char
        return picked
    }

    private fun draw(cached: CachedPool, avoidChar: String?): CharEntry {
        val items = cached.items
        val total = cached.total
        if (total <= 0.0 || !total.isFinite() || items.size == 1 || avoidChar == null) {
            // A degenerate total should be impossible (weights fall back to 1.0); if it ever
            // happens, a uniform pick beats dividing by zero.
            return items[pickIndex(cached, random.nextDouble())]
        }
        var attempt = 0
        while (attempt < 64) {
            val candidate = items[pickIndex(cached, random.nextDouble())]
            if (candidate.char != avoidChar) return candidate
            attempt++
        }
        // Astronomically unlikely for a multi-member pool; fall back to a neighbour so the
        // contract "different from the last one" still holds rather than silently breaking.
        val idx = items.indexOfFirst { it.char != avoidChar }
        return if (idx >= 0) items[idx] else items[0]
    }

    /** Forget the last sample, e.g. when a session restarts. */
    fun forgetLastSampled() {
        lastSampled = null
    }

    private fun pickIndex(cached: CachedPool, r: Double): Int {
        val target = r * cached.total
        val cum = cached.cumulative
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun cachedPool(mode: SessionMode, cfg: EngineConfig): CachedPool? {
        val key = CacheKey(version, mode, cfg.poolSize, cfg.freqSource, cfg.weightAlpha)
        cacheKey?.let { if (it == key) return cache }
        val items = eligible(mode, cfg)
        val cum = DoubleArray(items.size)
        var running = 0.0
        for (i in items.indices) {
            running += weight(items[i], cfg)
            cum[i] = running
        }
        return CachedPool(items, cum).also {
            cache = it
            cacheKey = key
        }
    }

    private fun eligible(mode: SessionMode, cfg: EngineConfig): List<CharEntry> = when (mode) {
        SessionMode.REGULAR -> {
            val ordered = if (cfg.freqSource == FreqSource.MODERN) poolModern else poolOverall
            val slice = if (cfg.poolSize >= ordered.size) ordered else ordered.subList(0, cfg.poolSize.coerceAtLeast(0))
            slice.filter { !progress.get(it.char).passed }
        }

        SessionMode.MISTAKE -> progress.snapshot()
            .asSequence()
            .filter { it.inMistake }
            .mapNotNull { lookup(it.char) }
            .sortedBy { it.rank(cfg.freqSource) }
            .toList()
    }

    /**
     * Sampling weight: `freq^alpha`, so the default alpha of 0.5 damps Zipf rather than
     * letting 的 (7.9 M occurrences) drown a rank-3000 character (count 1). A character with
     * no count in the active list keeps weight 1.0 so it stays reachable instead of
     * impossible (PRD §6 FR-16 / FR-23).
     */
    private fun weight(entry: CharEntry, cfg: EngineConfig): Double {
        if (cfg.weightAlpha <= 0.0) return 1.0
        val f = entry.freq(cfg.freqSource)
        if (f <= 0L) return 1.0
        val w = f.toDouble().pow(cfg.weightAlpha)
        return if (w.isFinite() && w > 0.0) w else 1.0
    }

    // --------------------------------------------------------------------- adjudication

    /** Adjudicate a committed answer and apply its consequences. */
    fun submit(input: String, entry: CharEntry, mode: SessionMode): Verdict {
        val cfg = config()
        val allowed = entry.allowedCodes(cfg.acceptScope)
        val typed = input.trim().lowercase()
        val before = progress.get(entry.char)
        val now = clock()

        if (typed.isNotEmpty() && allowed.contains(typed)) {
            var next = before.copy(
                char = entry.char,
                correct = before.correct + 1,
                lastSeenMs = now,
            )
            val passedNow = !before.passed && next.correct >= cfg.passCount
            if (passedNow) next = next.copy(passed = true)

            var leftMistake = false
            if (next.inMistake) {
                if (passedNow) {
                    // Passing is the stronger outcome: it discharges the 错题集 entry outright,
                    // without waiting for K correct answers to accumulate.
                    next = next.copy(inMistake = false, mistakeCorrect = 0)
                    leftMistake = true
                } else {
                    val mc = next.mistakeCorrect + 1
                    if (mc >= cfg.mistakeClearCount) {
                        next = next.copy(inMistake = false, mistakeCorrect = 0)
                        leftMistake = true
                    } else {
                        next = next.copy(mistakeCorrect = mc)
                    }
                }
            }
            progress.put(next)
            invalidate()
            return Verdict.Correct(
                entry = entry,
                code = typed,
                progress = next,
                correctBefore = before.correct,
                passedNow = passedNow,
                leftMistakeSet = leftMistake,
            )
        }

        val next = before.copy(
            char = entry.char,
            correct = 0,
            wrong = before.wrong + 1,
            inMistake = true,
            mistakeCorrect = 0,
            lastSeenMs = now,
        )
        progress.put(next)
        invalidate()
        return Verdict.Wrong(
            entry = entry,
            input = typed,
            allowed = allowed,
            reason = if (classify(typed, entry) == PrefixState.DEAD) {
                WrongReason.DEAD_PREFIX
            } else {
                WrongReason.NOT_A_CODE
            },
            progress = next,
        )
    }

    // -------------------------------------------------------------------- mistake set

    /**
     * Hand-add a character to the 错题集. Accepts anything [lookup] can produce codes for —
     * which, for hand entry, means the whole 70,944-character Wubi86 table, not just the
     * frequency pool (PRD D8).
     */
    fun addToMistakeSet(char: String): AddResult {
        val entry = lookup(char)
            ?: return AddResult.Rejected(char, "没有找到这个字的五笔编码")
        val before = progress.get(char)
        if (before.inMistake) return AddResult.Added(entry, alreadyInSet = true)
        progress.put(before.copy(char = char, inMistake = true, mistakeCorrect = 0))
        invalidate()
        return AddResult.Added(entry, alreadyInSet = false)
    }

    fun removeFromMistakeSet(char: String) {
        val before = progress.get(char)
        if (!before.inMistake) return
        progress.put(before.copy(inMistake = false, mistakeCorrect = 0))
        invalidate()
    }

    /** The 错题集 as stored, most recently seen first (FR-20). */
    fun mistakeRows(): List<CharProgress> =
        progress.snapshot().filter { it.inMistake }.sortedByDescending { it.lastSeenMs }

    fun stats(): ProgressStats {
        var tc = 0
        var tw = 0
        var passed = 0
        var mistake = 0
        var practised = 0
        for (p in progress.snapshot()) {
            tc += p.correct
            tw += p.wrong
            if (p.passed) passed++
            if (p.inMistake) mistake++
            if (p.correct > 0 || p.wrong > 0) practised++
        }
        return ProgressStats(practised, tc, tw, passed, mistake)
    }

    /** How many characters the active settings make eligible right now. */
    fun eligibleCount(mode: SessionMode): Int {
        val cfg = config()
        return cachedPool(mode, cfg)?.items?.size ?: 0
    }

    /**
     * Reset helpers (FR-18). Written as transformations of the current rows so the caller
     * keeps control of *what* is reset:
     *  - [clearPassed] also zeroes `correct`, so the characters have to be re-earned;
     *  - [clearMistakes] leaves the passing axis untouched.
     */
    fun clearPassed(): List<CharProgress> =
        progress.snapshot().map { it.copy(passed = false, correct = 0) }

    fun clearMistakes(): List<CharProgress> =
        progress.snapshot().map { if (it.inMistake) it.copy(inMistake = false, mistakeCorrect = 0) else it }

    fun clearAll(): List<CharProgress> = emptyList()
}
