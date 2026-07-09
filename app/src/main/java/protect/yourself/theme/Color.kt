package protect.yourself.theme

import androidx.compose.ui.graphics.Color

/**
 * NopoX color palette — ported from original `res/values/colors.xml`.
 *
 * Dark theme is the primary theme (matches original exactly).
 * Light theme is new for the rebuild (DayNight support per user choice).
 *
 * Original colors are 8-digit hex (#AARRGGBB). Compose `Color` takes 0xAARRGGBBUL.
 */

// === Brand colors ===
val BrandOrange = Color(0xFFFF7100)
val BrandOrangeLight = Color(0xFFFF9900)
val AccentCyan = Color(0xFF3ACFFE)

// === Dark theme colors (matches original) ===
val DarkPrimary = Color(0xFF1F323F)
val DarkBackground = Color(0xFF061620)
val DarkSurface = Color(0xFF151F26)
val DarkOnPrimary = Color.White
val DarkOnBackground = Color.White
val DarkOnSurface = Color.White
val DarkBottomNavActive = Color(0xFF38D0FE)
val DarkBottomNavInactive = Color(0xFF4D7389)
val DarkBottomNavDivider = Color(0xFF052233)
val DarkSubtitle = Color(0xFFB7BABD)
val DarkError = Color(0xFFFF5722)

// === Light theme colors (new — follows system per user choice) ===
val LightPrimary = Color(0xFF1F323F)
val LightBackground = Color(0xFFF5F7FA)
val LightSurface = Color(0xFFFFFFFF)
val LightOnPrimary = Color.White
val LightOnBackground = Color(0xFF061620)
val LightOnSurface = Color(0xFF061620)
val LightBottomNavActive = Color(0xFF1FA8D9)
val LightBottomNavInactive = Color(0xFF7A8B96)
val LightBottomNavDivider = Color(0xFFE0E5EA)
val LightSubtitle = Color(0xFF6B7785)
val LightError = Color(0xFFFF5722)

// === Splash ===
val SplashBackground = Color(0xFF152B35)
