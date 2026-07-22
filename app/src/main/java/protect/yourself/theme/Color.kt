package protect.yourself.theme

import androidx.compose.ui.graphics.Color

/**
 * Protect Yourself color palette — ported from original `res/values/colors.xml`.
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
val LightBottomNavInactive = Color(0xFF7A8B96)
val LightBottomNavDivider = Color(0xFFE0E5EA)
// v1.0.69: #6B7785 → #64707E. Same hue ramp, but the old value reached only
// ≈4.25:1 on the light surface/background (#F5F7FA) — under WCAG AA 4.5:1
// for body-size text. #64707E reaches ≈4.7:1 (and ≈5.1:1 on white cards).
val LightSubtitle = Color(0xFF64707E)
val LightError = Color(0xFFFF5722)

// === M3 contrast-corrected role colors (v1.0.69, DARK-CONTRAST-01) ===
//
// The legacy brand error orange (#FF5722, DarkError/LightError above) fails WCAG
// contrast when paired with white text/icons in the `error`/`errorContainer`/
// `tertiaryContainer` M3 roles (≈2.6:1–3.0:1, "black text on black background"
// class of failures reported in Dark Mode). The roles below follow the canonical
// Material 3 baseline pairings, each reaching ≥4.5:1 against its on* counterpart:
//
//   Dark  error #FFB4A9 / onError #690005               → 7.7:1
//   Dark  errorContainer #93000A / onErrorContainer #FFDAD6 → 7.2:1
//   Light error #BA1A1A / onError #FFFFFF               → 6.5:1
//   Light errorContainer #FFDAD6 / onErrorContainer #410002 → 13.3:1
//   Dark  tertiaryContainer #6F3A00 / onTertiaryContainer #FFDCC2 → 7.1:1
//   Light tertiaryContainer #FFE0C8 / onTertiaryContainer #311300 → 13.7:1
//
// `DarkError`/`LightError` remain for brand-gradient/highlight use only; they are
// no longer referenced by the color schemes.
val DarkErrorM3 = Color(0xFFFFB4A9)
val DarkOnErrorM3 = Color(0xFF690005)
val DarkErrorContainerM3 = Color(0xFF93000A)
val DarkOnErrorContainerM3 = Color(0xFFFFDAD6)
val DarkOnTertiaryM3 = Color(0xFF3B2700)
val DarkTertiaryContainerM3 = Color(0xFF6F3A00)
val DarkOnTertiaryContainerM3 = Color(0xFFFFDCC2)
val DarkOutlineM3 = Color(0xFF5D7E93)

val LightErrorM3 = Color(0xFFBA1A1A)
val LightErrorContainerM3 = Color(0xFFFFDAD6)
val LightOnErrorContainerM3 = Color(0xFF410002)
val LightOnTertiaryM3 = Color(0xFF311300)
val LightTertiaryContainerM3 = Color(0xFFFFE0C8)
val LightOnTertiaryContainerM3 = Color(0xFF311300)

// === Interactive/button role colors (v1.0.72, DARK-BTN-01) ===
//
// Two more "black text on black background" failure classes were reported in
// Dark Mode — this time on BUTTONS (Allow / Disallow / Export / Import /
// Open / Close / Cancel …):
//
//  1. The dark scheme used the near-black brand navy [DarkPrimary] #1F323F
//     as M3 `primary`. M3 consumes `primary` as the CONTENT color of every
//     TextButton / OutlinedButton label and border — #1F323F on the dark
//     surface #151F26 reaches only ≈1.3:1, i.e. effectively invisible.
//     [DarkPrimaryInteractive] #9DB8C6 keeps the blue-gray hue identity but
//     reaches ≈8.1:1 on the dark surface and ≈8.8:1 on the dark background.
//     Paired [DarkOnPrimaryInteractive] #0A2029 reaches ≈8.1:1 on it, so the
//     filled-button default (container=primary / content=onPrimary) also
//     stays ≥4.5:1. The near-black navy survives as `primaryContainer`
//     (container role: white on it ≈13.2:1) and as the light scheme primary.
//
//  2. Filled brand buttons used [BrandOrange] #FF7100 with (near-)white
//     labels — ≈2.8:1, below even WCAG large-text 3:1. [BrandOrangeButton]
//     #B85700 keeps the orange hue but reaches ≈4.77:1 with white text, so
//     every filled brand button meets WCAG AA for normal text. It is used
//     exclusively via `brandButtonColors()` (see theme/AppButtons.kt), which
//     also sets legible disabled colors (#B85700 @ 40% + white @ 60%).
val BrandOrangeButton = Color(0xFFB85700)
val DarkPrimaryInteractive = Color(0xFF9DB8C6)
val DarkOnPrimaryInteractive = Color(0xFF0A2029)

// === Semantic status pairs (v1.0.72, UI-CONSIST-01) ===
//
// Status indicators that need green=ok / amber=warn semantics independent
// of the M3 scheme (e.g. the Reliable Accessibility status card). Paired
// with pure-white icons: white on #2E7D32 ≈ 4.9:1, white on #E65100 ≈ 3.6:1
// (icons are non-text content; WCAG non-text minimum is 3:1). The previous
// hardcoded pair #4CAF50/#FF9800 with white reached only ≈2.5:1 / ≈2.2:1.
val StatusSuccess = Color(0xFF2E7D32)
val StatusWarning = Color(0xFFE65100)

