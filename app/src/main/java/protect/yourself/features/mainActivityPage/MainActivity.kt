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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import protect.yourself.R
import protect.yourself.features.blockerPage.components.BlockerPageHome
import protect.yourself.features.mainActivityPage.repository.MainPageScreen
import protect.yourself.theme.NopoXTheme

/**
 * Main launcher activity.
 *
 * Phase 1: Skeleton with bottom nav (4 tabs: NopoX, Streak, About, Profile).
 * Phase 4+: Full Compose UI for each tab.
 *
 * Original behavior:
 *  - launchMode = singleTask (declared in manifest)
 *  - showOnLockScreen = true (declared in manifest)
 *  - onCreate flow: initGoogleAdMobForAds → initPageForLockScreen → initReceivers →
 *    initPeriodicWorkManager → initPageUi → requestPostNotification
 *
 * Rebuild:
 *  - REMOVED: initGoogleAdMobForAds (ads stripped)
 *  - Phase 6: initReceivers, initPeriodicWorkManager
 *  - Phase 6: requestPostNotification
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO Phase 6: initReceivers()
        // TODO Phase 6: initPeriodicWorkManager()
        // TODO Phase 6: requestPostNotification()
        initPageUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Phase 5: handle deep links (accountability partner approval)
        // Phase 5: handle widget tab-open extras (EXTRA_OPEN_TAB)
    }

    private fun initPageUi() {
        setContent {
            NopoXTheme {
                MainScreen()
            }
        }
    }

    companion object {
        /** Intent extra: name of the bottom-nav tab to open (e.g. "Streak"). */
        const val EXTRA_OPEN_TAB = "extra_open_tab"
    }
}

@Composable
private fun MainScreen() {
    var selectedTab by remember { mutableStateOf(MainPageScreen.NopoX) }

    Scaffold(
        bottomBar = { NopoXBottomBar(selectedTab) { selectedTab = it } }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                is MainPageScreen.NopoX -> BlockerPageHome()
                is MainPageScreen.Streak -> PlaceholderScreen("Streak (Phase 5)")
                is MainPageScreen.About -> PlaceholderScreen("About (Phase 5)")
                is MainPageScreen.Profile -> PlaceholderScreen("Profile (Phase 5)")
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(label: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(label)
    }
}

@Composable
private fun NopoXBottomBar(
    selected: MainPageScreen,
    onSelect: (MainPageScreen) -> Unit
) {
    NavigationBar {
        MainPageScreen.all.forEach { screen ->
            NavigationBarItem(
                selected = selected.route == screen.route,
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
    is MainPageScreen.NopoX -> Icons.Filled.Shield
    is MainPageScreen.Streak -> Icons.Filled.LocalFireDepartment
    is MainPageScreen.About -> Icons.Filled.Info
    is MainPageScreen.Profile -> Icons.Filled.Person
}
