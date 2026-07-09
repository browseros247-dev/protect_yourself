package protect.yourself.features.blockerPage.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.features.blockerPage.BlockerPageNavigation
import protect.yourself.features.blockerPage.BlockerPageViewModel
import protect.yourself.features.blockerPage.data.SettingPageItemModel
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.selectAppPage.components.SelectAppPage
import protect.yourself.theme.BrandOrange

@Composable
fun BlockerPageHome() {
    val context = LocalContext.current
    val viewModel: BlockerPageViewModel = viewModel(
        factory = BlockerPageViewModel.factory(AppDatabase.getInstance(context))
    )
    val state by viewModel.state.collectAsState()

    // Navigation state — which sub-page to show (null = main list)
    var currentPage: SubPage? by remember { mutableStateOf(null) }

    // Collect navigation events
    LaunchedEffect(Unit) {
        viewModel.navigation.collect { nav ->
            when (nav) {
                is BlockerPageNavigation.OpenSelectAppPage -> {
                    currentPage = SubPage.SelectApp(nav.title, nav.identifier)
                }
                is BlockerPageNavigation.OpenAccessibilitySettings -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                is BlockerPageNavigation.OpenOverlaySettings -> {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                }
                is BlockerPageNavigation.OpenUrl -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(nav.url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                    } catch (_: Throwable) {
                        // No app to handle URL
                    }
                }
                is BlockerPageNavigation.ShowToast -> {
                    android.widget.Toast.makeText(context, nav.message, android.widget.Toast.LENGTH_SHORT).show()
                }
                is BlockerPageNavigation.OpenKeywordManager -> {
                    currentPage = SubPage.KeywordManager
                }
                is BlockerPageNavigation.OpenAppLockSetup -> {
                    currentPage = SubPage.AppLockSetup
                }
                is BlockerPageNavigation.OpenRequestHistory -> {
                    currentPage = SubPage.RequestHistory
                }
                is BlockerPageNavigation.OpenFaq -> {
                    currentPage = SubPage.Faq
                }
                is BlockerPageNavigation.OpenStopMe -> {
                    currentPage = SubPage.StopMe
                }
            }
        }
    }

    // Render sub-page or main list
    when (val page = currentPage) {
        is SubPage.SelectApp -> {
            SelectAppPage(
                identifier = page.identifier,
                title = page.title,
                onBack = { currentPage = null }
            )
        }
        SubPage.KeywordManager -> {
            SimpleSubPage(title = "Keyword Manager") { currentPage = null }
        }
        SubPage.AppLockSetup -> {
            protect.yourself.features.appPasswordPage.AppLockSetupPage(onBack = { currentPage = null })
        }
        SubPage.RequestHistory -> {
            SimpleSubPage(title = "Request History") { currentPage = null }
        }
        SubPage.Faq -> {
            SimpleSubPage(title = "FAQ — Keep App Running") { currentPage = null }
        }
        SubPage.StopMe -> {
            SimpleSubPage(title = "Stop Me") { currentPage = null }
        }
        null -> {
            // Main settings list
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = BrandOrange)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Accessibility warning banner
                    item { AccessibilityWarningCard(context) }

                    items(state.settingItems) { item ->
                        when {
                            item.isSection -> SectionHeader(item)
                            item.switchKey != null -> SwitchRow(item) { viewModel.toggleSwitch(it) }
                            item.actionLabel != null -> ActionRow(item) { viewModel.onActionClick(it) }
                            else -> InfoRow(item)
                        }
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun AccessibilityWarningCard(context: android.content.Context) {
    val isAccessibilityEnabled = remember { mutableStateOf(MyAccessibilityService.isEnabled(context)) }

    // Check on each recomposition (cheap — reads system setting)
    if (!isAccessibilityEnabled.value) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp)
                .clickable {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "⚠️ Accessibility not enabled",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Blocking features are disabled. Tap here to enable accessibility permission.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(item: SettingPageItemModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp)
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = BrandOrange,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun SwitchRow(
    item: SettingPageItemModel,
    onToggle: (SettingPageItemModel) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                if (!item.info.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.size(16.dp))
            Switch(
                checked = item.switchValue,
                onCheckedChange = { onToggle(item) }
            )
        }
    }
}

@Composable
private fun ActionRow(
    item: SettingPageItemModel,
    onClick: (SettingPageItemModel) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = { onClick(item) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                if (!item.info.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.info,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.size(16.dp))
            TextButton(onClick = { onClick(item) }) {
                Text(
                    text = item.actionLabel ?: "",
                    color = BrandOrange,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun InfoRow(item: SettingPageItemModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            if (!item.info.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SimpleSubPage(title: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        TextButton(onClick = onBack) {
            Text("← Back", color = BrandOrange)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "This feature is being implemented. Check back soon.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

sealed class SubPage {
    data class SelectApp(val title: String, val identifier: SelectedAppListIdentifier) : SubPage()
    data object KeywordManager : SubPage()
    data object AppLockSetup : SubPage()
    data object RequestHistory : SubPage()
    data object Faq : SubPage()
    data object StopMe : SubPage()
}
