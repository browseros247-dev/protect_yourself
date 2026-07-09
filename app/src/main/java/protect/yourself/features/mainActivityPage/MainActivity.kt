package protect.yourself.features.mainActivityPage

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.features.appPasswordPage.AppLockManager
import protect.yourself.features.appPasswordPage.AppLockScreen
import protect.yourself.features.blockerPage.components.BlockerPageHome
import protect.yourself.features.mainActivityPage.components.AboutPage
import protect.yourself.features.mainActivityPage.repository.MainPageScreen
import protect.yourself.features.profilePage.components.ProfilePage
import protect.yourself.features.streakPage.components.StreakPage
import protect.yourself.theme.AppTheme
import timber.log.Timber

/**
 * Main launcher activity.
 *
 * Extends FragmentActivity for BiometricPrompt support (App Lock).
 *
 * On launch:
 *  1. Checks if app lock is enabled (PIN/password/pattern)
 *  2. If enabled, shows AppLockScreen
 *  3. After successful unlock, shows main app UI
 */
class MainActivity : FragmentActivity() {

    private var isLocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if app lock is enabled
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val lockManager = AppLockManager.getInstance(this@MainActivity)
                val lockEnabled = lockManager.isLockEnabled()
                isLocked = lockEnabled
                Timber.i("App lock enabled: $lockEnabled")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to check app lock state")
                isLocked = false
            }
            runOnUiThread { initPageUi() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun initPageUi() {
        setContent {
            AppTheme {
                if (isLocked) {
                    // Show lock screen
                    AppLockScreen(onUnlocked = {
                        isLocked = false
                        // Recompose to show main UI
                        initPageUi()
                    })
                } else {
                    MainScreen()
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
    }
}

@Composable
private fun MainScreen() {
    var selectedTab by remember { mutableStateOf(MainPageScreen.Home) }

    Scaffold(
        bottomBar = { AppBottomBar(selectedTab) { selectedTab = it } }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                MainPageScreen.Home -> BlockerPageHome()
                MainPageScreen.Streak -> StreakPage()
                MainPageScreen.About -> AboutPage()
                MainPageScreen.Profile -> ProfilePage()
            }
        }
    }
}

@Composable
private fun AppBottomBar(
    selected: MainPageScreen,
    onSelect: (MainPageScreen) -> Unit
) {
    NavigationBar {
        MainPageScreen.all.forEach { screen ->
            NavigationBarItem(
                selected = selected == screen,
                onClick = { onSelect(screen) },
                icon = {
                    Icon(
                        imageVector = screen.vectorIcon(),
                        contentDescription = stringResource(screen.resourceId)
                    )
                },
                label = { Text(stringResource(screen.resourceId)) }
            )
        }
    }
}

private fun MainPageScreen.vectorIcon(): ImageVector = when (this) {
    MainPageScreen.Home -> Icons.Filled.Shield
    MainPageScreen.Streak -> Icons.Filled.LocalFireDepartment
    MainPageScreen.About -> Icons.Filled.Info
    MainPageScreen.Profile -> Icons.Filled.Person
}
