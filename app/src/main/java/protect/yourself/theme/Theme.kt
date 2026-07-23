package protect.yourself.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * Protect Yourself dark color scheme — matches original (#061620 dark blue, orange accents).
 */
// DARK-CONTRAST-01: internal (not private) so ColorContrastTest can assert
// WCAG ratios on every role pairing and prevent contrast regressions.
internal val DarkColorScheme = darkColorScheme(
    // DARK-BTN-01 (v1.0.72): primary must work as a CONTENT color — every
    // TextButton/OutlinedButton label+border and dialog action in the app
    // uses it. The near-black brand navy #1F323F was ≈1.3:1 on the dark
    // surface (invisible buttons in Dark Mode), so the interactive pair is
    // a light blue-gray that stays ≈8:1 both ways. The navy survives as
    // primaryContainer (white on it ≈13.2:1) and as the light theme primary.
    primary = DarkPrimaryInteractive,
    onPrimary = DarkOnPrimaryInteractive,
    primaryContainer = DarkPrimary,
    onPrimaryContainer = Color.White,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    secondaryContainer = AccentCyan,
    onSecondaryContainer = Color.Black,
    tertiary = BrandOrange,
    onTertiary = DarkOnTertiaryM3,
    tertiaryContainer = DarkTertiaryContainerM3,
    onTertiaryContainer = DarkOnTertiaryContainerM3,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkSubtitle,
    surfaceTint = AccentCyan,
    inverseSurface = Color.White,
    inverseOnSurface = DarkBackground,
    error = DarkErrorM3,
    onError = DarkOnErrorM3,
    errorContainer = DarkErrorContainerM3,
    onErrorContainer = DarkOnErrorContainerM3,
    outline = DarkOutlineM3,
    outlineVariant = DarkBottomNavDivider,
    scrim = Color.Black,
)

/**
 * Protect Yourself light color scheme — new for rebuild (DayNight support).
 */
internal val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimary,
    onPrimaryContainer = Color.White,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    secondaryContainer = AccentCyan,
    onSecondaryContainer = Color.Black,
    tertiary = BrandOrange,
    onTertiary = LightOnTertiaryM3,
    tertiaryContainer = LightTertiaryContainerM3,
    onTertiaryContainer = LightOnTertiaryContainerM3,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightBackground,
    onSurfaceVariant = LightSubtitle,
    surfaceTint = AccentCyan,
    inverseSurface = DarkBackground,
    inverseOnSurface = Color.White,
    error = LightErrorM3,
    onError = Color.White,
    errorContainer = LightErrorContainerM3,
    onErrorContainer = LightOnErrorContainerM3,
    outline = LightBottomNavInactive,
    outlineVariant = LightBottomNavDivider,
    scrim = Color.Black,
)

/**
 * Protect Yourself Material 3 theme — supports Light/Dark/System preference.
 *
 * The theme mode is stored in SharedPreferences via [ThemePreferences] and
 * can be changed at runtime from the Profile tab. Changes take effect
 * immediately across the entire app.
 */
@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    val themeMode by ThemePreferences.themeMode.collectAsState()
    val isDark = when (themeMode) {
        ThemePreferences.MODE_LIGHT -> false
        ThemePreferences.MODE_DARK -> true
        else -> isSystemInDarkTheme() // System Default
    }
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
