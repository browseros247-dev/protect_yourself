package protect.yourself.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Theme preference: 0 = Light, 1 = Dark, 2 = System Default
 */
object ThemePreferences {
    private const val PREFS_NAME = "protect_yourself_theme"
    private const val KEY_THEME = "theme_mode"

    private val _themeMode = MutableStateFlow(2) // default: System Default
    val themeMode: StateFlow<Int> = _themeMode

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, 0)
        _themeMode.value = prefs.getInt(KEY_THEME, 2)
    }

    fun setThemeMode(context: Context, mode: Int) {
        _themeMode.value = mode
        context.applicationContext.getSharedPreferences(PREFS_NAME, 0)
            .edit().putInt(KEY_THEME, mode).apply()
    }

    const val MODE_LIGHT = 0
    const val MODE_DARK = 1
    const val MODE_SYSTEM = 2

    fun modeLabel(mode: Int): String = when (mode) {
        MODE_LIGHT -> "Light"
        MODE_DARK -> "Dark"
        else -> "System Default"
    }
}
