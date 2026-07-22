package protect.yourself.theme

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Shared colors for filled brand (orange) action buttons — DARK-BTN-01 (v1.0.72).
 *
 * Before v1.0.72, ~12 call sites spelled `ButtonDefaults.buttonColors(
 * containerColor = BrandOrange)` by hand. BrandOrange #FF7100 with the
 * (near-)white label M3 resolves for it reaches only ≈2.8:1 — below even the
 * WCAG 3:1 large-text floor — which made primary actions (Accept & Continue,
 * Continue to App, Unlock, Next, Confirm & Save, Save, Save Schedule,
 * Export/Import …) hard to read, worst in Dark Mode.
 *
 * [brandButtonColors] is the single replacement:
 *  - container  #B85700 ([BrandOrangeButton])  — orange hue, white label ≈4.77:1
 *  - content    pure white                      — unchanged app look & feel
 *  - disabled   same hue @ 40% alpha + white @ 60% — still hue-recognizable,
 *    and replaces the previous `BrandOrange.copy(alpha = 0.3f)` one-offs.
 *
 * The ratios are pinned in `ColorContrastTest` so they cannot silently
 * regress. Do NOT reintroduce raw `containerColor = BrandOrange` on filled
 * buttons — the static regression test `DarkModeButtonsTest` fails the build.
 */
@Composable
fun brandButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = BrandOrangeButton,
    contentColor = Color.White,
    disabledContainerColor = BrandOrangeButton.copy(alpha = 0.4f),
    disabledContentColor = Color.White.copy(alpha = 0.6f)
)
