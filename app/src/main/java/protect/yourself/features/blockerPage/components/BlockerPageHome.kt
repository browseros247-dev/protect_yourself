package protect.yourself.features.blockerPage.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.features.blockerPage.BlockerPageNavigation
import protect.yourself.features.blockerPage.BlockerPageViewModel
import protect.yourself.features.blockerPage.data.SettingPageItemModel
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.service.MyVpnService
import protect.yourself.features.selectAppPage.components.SelectAppPage
import protect.yourself.theme.BrandOrange

@Composable
fun BlockerPageHome() {
    val context = LocalContext.current
    val viewModel: BlockerPageViewModel = viewModel(
        factory = BlockerPageViewModel.factory(AppDatabase.getInstance(context))
    )
    val state by viewModel.state.collectAsState()

    var currentPage: SubPage? by remember { mutableStateOf(null) }
    var editDialog: EditDialogData? by remember { mutableStateOf(null) }
    var numberDialog: NumberDialogData? by remember { mutableStateOf(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            MyVpnService.start(context)
            viewModel.onVpnPermissionGranted()
        }
    }

    /**
     * Device Admin activation launcher.
     *
     * The Device Admin screen doesn't return a result code we can rely on
     * (some OEMs return RESULT_OK even when the user cancels). Instead, we
     * check DeviceAdminManager.isActive() when the user returns to the app.
     */
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val adminManager = protect.yourself.features.protectedApps.DeviceAdminManager.getInstance(context)
        val granted = adminManager.isActive()
        viewModel.onDeviceAdminResult(granted)
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.saveTextField(
                protect.yourself.database.switchStatus.SwitchIdentifier.BLOCK_SCREEN_STORE_IMAGE_PATH,
                uri.toString()
            )
            android.widget.Toast.makeText(context, "Image selected", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigation.collect { nav ->
            when (nav) {
                is BlockerPageNavigation.OpenSelectAppPage -> {
                    currentPage = SubPage.SelectApp(nav.title, nav.identifier)
                }
                is BlockerPageNavigation.OpenAccessibilitySettings -> {
                    // Try to open the specific accessibility service detail page
                    val componentName = android.content.ComponentName(
                        context.packageName,
                        protect.yourself.features.blockerPage.service.MyAccessibilityService::class.java.name
                    )
                    try {
                        // Android 12+ supports ACTION_ACCESSIBILITY_DETAIL_SETTINGS
                        val intent = Intent("android.settings.ACCESSIBILITY_DETAIL_SETTINGS").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
                        }
                        context.startActivity(intent)
                    } catch (e: Throwable) {
                        // Fallback: open general accessibility settings
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Throwable) {}
                    }
                }
                is BlockerPageNavigation.OpenOverlaySettings -> {
                    context.startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
                is BlockerPageNavigation.OpenUrl -> {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(nav.url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Throwable) {}
                }
                is BlockerPageNavigation.ShowToast -> {
                    android.widget.Toast.makeText(context, nav.message, android.widget.Toast.LENGTH_SHORT).show()
                }
                is BlockerPageNavigation.ShowToastRes -> {
                    // VPN-14 fix: resolve the string resource in the UI layer
                    // (the ViewModel does not have a Context).
                    val msg = if (nav.args.isEmpty()) {
                        context.getString(nav.resId)
                    } else {
                        context.getString(nav.resId, *nav.args.toTypedArray())
                    }
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                is BlockerPageNavigation.EditTextField -> {
                    editDialog = EditDialogData(nav.title, nav.currentValue, nav.hint, nav.switchKey)
                }
                is BlockerPageNavigation.EditNumberField -> {
                    numberDialog = NumberDialogData(nav.title, nav.currentValue, nav.min, nav.max, nav.switchKey)
                }
                is BlockerPageNavigation.RequestVpnPermission -> {
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        vpnPermissionLauncher.launch(intent)
                    } else {
                        MyVpnService.start(context)
                        viewModel.onVpnPermissionGranted()
                    }
                }
                is BlockerPageNavigation.StopVpn -> {
                    MyVpnService.stop(context)
                }
                is BlockerPageNavigation.RestartVpn -> {
                    MyVpnService.restart(context)
                }
                is BlockerPageNavigation.OpenVpnManagement -> {
                    currentPage = SubPage.VpnManagement
                }
                is BlockerPageNavigation.OpenAppLockSetup -> {
                    currentPage = SubPage.AppLockSetup
                }
                is BlockerPageNavigation.OpenKeywordManager -> {
                    currentPage = SubPage.KeywordManager
                }
                is BlockerPageNavigation.OpenKeywordManagerTab -> {
                    currentPage = SubPage.KeywordManagerTab(nav.tab)
                }
                is BlockerPageNavigation.OpenPackageIntentManager -> {
                    currentPage = SubPage.PackageIntentManager
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
                is BlockerPageNavigation.PickBlockScreenImage -> {
                    imagePickerLauncher.launch("image/*")
                }
                is BlockerPageNavigation.RequestDeviceAdmin -> {
                    try {
                        val adminManager = protect.yourself.features.protectedApps.DeviceAdminManager.getInstance(context)
                        // If admin is already active, just persist the switch ON
                        if (adminManager.isActive()) {
                            viewModel.onDeviceAdminResult(true)
                        } else {
                            // Launch Device Admin activation screen via launcher
                            // so we get a callback when the user returns
                            val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(
                                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                    protect.yourself.features.blockerPage.utils.DeviceAdminUtils.getComponentName(context)
                                )
                                putExtra(
                                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Device Admin prevents unauthorized uninstall of Protect Yourself."
                                )
                            }
                            deviceAdminLauncher.launch(intent)
                        }
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(context, "Could not open Device Admin settings", android.widget.Toast.LENGTH_SHORT).show()
                        // Revert the switch since we couldn't even open the screen
                        viewModel.onDeviceAdminResult(false)
                    }
                }
            }
        }
    }

    editDialog?.let { dialog ->
        EditTextDialog(dialog.title, dialog.currentValue, dialog.hint,
            onDismiss = { editDialog = null },
            onSave = { value ->
                viewModel.saveTextField(dialog.switchKey, value)
                editDialog = null
            }
        )
    }

    numberDialog?.let { dialog ->
        EditNumberDialog(dialog.title, dialog.currentValue, dialog.min, dialog.max,
            onDismiss = { numberDialog = null },
            onSave = { value ->
                viewModel.saveNumberField(dialog.switchKey, value)
                numberDialog = null
            }
        )
    }

    // Handle system Back button — return to home list from sub-pages
    androidx.activity.compose.BackHandler(
        enabled = currentPage != null,
        onBack = { currentPage = null }
    )

    when (val page = currentPage) {
        null -> HomeWithCategories(context, viewModel, state, currentPage, { currentPage = it })
        is SubPage.SelectApp -> {
            SelectAppPage(identifier = page.identifier, title = page.title, onBack = { currentPage = null })
        }
        SubPage.AppLockSetup -> {
            protect.yourself.features.appPasswordPage.AppLockSetupPage(onBack = { currentPage = null })
        }
        is SubPage.CategoryPage -> {
            CategoryDetailPage(
                title = page.title,
                items = state.settingItems.filter { it.identifier in page.identifiers },
                viewModel = viewModel,
                onBack = { currentPage = null }
            )
        }
        SubPage.KeywordManager -> protect.yourself.features.keywordManagerPage.KeywordManagerPage(
            onBack = { currentPage = null }
        )
        is SubPage.KeywordManagerTab -> protect.yourself.features.keywordManagerPage.KeywordManagerPage(
            initialTab = page.tab,
            onBack = { currentPage = null }
        )
        SubPage.PackageIntentManager -> protect.yourself.features.packageIntentPage.PackageIntentPage(
            onBack = { currentPage = null }
        )
        SubPage.RequestHistory -> SimpleSubPage("Request History") { currentPage = null }
        SubPage.Faq -> SimpleSubPage("FAQ") { currentPage = null }
        SubPage.StopMe -> protect.yourself.features.stopMePage.StopMePage(
            onBack = { currentPage = null }
        )
        SubPage.VpnManagement -> VpnManagementPage(
            onBack = { currentPage = null },
            onOpenVpnWhitelistApps = {
                currentPage = SubPage.SelectApp(
                    "VPN Whitelist Apps",
                    SelectedAppListIdentifier.VPN_WHITELIST_APPS
                )
            },
            onEditNotificationMessage = {
                // Open the existing edit-text dialog flow for the VPN
                // notification message. We re-use the ViewModel's action
                // handler so the dialog logic stays in one place.
                viewModel.onActionClick(
                    protect.yourself.features.blockerPage.data.SettingPageItemModel(
                        identifier = protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE,
                        title = "VPN Notification Message",
                        info = null
                    )
                )
            }
        )
        SubPage.ImagePicker -> SimpleSubPage("Choose Image") { currentPage = null }
    }
}

