package com.xudong.wubitrainer.data

import android.content.Context
import android.content.SharedPreferences
import com.xudong.wubitrainer.ui.theme.GlyphFont

/**
 * Every knob the learner can turn (PRD §7). Defaults are the ones agreed in the PRD.
 *
 * [lastStrictScope] is bookkeeping for the 仅全码 toolbar toggle: turning strict mode off has
 * to restore whatever non-strict scope was in force before, not silently reset to ANY_CODE.
 */
data class Settings(
    val passCount: Int = 2,
    val mistakeClearCount: Int = 3,
    val poolSize: Int = 3000,
    val freqSource: FreqSource = FreqSource.MODERN,
    val weightAlpha: Double = 0.5,
    val acceptMode: AcceptMode = AcceptMode.ON_THE_FLY,
    val acceptScope: AcceptScope = AcceptScope.ANY_CODE,
    val lastStrictScope: AcceptScope = AcceptScope.ANY_CODE,
    val failOnDeadPrefix: Boolean = true,
    val revealShortcut: Boolean = true,
    val explainShortcut: Boolean = true,
    val showKeypad: Boolean = true,
    /** Height of one keypad row, in dp. See [KEYPAD_HEIGHT_OPTIONS]. */
    val keypadHeightDp: Int = 46,
    /** Font of the headline character. Its colour follows the theme. */
    val glyphFont: GlyphFont = GlyphFont.DEFAULT,
) {
    val fullCodeOnly: Boolean get() = acceptScope == AcceptScope.FULL_CODE_ONLY

    /**
     * Pick a scope directly. Choosing a non-strict scope also remembers it, so that a later
     * 仅全码 toggle has something sensible to fall back to.
     */
    fun withAcceptScope(scope: AcceptScope): Settings = copy(
        acceptScope = scope,
        lastStrictScope = if (scope == AcceptScope.FULL_CODE_ONLY) lastStrictScope else scope,
    )

    /** Turn 仅全码 on, remembering the scope to come back to. */
    fun withFullCodeOnly(on: Boolean): Settings = if (on) {
        copy(lastStrictScope = if (acceptScope == AcceptScope.FULL_CODE_ONLY) lastStrictScope else acceptScope,
             acceptScope = AcceptScope.FULL_CODE_ONLY)
    } else {
        copy(acceptScope = if (lastStrictScope == AcceptScope.FULL_CODE_ONLY) AcceptScope.ANY_CODE else lastStrictScope)
    }
}

/** Persistence for [Settings]. Kept out of `progress.tsv` so that file stays a pure record. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wubi_settings", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            passCount = int(KEY_PASS, d.passCount).coerceIn(1, 10),
            mistakeClearCount = int(KEY_MISTAKE_CLEAR, d.mistakeClearCount).coerceIn(1, 10),
            poolSize = int(KEY_POOL, d.poolSize).coerceAtLeast(1),
            freqSource = enum(KEY_FREQ, d.freqSource),
            weightAlpha = prefs.getFloat(KEY_ALPHA, d.weightAlpha.toFloat()).toDouble(),
            acceptMode = enum(KEY_MODE, d.acceptMode),
            acceptScope = enum(KEY_SCOPE, d.acceptScope),
            lastStrictScope = enum(KEY_LAST_STRICT, d.lastStrictScope),
            failOnDeadPrefix = prefs.getBoolean(KEY_DEAD_PREFIX, d.failOnDeadPrefix),
            revealShortcut = prefs.getBoolean(KEY_REVEAL, d.revealShortcut),
            explainShortcut = prefs.getBoolean(KEY_EXPLAIN, d.explainShortcut),
            showKeypad = prefs.getBoolean(KEY_KEYPAD, d.showKeypad),
            keypadHeightDp = int(KEY_KEYPAD_HEIGHT, d.keypadHeightDp),
            glyphFont = enum(KEY_GLYPH_FONT, d.glyphFont),
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putInt(KEY_PASS, s.passCount)
            .putInt(KEY_MISTAKE_CLEAR, s.mistakeClearCount)
            .putInt(KEY_POOL, s.poolSize)
            .putString(KEY_FREQ, s.freqSource.name)
            .putFloat(KEY_ALPHA, s.weightAlpha.toFloat())
            .putString(KEY_MODE, s.acceptMode.name)
            .putString(KEY_SCOPE, s.acceptScope.name)
            .putString(KEY_LAST_STRICT, s.lastStrictScope.name)
            .putBoolean(KEY_DEAD_PREFIX, s.failOnDeadPrefix)
            .putBoolean(KEY_REVEAL, s.revealShortcut)
            .putBoolean(KEY_EXPLAIN, s.explainShortcut)
            .putBoolean(KEY_KEYPAD, s.showKeypad)
            .putInt(KEY_KEYPAD_HEIGHT, s.keypadHeightDp)
            .putString(KEY_GLYPH_FONT, s.glyphFont.name)
            .apply()
    }

    private fun int(key: String, fallback: Int): Int =
        runCatching { prefs.getInt(key, fallback) }.getOrDefault(fallback)

    private inline fun <reified T : Enum<T>> enum(key: String, fallback: T): T {
        val raw = prefs.getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }

    private companion object {
        const val KEY_PASS = "passCount"
        const val KEY_MISTAKE_CLEAR = "mistakeClearCount"
        const val KEY_POOL = "poolSize"
        const val KEY_FREQ = "freqSource"
        const val KEY_ALPHA = "weightAlpha"
        const val KEY_MODE = "acceptMode"
        const val KEY_SCOPE = "acceptScope"
        const val KEY_LAST_STRICT = "lastStrictScope"
        const val KEY_DEAD_PREFIX = "failOnDeadPrefix"
        const val KEY_REVEAL = "revealShortcut"
        const val KEY_EXPLAIN = "explainShortcut"
        const val KEY_KEYPAD = "showKeypad"
        const val KEY_KEYPAD_HEIGHT = "keypadHeightDp"
        const val KEY_GLYPH_FONT = "glyphFont"
    }
}


/**
 * Keycap sizes offered by the 键盘高度 setting.
 *
 * Discrete steps rather than a continuous slider on purpose: you want a size you can hit the same
 * way every session, not one you nudge into place. The labels matter more than the numbers —
 * 标准 is the default, and the range runs from comfortably smaller up to hand-sized.
 */
val KEYPAD_HEIGHT_OPTIONS: List<Pair<Int, String>> = listOf(
    38 to "小",
    46 to "标准",
    56 to "大",
    68 to "特大",
)

/** The label for a stored height, falling back to the raw number for an unknown value. */
fun keypadHeightLabel(dp: Int): String =
    KEYPAD_HEIGHT_OPTIONS.firstOrNull { it.first == dp }?.second ?: "$dp dp"

/** Options offered by the 练习范围 setting, in the order the settings screen shows them. */
val POOL_SIZE_OPTIONS: List<Pair<Int, String>> = listOf(
    500 to "前 500 字",
    1000 to "前 1000 字",
    2000 to "前 2000 字",
    3000 to "前 3000 字",
    5000 to "前 5000 字",
    9933 to "前 9933 字",
    Int.MAX_VALUE to "全部",
)
