package protect.yourself.theme

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * THEME-SWITCH-01 (v1.0.72) static pins — the theme selection card moved
 * from the Profile tab to a compact icon + dropdown at the top of the main
 * UI, and the old card must not come back.
 */
class ThemeSwitcherPlacementTest {

    private val mainJava = File(".", "src/main/java")

    private val mainActivity: String by lazy {
        File(mainJava, "protect/yourself/features/mainActivityPage/MainActivity.kt").readText()
    }
    private val profilePage: String by lazy {
        File(mainJava, "protect/yourself/features/profilePage/components/ProfilePage.kt").readText()
    }
    private val switcher: String by lazy {
        File(mainJava, "protect/yourself/theme/ThemeSwitcher.kt").readText()
    }

    @Test
    fun `theme selector card and radio rows are gone from ProfilePage`() {
        assertThat(profilePage).doesNotContain("ThemeOptionRow")
        assertThat(profilePage).doesNotContain("Theme Selector")
        assertThat(profilePage).doesNotContain("ThemePreferences.setThemeMode")
        assertThat(profilePage).doesNotContain("RadioButton")
    }

    @Test
    fun `switcher composable exists with the three modes in a dropdown`() {
        assertThat(switcher).contains("DropdownMenu")
        assertThat(switcher).contains("\"System Default\"")
        assertThat(switcher).contains("\"Dark\"")
        assertThat(switcher).contains("\"Light\"")
        assertThat(switcher).contains("ThemePreferences.setThemeMode")
        assertThat(switcher).contains("IconButton")
    }

    @Test
    fun `main screen hosts a top bar with the theme switcher action`() {
        assertThat(mainActivity).contains("topBar = {")
        assertThat(mainActivity).contains("TopAppBar(")
        assertThat(mainActivity).contains("ThemeSwitcherIcon()")
        assertThat(mainActivity).contains("import protect.yourself.theme.ThemeSwitcherIcon")
    }

    @Test
    fun `switcher icon reflects effective brightness like AppTheme`() {
        // Same mapping as AppTheme: LIGHT→false, DARK→true, else system.
        assertThat(switcher).contains("ThemePreferences.MODE_LIGHT -> false")
        assertThat(switcher).contains("ThemePreferences.MODE_DARK -> true")
        assertThat(switcher).contains("isSystemInDarkTheme()")
    }
}