// ===== HOME: 4 primary category cards =====

@Composable
private fun HomeWithCategories(
    context: android.content.Context,
    viewModel: BlockerPageViewModel,
    state: protect.yourself.features.blockerPage.BlockerPageState,
    currentPage: SubPage?,
    onNavigate: (SubPage) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Accessibility status banner
        item { AccessibilityWarningCard(context) }

        // Block screen count
        item { BlockScreenCountCard(state) }

        // 4 primary category cards
        item {
            CategoryCard(
                title = "Protective Mode",
                subtitle = "Time Delay, Real Friend, Daily Report",
                icon = Icons.Filled.Shield,
                onClick = {
                    onNavigate(SubPage.CategoryPage("Protective Mode", setOf(
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.TIME_DELAY,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.TIME_DELAY_CUSTOM_DURATION,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.REAL_FRIEND,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.DAILY_REPORT,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.SUGGEST_PROTECTIVE_MODE,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.REQUEST_HISTORY
                    )))
                }
            )
        }

        item {
            CategoryCard(
                title = "Content Blocking",
                subtitle = "Porn blocker, keywords, apps, SafeSearch, browsers",
                icon = Icons.Filled.Block,
                onClick = {
                    onNavigate(SubPage.CategoryPage("Content Blocking", setOf(
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.SUPPORTED_BROWSERS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.PORN_BLOCKER,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCKER_CUSTOM_KEYWORD_WEBSITE,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCKLIST_APPS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.SAFE_SEARCH,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.MAKE_ANY_BROWSER_SUPPORTED
                    )))
                }
            )
        }

        item {
            CategoryCard(
                title = "Uninstall Protection",
                subtitle = "Prevent uninstall, block reboot, title-based blocking",
                icon = Icons.Filled.Security,
                onClick = {
                    onNavigate(SubPage.CategoryPage("Uninstall Protection", setOf(
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.PREVENT_UNINSTALL_SETTINGS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_NOTIFICATION_DRAWER,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_PHONE_REBOOT,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_RECENT_APPS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE_APPS
                    )))
                }
            )
        }

        item {
            CategoryCard(
                title = "App Lock",
                subtitle = "PIN/password/pattern lock, biometric, forgot password",
                icon = Icons.Filled.Lock,
                onClick = {
                    onNavigate(SubPage.CategoryPage("App Lock", setOf(
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.SET_APP_LOCK,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.TOUCH_ID,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.DISABLE_FORGOT_PASSWORD
                    )))
                }
            )
        }

        item {
            CategoryCard(
                title = "Advanced Features",
                subtitle = "VPN, block screen customization, package+intent, in-app browsers",
                icon = Icons.Filled.Settings,
                onClick = {
                    onNavigate(SubPage.CategoryPage("Advanced Features", setOf(
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_UNSUPPORTED_BROWSERS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.WHITELIST_UNSUPPORTED_BROWSER,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_PACKAGE_INTENT,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.ADD_PACKAGE_INTENT_TO_BLOCK,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.VPN,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.VPN_MANAGE,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.WHITELIST_VPN_APPS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.VPN_NOTIFICATION_HIDE,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_NEW_INSTALL_APPS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_IN_APP_BROWSERS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCKED_SCREEN_IMAGE,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCKED_SCREEN_MESSAGE,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCKED_SCREEN_COUNTDOWN,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.CUSTOM_REDIRECT_URL_APP,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_WHITELIST_DETECTED_APP
                    )))
                }
            )
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun CategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(BrandOrange.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = BrandOrange, modifier = Modifier.size(28.dp))
            }
            Spacer(modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BlockScreenCountCard(state: protect.yourself.features.blockerPage.BlockerPageState) {
    val count = state.settingItems.firstOrNull {
        it.identifier == protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_SCREEN_COUNT
    }?.actionLabel ?: "0"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Block screens", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Total times content has been blocked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(count, style = MaterialTheme.typography.headlineMedium, color = BrandOrange, fontWeight = FontWeight.Bold)
        }
    }
}

