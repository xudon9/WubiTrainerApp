package com.xudong.wubitrainer

import androidx.compose.ui.graphics.Color
import com.xudong.wubitrainer.ui.theme.DarkPracticeColors
import com.xudong.wubitrainer.ui.theme.DarkScheme
import com.xudong.wubitrainer.ui.theme.LightPracticeColors
import com.xudong.wubitrainer.ui.theme.LightScheme
import com.xudong.wubitrainer.ui.theme.PracticeColors
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * "It should support both light and dark mode" is a *measurable* claim, so it is measured.
 *
 * The dark theme originally shipped with one fixed set of semantic colours — a #1B7A46 green and
 * a #B3261E red tuned for paper — which are unreadable on a #12181B page. That is the kind of
 * regression a screenshot catches once and a test catches forever, so this asserts WCAG contrast
 * ratios for 正确 / 错误 / 提示 and the page and surface text that carries meaning.
 */
class ThemeContrastTest {

    /** WCAG 2.1 relative luminance. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    /** WCAG 2.1 contrast ratio. */
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertContrast(
        what: String,
        foreground: Color,
        background: Color,
        minimum: Double = 4.5,
    ) {
        val ratio = contrast(foreground, background)
        assertTrue(
            "%s has a contrast ratio of %.2f:1 against its background, below the %.1f:1 minimum"
                .format(what, ratio, minimum),
            ratio >= minimum,
        )
    }

    private fun checkMode(name: String, palette: PracticeColors, page: Color, surface: Color) {
        for ((label, colour) in listOf(
            "correct" to palette.correct,
            "wrong" to palette.wrong,
            "caution" to palette.caution,
            "neutral" to palette.neutral,
        )) {
            assertContrast("$name/$label on the page", colour, page)
            // The correction card and the mistake rows put these on a lifted surface.
            assertContrast("$name/$label on a surface", colour, surface)
        }
    }

    @Test
    fun `semantic answer colours are readable in light mode`() {
        checkMode("light", LightPracticeColors, LightScheme.background, LightScheme.surface)
    }

    @Test
    fun `semantic answer colours are readable in dark mode`() {
        checkMode("dark", DarkPracticeColors, DarkScheme.background, DarkScheme.surface)
    }

    /**
     * The regression that made dark mode unreadable: the two palettes must actually differ, and
     * the dark one must be *lighter* than its page rather than merely a different hue.
     */
    @Test
    fun `the dark palette is genuinely a light-on-dark inversion`() {
        assertTrue(
            "dark semantic colours must be lighter than the dark page",
            luminance(DarkPracticeColors.correct) > luminance(DarkScheme.background),
        )
        assertTrue(
            "light semantic colours must be darker than the light page",
            luminance(LightPracticeColors.correct) < luminance(LightScheme.background),
        )
        assertTrue(
            "the answer colours must not be the same object in both modes",
            luminance(DarkPracticeColors.wrong) > luminance(LightPracticeColors.wrong),
        )
        assertTrue(
            "the dark page must be dark and the light page light",
            luminance(DarkScheme.background) < 0.05 && luminance(LightScheme.background) > 0.5,
        )
    }

    /**
     * Body text must read on its own background, and the dark page must be darker than a surface
     * (so cards are distinguishable) while the light page is *lighter* than one.
     */
    @Test
    fun `page and surface text is readable in both modes`() {
        for ((name, scheme) in listOf("light" to LightScheme, "dark" to DarkScheme)) {
            assertContrast("$name/onBackground", scheme.onBackground, scheme.background)
            assertContrast("$name/onSurface", scheme.onSurface, scheme.surface)
            assertContrast("$name/onSurfaceVariant", scheme.onSurfaceVariant, scheme.surface)
            assertContrast("$name/onSurfaceVariant on the page", scheme.onSurfaceVariant, scheme.background)
            assertContrast("$name/primary on the page", scheme.primary, scheme.background)
            // Chips: primary text on a primary-filled chip.
            assertContrast("$name/onPrimary on primary", scheme.onPrimary, scheme.primary)
        }
    }

    /**
     * A surface has to be *findable* against its page, otherwise a card is invisible in one of
     * the modes. The delta is small by design (Material 3 elevates subtly), so the bar is 1.05:1.
     */
    @Test
    fun `surfaces are distinguishable from the page in both modes`() {
        for ((name, scheme) in listOf("light" to LightScheme, "dark" to DarkScheme)) {
            val ratio = contrast(scheme.surface, scheme.background)
            assertTrue(
                "$name/surface is indistinguishable from the page (%.3f:1)".format(ratio),
                ratio >= 1.05,
            )
        }
    }

    /**
     * Sanity check on the maths itself, against two values from the WCAG specification, so a
     * broken luminance function cannot make every assertion above vacuously pass.
     */
    @Test
    fun `contrast maths matches the specification`() {
        assertTrue(abs(contrast(Color.White, Color.Black) - 21.0) < 0.01)
        assertTrue(abs(contrast(Color.Black, Color.Black) - 1.0) < 0.01)
        // #767676 on white is the classic "exactly 4.5:1" grey.
        assertTrue(abs(contrast(Color(0xFF767676), Color.White) - 4.54) < 0.05)
    }
}
