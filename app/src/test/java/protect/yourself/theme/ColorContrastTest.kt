package protect.yourself.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.pow

/**
 * WCAG 2.1 contrast regression tests for the Protect Yourself color schemes
 * (DARK-CONTRAST-01, v1.0.69).
 *
 * ## Why this test exists
 *
 * The reported Dark Mode bug — "some settings become difficult to read,
 * black text on black background" — traced back to M3 role pairings that
 * paired the brand error orange (#FF5722) and brand light orange (#FF9900)
 * with WHITE foreground content:
 *
 *   error #FF5722          / onError White          → ≈3.0:1  (FAIL)
 *   errorContainer #FF5722 / onErrorContainer White → ≈3.0:1  (FAIL)
 *   tertiaryContainer #FF9900 / onTertiaryContainer White → ≈2.6:1 (FAIL)
 *   onTertiary White on tertiary #FF7100            → ≈2.8:1  (FAIL)
 *   outline #4D7389 on surface #151F26              → ≈3.3:1  (borderline)
 *   (light) onSurfaceVariant #6B7785 on surfaceVariant #F5F7FA → ≈4.25:1 (FAIL)
 *
 * Consumers render NORMAL-SIZE text in these roles (onboarding "⚠️ Important"
 * card, BlockerPageHome status cards, schedule lists, crash-log severity
 * labels, app-lock buttons), so AA body text needs ≥ 4.5:1.
 *
 * The v1.0.69 schemes adopt the canonical M3 baseline pairings for
 * error/errorContainer/tertiaryContainer (≥ 4.5:1, most ≥ 7:1). This test
 * pins every text-bearing role pair in BOTH schemes so the contrast can
 * never silently regress again.
 *
 * Pure JVM test — no Android runtime needed (Compose Color is a value class).
 */
class ColorContrastTest {

    // ---- WCAG 2.1 math ------------------------------------------------------