// ===== Category detail page =====

@Composable
private fun CategoryDetailPage(
    title: String,
    items: List<SettingPageItemModel>,
    viewModel: BlockerPageViewModel,
    onBack: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = BrandOrange)
                }
            }
        }
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = BrandOrange,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(items) { item ->
            when {
                item.switchKey != null -> SwitchRow(item) { viewModel.toggleSwitch(it) }
                item.actionLabel != null -> ActionRow(item) { viewModel.onActionClick(it) }
                else -> InfoRow(item)
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

// ===== Dialogs =====

@Composable
private fun EditTextDialog(
    title: String, currentValue: String, hint: String,
    onDismiss: () -> Unit, onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentValue) }

    // Use a custom Dialog (not AlertDialog) so we can control the scrim
    // colour + surface elevation for better contrast + accessibility.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f))
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { /* swallow clicks inside the card so it doesn't dismiss */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(hint) },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandOrange,
                            focusedLabelColor = BrandOrange,
                            cursorColor = BrandOrange
                        )
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { onSave(text) },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = BrandOrange,
                                contentColor = androidx.compose.ui.graphics.Color.White
                            ),
                            enabled = text.isNotBlank()
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditNumberDialog(
    title: String, currentValue: Int, min: Int, max: Int,
    onDismiss: () -> Unit, onSave: (Int) -> Unit
) {
    var text by remember { mutableStateOf(currentValue.toString()) }
    val parsed = text.toIntOrNull()
    val isValid = parsed != null && parsed >= min && parsed <= max

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f))
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) { /* swallow clicks inside the card */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() } },
                        label = { Text("Seconds ($min-$max)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = !isValid,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandOrange,
                            focusedLabelColor = BrandOrange,
                            cursorColor = BrandOrange
                        )
                    )
                    if (!isValid && text.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Must be between $min and $max",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("Cancel", fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { onSave(parsed ?: min) },
                            enabled = isValid,
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = BrandOrange,
                                contentColor = androidx.compose.ui.graphics.Color.White
                            )
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ===== Accessibility banner =====

