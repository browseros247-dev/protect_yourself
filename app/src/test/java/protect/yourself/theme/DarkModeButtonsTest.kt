package protect.yourself.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * DARK-BTN-01 (v1.0.72) static regression pins — the Dark Mode button fixes
 * must not creep back (same style as OverlayDependencyRemovedTest).
 *
 *  1. No filled button may use raw `containerColor = BrandOrange` again
 *     — filled brand actions go through `brandButtonColors()`.
 *  2. The dark scheme must use the interactive primary pair — `primary` is
 *     the CONTENT color of every TextButton/OutlinedButton label & border;
 *     the near-black navy made all dialog/onboarding buttons invisible.
 *  3. New color tokens exist with the exact audited values.
 */
class DarkModeButtonsTest {

    private val moduleDir = File(".")
    private val mainJava = File(moduleDir, "src/main/java")

    private fun allKotlinSources(): List<File> =
        mainJava.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun `no filled button uses raw BrandOrange container anymore`() {
        // AppButtons.kt legitimately quotes the pattern in its KDoc
        // ("do NOT reintroduce…") — it is the replacement, not a caller.
        val offenders = allKotlinSources()
            .filter { it.name != "AppButtons.kt" }
            .filter { f ->
                Regex("containerColor\\s*=\\s*BrandOrange\\s*[,)]").containsMatchIn(f.readText())
            }.map { it.name }
        assertWithMessage(
            "filled-button BrandOrange containers reintroduced in: $offenders — use brandButtonColors()"
        ).that(offenders).isEmpty()
    }

    @Test
    fun `AppButtons provides the shared brandButtonColors helper`() {
        val src = File(mainJava, "protect/yourself/theme/AppButtons.kt")
        assertWithMessage("theme/AppButtons.kt must exist").that(src.exists()).isTrue()
        val text = src.readText()
        assertThat(text).contains("fun brandButtonColors()")
        assertThat(text).contains("containerColor = BrandOrangeButton")
        assertThat(text).contains("contentColor = Color.White")
    }

    @Test
    fun `dark theme uses the interactive primary pair, not near-black navy`() {
        val themeSrc = File(mainJava, "protect/yourself/theme/Theme.kt").readText()
        val darkBlock = themeSrc.substringAfter("DarkColorScheme = darkColorScheme(", "")
        assertWithMessage("DarkColorScheme block not found").that(darkBlock).isNotEmpty()
        val darkUpToLight = darkBlock.substringBefore("LightColorScheme")
        assertThat(darkUpToLight).contains("primary = DarkPrimaryInteractive")
        assertThat(darkUpToLight).contains("onPrimary = DarkOnPrimaryInteractive")
        // Navy survives only as primaryContainer (container role) in dark.
        assertThat(darkUpToLight).doesNotContain("primary = DarkPrimary,\n")
    }

    @Test
    fun `contrast tokens exist with audited hex values`() {
        val colorSrc = File(mainJava, "protect/yourself/theme/Color.kt").readText()
        assertThat(colorSrc).contains("val BrandOrangeButton = Color(0xFFB85700)")
        assertThat(colorSrc).contains("val DarkPrimaryInteractive = Color(0xFF9DB8C6)")
        assertThat(colorSrc).contains("val DarkOnPrimaryInteractive = Color(0xFF0A2029)")
        assertThat(colorSrc).contains("val StatusSuccess = Color(0xFF2E7D32)")
        assertThat(colorSrc).contains("val StatusWarning = Color(0xFFE65100)")
    }
}
