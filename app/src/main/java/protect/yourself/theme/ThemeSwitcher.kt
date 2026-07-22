package protect.yourself.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Compact theme switcher shown at the top of the main UI (THEME-SWITCH-01,
 * v1.0.72).
 *
 * Replaces the old "Theme" selection card on the Profile tab: a single
 * icon button (sun when the effective theme is light, moon when dark) whose
 * tap opens a dropdown with the three modes — System Default, Dark, Light.
 *
 * Behaviour is identical to the removed card: selection is persisted via
 * [ThemePreferences.setThemeMode] (SharedPreferences) and applies instantly
 * app-wide because [AppTheme] collects the same StateFlow.
 */
@Composable
fun ThemeSwitcherIcon() {
    val context = LocalContext.current
    val themeMode by ThemePreferences.themeMode.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    // Effective brightness decides which affordance icon to show on the
    // trigger (sun in light UI, moon in dark UI) — System Default follows
    // the platform, exactly like AppTheme itself does.
    val isDark = when (themeMode) {
        ThemePreferences.MODE_LIGHT -> false
        ThemePreferences.MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = if (isDark) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = "Theme: ${ThemePreferences.modeLabel(themeMode)}. Tap to change.",
                // After DARK-BTN-01 the primary role is a high-contrast content
                // color in BOTH schemes (≈8:1 dark / ≈13:1 light on surface).
                tint = MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            ThemeModeMenuItem(
                label = "System Default",
                icon = Icons.Filled.PhoneAndroid,
                mode = ThemePreferences.MODE_SYSTEM,
                currentMode = themeMode,
                onSelect = { selected ->
                    ThemePreferences.setThemeMode(context, selected)
                    expanded = false
                }
            )
            ThemeModeMenuItem(
                label = "Dark",
                icon = Icons.Filled.DarkMode,
                mode = ThemePreferences.MODE_DARK,
                currentMode = themeMode,
                onSelect = { selected ->
                    ThemePreferences.setThemeMode(context, selected)
                    expanded = false
                }
            )
            ThemeModeMenuItem(
                label = "Light",
                icon = Icons.Filled.LightMode,
                mode = ThemePreferences.MODE_LIGHT,
                currentMode = themeMode,
                onSelect = { selected ->
                    ThemePreferences.setThemeMode(context, selected)
                    expanded = false
                }
            )
        }
    }
}

@Composable
private fun ThemeModeMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    mode: Int,
    currentMode: Int,
    onSelect: (Int) -> Unit
) {
    val selected = currentMode == mode
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.Bold
                else androidx.compose.ui.text.font.FontWeight.Normal
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else null,
        onClick = { onSelect(mode) }
    )
}