@Composable
private fun AccessibilityWarningCard(context: android.content.Context) {
    var isAccessibilityEnabled by remember { mutableStateOf(MyAccessibilityService.isEnabled(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = MyAccessibilityService.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!isAccessibilityEnabled) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp)
                .clickable {
                    // Try direct service detail page first, fallback to general settings
                    val componentName = android.content.ComponentName(
                        context.packageName,
                        protect.yourself.features.blockerPage.service.MyAccessibilityService::class.java.name
                    )
                    try {
                        val intent = Intent("android.settings.ACCESSIBILITY_DETAIL_SETTINGS").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
                        }
                        context.startActivity(intent)
                    } catch (e: Throwable) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (_: Throwable) {}
                    }
                },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⚠️ Accessibility not enabled", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Blocking features are disabled. Tap here to enable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Steps: Settings → Accessibility → Protect Yourself → ON", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Medium)
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1B5E20))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("✅ Accessibility enabled", style = MaterialTheme.typography.titleSmall, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                Text("Content blocking is active.", style = MaterialTheme.typography.bodySmall, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

// ===== Row components =====

@Composable
private fun SwitchRow(item: SettingPageItemModel, onToggle: (SettingPageItemModel) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                if (!item.info.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(item.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.size(16.dp))
            Switch(checked = item.switchValue, onCheckedChange = { onToggle(item) })
        }
    }
}

@Composable
private fun ActionRow(item: SettingPageItemModel, onClick: (SettingPageItemModel) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = { onClick(item) }
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                if (!item.info.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(item.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.size(16.dp))
            TextButton(onClick = { onClick(item) }) {
                Text(item.actionLabel ?: "", color = BrandOrange, fontWeight = FontWeight.SemiBold)
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
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            if (!item.info.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(item.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SimpleSubPage(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← Back", color = BrandOrange) }
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text("This feature is being implemented.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===== Data classes =====

private data class EditDialogData(val title: String, val currentValue: String, val hint: String, val switchKey: String)
private data class NumberDialogData(val title: String, val currentValue: Int, val min: Int, val max: Int, val switchKey: String)

sealed class SubPage {
    data class SelectApp(val title: String, val identifier: SelectedAppListIdentifier) : SubPage()
    data class CategoryPage(val title: String, val identifiers: Set<protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers>) : SubPage()
    data object KeywordManager : SubPage()
    data class KeywordManagerTab(val tab: protect.yourself.features.keywordManagerPage.KeywordTab) : SubPage()
    data object PackageIntentManager : SubPage()
    data object AppLockSetup : SubPage()
    data object RequestHistory : SubPage()
    data object Faq : SubPage()
    data object StopMe : SubPage()
    data object VpnManagement : SubPage()
    data object ImagePicker : SubPage()
}
