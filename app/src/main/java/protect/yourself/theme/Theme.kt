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
private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimary,
    onPrimaryContainer = Color.White,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    secondaryContainer = AccentCyan,
    onSecondaryContainer = Color.Black,
    tertiary = BrandOrange,
    onTertiary = Color.White,
    tertiaryContainer = BrandOrangeLight,
    onTertiaryContainer = Color.White,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = DarkSubtitle,
    surfaceTint = AccentCyan,
    inverseSurface = Color.White,
    inverseOnSurface = DarkBackground,
    error = DarkError,
    onError = Color.White,
    errorContainer = DarkError,
    onErrorContainer = Color.White,
    outline = DarkBottomNavInactive,
    outlineVariant = DarkBottomNavDivider,
    scrim = Color.Black,
)

/**
 * Protect Yourself light color scheme — new for rebuild (DayNight support).
 */
private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimary,
    onPrimaryContainer = Color.White,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    secondaryContainer = AccentCyan,
    onSecondaryContainer = Color.Black,
    tertiary = BrandOrange,
    onTertiary = Color.White,
    tertiaryContainer = BrandOrangeLight,
    onTertiaryContainer = Color.White,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightBackground,
    onSurfaceVariant = LightSubtitle,
    surfaceTint = AccentCyan,
    inverseSurface = DarkBackground,
    inverseOnSurface = Color.White,
    error = LightError,
    onError = Color.White,
    errorContainer = LightError,
    onErrorContainer = Color.White,
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
