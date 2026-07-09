package protect.yourself.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * NopoX dark color scheme — matches original (#061620 dark blue, orange accents).
 */
private val NopoXDarkColorScheme = darkColorScheme(
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
 * NopoX light color scheme — new for rebuild (DayNight support).
 */
private val NopoXLightColorScheme = lightColorScheme(
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
 * NopoX Material 3 theme — DayNight (follows system).
 *
 * Per user choice: theme follows system dark/light setting.
 * Dark theme is primary (matches original); light theme is new for rebuild.
 */
@Composable
fun NopoXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) NopoXDarkColorScheme else NopoXLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NopoXTypography,
        content = content
    )
}

/**
 * Brand orange gradient brush — used for primary CTA buttons + Stop Me widget.
 */
val OrangeGradientBrush: androidx.compose.ui.graphics.Brush
    @Composable
    get() = androidx.compose.ui.graphics.Brush.verticalGradient(
        colors = listOf(BrandOrangeLight, BrandOrange)
    )

/**
 * Bottom nav colors (used by Compose bottom nav).
 */
data class BottomNavColors(
    val active: Color,
    val inactive: Color,
    val divider: Color,
)

@Composable
fun bottomNavColors(): BottomNavColors {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        BottomNavColors(
            active = DarkBottomNavActive,
            inactive = DarkBottomNavInactive,
            divider = DarkBottomNavDivider
        )
    } else {
        BottomNavColors(
            active = LightBottomNavActive,
            inactive = LightBottomNavInactive,
            divider = LightBottomNavDivider
        )
    }
}
