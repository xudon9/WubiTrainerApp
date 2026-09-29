package com.xudong.wubitrainer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// An ink-and-paper palette: the app is a drill notebook, not a game.
internal val Ink = Color(0xFF1F4E5F)
internal val InkLight = Color(0xFF3A7288)
internal val Paper = Color(0xFFF7F5F0)
internal val PaperDark = Color(0xFF12181B)
private val Wrong = Color(0xFFB3261E)

/**
 * Semantic colours for answer states.
 *
 * These are deliberately not part of the Material scheme — 正确/错误/提示 need to survive a
 * theme change with their *meaning* intact — but they absolutely must follow the theme's
 * lightness. The light values are dark inks that read on paper; the dark values are light
 * tints that read on [PaperDark]. Shipping one fixed set is precisely what made dark mode
 * unreadable: a #1F7A45 green on a #12181B background is unreadable text, not a colour choice.
 *
 * Colour is never the only signal: every one of these is paired with a glyph or a word.
 */
@Immutable
data class PracticeColors(
    val correct: Color,
    val wrong: Color,
    val caution: Color,
    val neutral: Color,
)

/**
 * Font for the headline character.
 *
 * These are the generic families Android resolves through its CJK fallback chain, so what they
 * actually render depends on the device's own fonts — and that is measured, not assumed: on the
 * reference device 默认 and 宋体 differ across 4,439 pixels of the sample glyph, while a
 * `Monospace` option differed across **zero**. Monospace was therefore removed rather than
 * shipped as a choice that does nothing — Android has no CJK monospace face to fall back to.
 * The settings screen shows a live sample so the remaining choice can be judged by eye.
 *
 * The colour is deliberately NOT a setting: the glyph uses the theme's `onBackground`, which the
 * contrast test already holds to a readable ratio in both modes. A free colour choice would have
 * had to re-earn that in every combination.
 */
enum class GlyphFont(val labelZh: String) {
    DEFAULT("默认"),
    SERIF("宋体"),
}

/**
 * The platform family behind each [GlyphFont].
 *
 * The judgement call: Android has no first-class CJK typeface API, so this asks for the generic
 * families and lets the platform's CJK fallback pick the actual face. On a device that ships
 * Noto Serif CJK, 宋体 really is a different typeface; where it does not, the option is a no-op
 * and renders like 默认 — which is why the settings screen shows a live sample instead of asking
 * the learner to trust a label.
 */
internal fun GlyphFont.family(): FontFamily = when (this) {
    GlyphFont.DEFAULT -> FontFamily.Default
    GlyphFont.SERIF -> FontFamily.Serif
}


internal val LightPracticeColors = PracticeColors(
    correct = Color(0xFF1B7A46),
    wrong = Wrong,
    caution = Color(0xFF8A5300),
    neutral = InkLight,
)

internal val DarkPracticeColors = PracticeColors(
    correct = Color(0xFF7ED9A4),
    wrong = Color(0xFFFFB4AB),
    caution = Color(0xFFF0C070),
    neutral = Color(0xFF9BCBDC),
)

val LocalPracticeColors = staticCompositionLocalOf { LightPracticeColors }

/**
 * Theme-aware accessors, so call sites read [PracticePalette.correct] in either mode and get
 * whatever the current theme makes readable. Mirrors how `MaterialTheme.colorScheme` works.
 */
object PracticePalette {
    val correct: Color
        @Composable @ReadOnlyComposable
        get() = LocalPracticeColors.current.correct

    val wrong: Color
        @Composable @ReadOnlyComposable
        get() = LocalPracticeColors.current.wrong

    val caution: Color
        @Composable @ReadOnlyComposable
        get() = LocalPracticeColors.current.caution

    val neutral: Color
        @Composable @ReadOnlyComposable
        get() = LocalPracticeColors.current.neutral
}

internal val LightScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6EC),
    onPrimaryContainer = Color(0xFF0B2A34),
    secondary = InkLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EDF1),
    onSecondaryContainer = Color(0xFF12313C),
    background = Paper,
    onBackground = Color(0xFF1A1C1D),
    surface = Color(0xFFFDFCF9),
    onSurface = Color(0xFF1A1C1D),
    surfaceVariant = Color(0xFFE8E4DA),
    onSurfaceVariant = Color(0xFF49454A),
    outline = Color(0xFF7A7670),
    outlineVariant = Color(0xFFC9C4B9),
    // The container/inverse tokens Material 3 components read for themselves: NavigationBar,
    // Card, Snackbar and friends pick their own backgrounds out of these, so a scheme that
    // leaves them at the baseline defaults renders components that do not belong to this theme.
    surfaceDim = Color(0xFFDEDAD1),
    surfaceBright = Color(0xFFFDFCF9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F7F2),
    surfaceContainer = Color(0xFFF3F0E9),
    surfaceContainerHigh = Color(0xFFEDEAE3),
    surfaceContainerHighest = Color(0xFFE7E4DD),
    inverseSurface = Color(0xFF2F3133),
    inverseOnSurface = Color(0xFFF1F0EB),
    inversePrimary = Color(0xFF9BCBDC),
    error = Wrong,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * The dark scheme is the light one inverted around the same ink/paper idea: a near-black page,
 * slightly lifted surfaces, and light tints for anything that has to stay legible.
 */
internal val DarkScheme = darkColorScheme(
    primary = Color(0xFF9BCBDC),
    onPrimary = Color(0xFF05323F),
    primaryContainer = Color(0xFF21495A),
    onPrimaryContainer = Color(0xFFCDE7F0),
    secondary = Color(0xFFB4CBD5),
    onSecondary = Color(0xFF1F333C),
    secondaryContainer = Color(0xFF33454E),
    onSecondaryContainer = Color(0xFFD0E4EC),
    background = PaperDark,
    onBackground = Color(0xFFE3E6E8),
    surface = Color(0xFF1A2124),
    onSurface = Color(0xFFE3E6E8),
    surfaceVariant = Color(0xFF313A3E),
    onSurfaceVariant = Color(0xFFC1C7CB),
    outline = Color(0xFF8B9296),
    outlineVariant = Color(0xFF454D51),
    surfaceDim = Color(0xFF0E1315),
    surfaceBright = Color(0xFF343C40),
    surfaceContainerLowest = Color(0xFF0A0F11),
    surfaceContainerLow = Color(0xFF171E21),
    surfaceContainer = Color(0xFF1B2326),
    surfaceContainerHigh = Color(0xFF252D31),
    surfaceContainerHighest = Color(0xFF30383C),
    inverseSurface = Color(0xFFE3E6E8),
    inverseOnSurface = Color(0xFF12181B),
    inversePrimary = Color(0xFF1F4E5F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Monospace style for Wubi codes, so letters line up and l/g/1 are unambiguous. */
val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    letterSpacing = 2.sp,
)

/**
 * The app theme.
 *
 * Note what it publishes beyond the colour scheme:
 *  - [LocalPracticeColors], so the 正确/错误 semantics are theme-aware; and
 *  - [LocalContentColor], because `MaterialTheme` does *not* publish a content colour of its
 *    own. Without this, any `Text` that does not name a colour falls back to
 *    `LocalContentColor`'s black default — invisible on a dark background. Screens deliberately
 *    paint their own background (see `WubiRoot`) rather than relying on the window's.
 */
@Composable
fun WubiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        CompositionLocalProvider(
            LocalPracticeColors provides if (dark) DarkPracticeColors else LightPracticeColors,
            LocalContentColor provides scheme.onBackground,
            content = content,
        )
    }
}