    /** sRGB electro-optical transfer function (per WCAG 2.1 definition). */
    private fun linearize(channel: Float): Double {
        val c = channel.toDouble().coerceIn(0.0, 1.0)
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double {
        val r = linearize(color.red)
        val g = linearize(color.green)
        val b = linearize(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lf = relativeLuminance(foreground)
        val lb = relativeLuminance(background)
        val hi = maxOf(lf, lb)
        val lo = minOf(lf, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    // ---- pair inventory -----------------------------------------------------

    private data class RolePair(val name: String, val fg: Color, val bg: Color)

    /** All text/content-bearing role pairings (WCAG AA normal text → ≥ 4.5:1). */
    private fun textPairs(s: ColorScheme) = listOf(
        RolePair("onPrimary/primary", s.onPrimary, s.primary),
        RolePair("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
        RolePair("onSecondary/secondary", s.onSecondary, s.secondary),
        RolePair("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
        RolePair("onTertiary/tertiary", s.onTertiary, s.tertiary),
        RolePair("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
        RolePair("onBackground/background", s.onBackground, s.background),
        RolePair("onSurface/surface", s.onSurface, s.surface),
        RolePair("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
        RolePair("onError/error", s.onError, s.error),
        RolePair("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
        RolePair("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface),
    )

    // ---- DARK scheme --------------------------------------------------------

    @Test
    fun `dark scheme - every text role pair meets WCAG AA 4_5_1`() {
        for (p in textPairs(DarkColorScheme)) {
            val ratio = contrastRatio(p.fg, p.bg)
            assertWithMessage(
                "DARK ${p.name}: %.2f:1 < 4.5:1 (fg=%s bg=%s)".format(ratio, p.fg, p.bg)
            ).that(ratio).isAtLeast(4.5)
        }
    }

    @Test
    fun `dark scheme - outline on surface meets WCAG non-text 3_1`() {
        val ratio = contrastRatio(DarkColorScheme.outline, DarkColorScheme.surface)
        assertWithMessage("DARK outline/surface: %.2f:1 < 3.0:1".format(ratio))
            .that(ratio).isAtLeast(3.0)
    }

    @Test
    fun `dark scheme - pins the v1_0_69 contrast-corrected role colors`() {
        // If someone reverts these to the brand oranges, the ratio tests above
        // will fail — but pinning the exact values documents the intended
        // canonical M3 pairings and produces a clearer failure.
        assertThat(DarkColorScheme.error).isEqualTo(Color(0xFFFFB4A9))
        assertThat(DarkColorScheme.onError).isEqualTo(Color(0xFF690005))
        assertThat(DarkColorScheme.errorContainer).isEqualTo(Color(0xFF93000A))
        assertThat(DarkColorScheme.onErrorContainer).isEqualTo(Color(0xFFFFDAD6))
        assertThat(DarkColorScheme.tertiaryContainer).isEqualTo(Color(0xFF6F3A00))
        assertThat(DarkColorScheme.onTertiaryContainer).isEqualTo(Color(0xFFFFDCC2))
        assertThat(DarkColorScheme.onTertiary).isEqualTo(Color(0xFF3B2700))
        assertThat(DarkColorScheme.outline).isEqualTo(Color(0xFF5D7E93))
    }

    // ---- LIGHT scheme -------------------------------------------------------

    @Test
    fun `light scheme - every text role pair meets WCAG AA 4_5_1`() {
        for (p in textPairs(LightColorScheme)) {
            val ratio = contrastRatio(p.fg, p.bg)
            assertWithMessage(
                "LIGHT ${p.name}: %.2f:1 < 4.5:1 (fg=%s bg=%s)".format(ratio, p.fg, p.bg)
            ).that(ratio).isAtLeast(4.5)
        }
    }

    @Test
    fun `light scheme - outline on surface meets WCAG non-text 3_1`() {
        val ratio = contrastRatio(LightColorScheme.outline, LightColorScheme.surface)
        assertWithMessage("LIGHT outline/surface: %.2f:1 < 3.0:1".format(ratio))
            .that(ratio).isAtLeast(3.0)
    }

    @Test
    fun `light scheme - pins the v1_0_69 contrast-corrected role colors`() {
        assertThat(LightColorScheme.error).isEqualTo(Color(0xFFBA1A1A))
        assertThat(LightColorScheme.onError).isEqualTo(Color.White)
        assertThat(LightColorScheme.errorContainer).isEqualTo(Color(0xFFFFDAD6))
        assertThat(LightColorScheme.onErrorContainer).isEqualTo(Color(0xFF410002))
        assertThat(LightColorScheme.tertiaryContainer).isEqualTo(Color(0xFFFFE0C8))
        assertThat(LightColorScheme.onTertiaryContainer).isEqualTo(Color(0xFF311300))
    }

    // ---- documented brand exception -----------------------------------------

    /**
     * The brand-gradient buttons (#FF7100 → #FF9900) keep bold WHITE labels —
     * the app's visual signature. M3 `labelLarge` (14sp bold) qualifies as
     * WCAG "large text" (3:1 required; #FF7100+white ≈ 2.76:1 and the #FF9900
     * gradient end ≈ 2.14:1 — a KNOWN, pre-existing deviation documented in
     * docs/A11Y_PERSIST_DARKMODE_REPORT_v1.0.69.md; fixing it is a brand
     * decision, out of scope for the settings-readability bug).
     *
     * This test pins the #FF7100 floor at 2.7:1 as a tripwire so the pair can
     * never silently degrade further — any darkening of the brand orange or
     * lightening of the label color fails loudly here.
     */
    @Test
    fun `documented exception - brand orange with white bold label stays above floor`() {
        val onBrandOrange = contrastRatio(Color.White, BrandOrange)
        assertWithMessage("white on BrandOrange #FF7100: %.2f:1".format(onBrandOrange))
            .that(onBrandOrange).isAtLeast(2.7)
    }
}
