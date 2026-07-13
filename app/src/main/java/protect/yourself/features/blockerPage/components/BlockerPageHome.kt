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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.withContext
import protect.yourself.R
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.features.blockerPage.BlockerPageNavigation
import protect.yourself.features.blockerPage.BlockerPageViewModel
import protect.yourself.features.blockerPage.data.SettingPageItemModel
import protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.service.MyVpnService
import protect.yourself.features.blockerPage.utils.BlockScreenImageLoader
import protect.yourself.features.selectAppPage.components.SelectAppPage
import protect.yourself.theme.BrandOrange
import timber.log.Timber

@Composable
fun BlockerPageHome() {
    val context = LocalContext.current
    val viewModel: BlockerPageViewModel = viewModel(
        factory = BlockerPageViewModel.factory(context.applicationContext as android.app.Application, AppDatabase.getInstance(context))
    )
    val state by viewModel.state.collectAsState()

    var currentPage: SubPage? by remember { mutableStateOf(null) }
    var editDialog: EditDialogData? by remember { mutableStateOf(null) }
    var numberDialog: NumberDialogData? by remember { mutableStateOf(null) }
    // PM-01: Time Delay countdown dialog state
    var timeDelayDialog: TimeDelayDialogData? by remember { mutableStateOf(null) }

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

    /**
     * Image picker for the Block Screen Motivation Image.
     *
     * CRITICAL FIX: previously used `ActivityResultContracts.GetContent()`,
     * which returns a content:// URI that is NOT persistable — the URI
     * becomes invalid after the process dies or the device reboots, so the
     * motivation image silently disappeared. We now use
     * `ActivityResultContracts.OpenDocument()` which returns a persistable
     * content:// URI, and we call `takePersistableUriPermission()` so the
     * app retains read access across reboots.
     *
     * We also persist the URI via the new
     * [protect.yourself.features.blockerPage.BlockerPageViewModel.saveBlockScreenImageUri]
     * which validates the URI scheme before storing it.
     */
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                // Take a persistable read permission so the URI remains
                // accessible after process death / device reboot.
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                viewModel.saveBlockScreenImageUri(uri.toString())
            } catch (t: Throwable) {
                // Some providers don't support persistable permissions — log
                // and fall back to a non-persistable save. The image will
                // work for this app session but may need to be re-picked
                // after a reboot. We still save the URI so the user sees
                // immediate feedback.
                Timber.w(t, "takePersistableUriPermission failed — saving non-persistable URI")
                viewModel.saveBlockScreenImageUri(uri.toString())
            }
        } else {
            Timber.d("Image picker cancelled by user")
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
                    try {
                        context.startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(context, "Could not open overlay settings", android.widget.Toast.LENGTH_SHORT).show()
                    }
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
                is BlockerPageNavigation.OpenUnifiedBlocking -> {
                    currentPage = SubPage.UnifiedBlocking
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
                    // OpenDocument requires an array of MIME types. We pass
                    // "image/*" so the picker shows photos only.
                    imagePickerLauncher.launch(arrayOf("image/*"))
                }
                is BlockerPageNavigation.ClearBlockScreenImage -> {
                    // The ViewModel has already cleared the persisted path.
                    // Nothing more to do in the UI layer — the toast is
                    // emitted by the ViewModel via ShowToastRes.
                }
                is BlockerPageNavigation.ClearBlockScreenMessage -> {
                    // Same as above — handled by the ViewModel.
                }
                is BlockerPageNavigation.PreviewBlockScreen -> {
                    // Launch PornBlockActivity as a preview so the user can
                    // see what their custom message + image will look like
                    // without having to trigger a real block.
                    try {
                        val intent = android.content.Intent(
                            context,
                            protect.yourself.features.blockerPage.ui.PornBlockActivity::class.java
                        ).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            // Pass a synthetic package name + the default
                            // message key so the activity's onCreate logs
                            // are sensible.
                            putExtra(
                                protect.yourself.features.blockerPage.service.MyAccessibilityService.EXTRA_BLOCK_PACKAGE,
                                "preview"
                            )
                            putExtra(
                                protect.yourself.features.blockerPage.service.MyAccessibilityService.EXTRA_BLOCK_MESSAGE_KEY,
                                "block_page_default_message"
                            )
                        }
                        context.startActivity(intent)
                    } catch (t: Throwable) {
                        Timber.w(t, "Failed to launch block screen preview")
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.block_screen_image_pick_failed),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                is BlockerPageNavigation.RequestTimeDelay -> {
                    // PM-01: show the Time Delay countdown dialog
                    timeDelayDialog = TimeDelayDialogData(nav.delaySeconds, nav.item)
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
                is BlockerPageNavigation.VpnCustomDnsPresetAdded -> {
                    // BUG-05 fix: handled by VpnManagementPage's LaunchedEffect
                    // collector which dismisses the Add Custom DNS dialog.
                    // No action needed here — this branch just satisfies the
                    // exhaustive when expression.
                }
            }
        }
    }

    editDialog?.let { dialog ->
        // Block screen message has its own save path that supports
        // validation + clear-on-empty semantics. Other text fields
        // use the generic saveTextField().
        val isBlockScreenMessage =
            dialog.switchKey == protect.yourself.database.switchStatus.SwitchIdentifier.BLOCK_SCREEN_CUSTOM_MESSAGE

        EditTextDialog(
            title = dialog.title,
            currentValue = dialog.currentValue,
            hint = dialog.hint,
            maxLength = if (isBlockScreenMessage)
                protect.yourself.features.blockerPage.BlockerPageViewModel.MAX_BLOCK_SCREEN_MESSAGE_CHARS
            else null,
            showResetToDefault = isBlockScreenMessage,
            onDismiss = { editDialog = null },
            onSave = { value ->
                if (isBlockScreenMessage) {
                    viewModel.saveBlockScreenMessage(value)
                } else {
                    viewModel.saveTextField(dialog.switchKey, value)
                }
                editDialog = null
            },
            onResetToDefault = if (isBlockScreenMessage) {
                {
                    viewModel.clearBlockScreenMessage()
                    editDialog = null
                }
            } else null
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

    // PM-01: Time Delay countdown dialog
    timeDelayDialog?.let { dialog ->
        TimeDelayDialog(
            delaySeconds = dialog.delaySeconds,
            switchName = dialog.item.title,
            onConfirm = {
                viewModel.confirmToggleAfterDelay(dialog.item)
                timeDelayDialog = null
            },
            onCancel = {
                // User cancelled — don't toggle. The switch reverts to its
                // original ON state automatically (the UI was never updated).
                timeDelayDialog = null
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
            // AL-02 fix: reload setting items when returning from App Lock
            // setup. If the user just set up (or disabled) App Lock, the
            // TOUCH_ID and DISABLE_FORGOT_PASSWORD cards need to appear or
            // disappear. Without this reload, the cards would be stale.
            protect.yourself.features.appPasswordPage.AppLockSetupPage(
                onBack = {
                    currentPage = null
                    viewModel.loadSettingItems()
                }
            )
        }
        is SubPage.CategoryPage -> {
            CategoryDetailPage(
                title = page.title,
                items = state.settingItems.filter { it.identifier in page.identifiers },
                viewModel = viewModel,
                onBack = { currentPage = null }
            )
        }
        SubPage.UnifiedBlocking -> protect.yourself.features.blockerPage.components.UnifiedBlockingPage(
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
        SubPage.ReliableAccessibility ->
            protect.yourself.features.protectedApps.WriteSecureSettingsSetupPage(
                onBack = { currentPage = null }
            )
        SubPage.ProtectedApps -> {
            // Launch the ProtectedAppsActivity (separate activity to keep
            // navigation simple and avoid recomposition overhead).
            val ctx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                ctx.startActivity(
                    android.content.Intent(
                        ctx,
                        protect.yourself.features.protectedApps.ProtectedAppsActivity::class.java
                    )
                )
                currentPage = null
            }
        }
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
                subtitle = "Porn blocker, keywords, apps, SafeSearch, browser blocking",
                icon = Icons.Filled.Block,
                onClick = {
                    onNavigate(SubPage.CategoryPage("Content Blocking", setOf(
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.PORN_BLOCKER,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.UNIFIED_BLOCKING_MANAGEMENT,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCKLIST_APPS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.SAFE_SEARCH,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_UNSUPPORTED_BROWSERS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.WHITELIST_UNSUPPORTED_BROWSER
                    )))
                }
            )
        }

        item {
            CategoryCard(
                title = "Uninstall Protection",
                subtitle = "Prevent uninstall, block reboot",
                icon = Icons.Filled.Security,
                onClick = {
                    onNavigate(SubPage.CategoryPage("Uninstall Protection", setOf(
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.PREVENT_UNINSTALL_SETTINGS,
                        protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers.BLOCK_PHONE_REBOOT,
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
                subtitle = "VPN, block screen customization, in-app browsers",
                icon = Icons.Filled.Settings,
                onClick = {
                    onNavigate(SubPage.CategoryPage("Advanced Features", setOf(
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

        item {
            CategoryCard(
                title = "Reliable Accessibility",
                subtitle = "One-time ADB setup to prevent OEMs from killing the service",
                icon = Icons.Filled.Security,
                onClick = { onNavigate(SubPage.ReliableAccessibility) }
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
            // Dedicated cards for the Block Screen Motivation Image and
            // Block Screen Message — these benefit from a richer layout
            // (thumbnail preview, Clear button, Preview button) that the
            // generic ActionRow can't express.
            when (item.identifier) {
                SettingPageItemIdentifiers.BLOCKED_SCREEN_IMAGE ->
                    BlockScreenImageRow(item, viewModel)
                SettingPageItemIdentifiers.BLOCKED_SCREEN_MESSAGE ->
                    BlockScreenMessageRow(item, viewModel)
                else -> when {
                    item.switchKey != null -> SwitchRow(item) { viewModel.toggleSwitch(it) }
                    item.actionLabel != null -> ActionRow(item) { viewModel.onActionClick(it) }
                    else -> InfoRow(item)
                }
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

// ===== Dialogs =====

@Composable
private fun EditTextDialog(
    title: String,
    currentValue: String,
    hint: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    /**
     * Optional max character count. When set, a "N / maxLength" counter is
     * shown below the text field and the input is clamped to [maxLength].
     */
    maxLength: Int? = null,
    /**
     * When true, a "Reset to default" button is shown in the dialog footer.
     * Used by the Block Screen Message dialog so the user can clear the
     * custom message back to the localized default.
     */
    showResetToDefault: Boolean = false,
    onResetToDefault: (() -> Unit)? = null
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
                        onValueChange = { newValue ->
                            // Clamp to maxLength when set
                            text = if (maxLength != null && newValue.length > maxLength) {
                                newValue.take(maxLength)
                            } else {
                                newValue
                            }
                        },
                        label = { Text(hint) },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandOrange,
                            focusedLabelColor = BrandOrange,
                            cursorColor = BrandOrange
                        )
                    )
                    // Character counter — only shown when maxLength is set
                    if (maxLength != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            val context = LocalContext.current
                            val counterColor = if (text.length >= maxLength) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = context.getString(
                                    R.string.block_screen_message_dialog_counter,
                                    text.length, maxLength
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = counterColor
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // "Reset to default" — left-aligned, only for block
                        // screen message.
                        if (showResetToDefault && onResetToDefault != null) {
                            val context = LocalContext.current
                            TextButton(
                                onClick = onResetToDefault,
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(context.getString(R.string.block_screen_message_reset_to_default))
                            }
                            Spacer(modifier = Modifier.weight(1f))
                        }
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
                            // Allow saving an empty value when maxLength is
                            // set — for the block screen message, an empty
                            // value means "reset to default".
                            enabled = maxLength != null || text.isNotBlank()
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

/**
 * Dedicated settings card for the Block Screen Motivation Image.
 *
 * Shows:
 *  - Title + info text
 *  - A 56x56 thumbnail preview of the currently-set image (loaded async
 *    via [BlockScreenImageLoader]). If no image is set, a placeholder
 *    icon is shown instead.
 *  - A primary "Choose" / "Change" TextButton (action label is dynamic
 *    and comes from the ViewModel via [SettingPageItemModel.actionLabel]).
 *  - A secondary "Remove" IconButton that calls
 *    [BlockerPageViewModel.clearBlockScreenImage]. Only shown when an
 *    image is currently set.
 *  - A "Preview block screen" TextButton that launches PornBlockActivity
 *    so the user can see what their customization looks like.
 *
 * The thumbnail is loaded off the main thread via `remember { mutableStateOf }
 * + LaunchedEffect` to avoid blocking recomposition.
 */
@Composable
private fun BlockScreenImageRow(
    item: SettingPageItemModel,
    viewModel: BlockerPageViewModel
) {
    val context = LocalContext.current
    // Whether an image is currently set — derived from the action label.
    val isImageSet = item.actionLabel != context.getString(R.string.block_screen_image_action_choose)

    // Thumbnail state. Null = not loaded yet / nothing set.
    var thumbnail by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var thumbnailLoadAttempted by remember { mutableStateOf(false) }

    // Re-load the thumbnail whenever the action label flips between
    // "Choose" and "Change" (i.e. the user picked / cleared an image).
    LaunchedEffect(item.actionLabel) {
        if (isImageSet && !thumbnailLoadAttempted) {
            thumbnailLoadAttempted = true
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val db = protect.yourself.database.core.AppDatabase.getInstance(context)
                    val sv = protect.yourself.database.switchStatus.SwitchStatusValues(db.switchStatusDao())
                    val path = sv.getBlockScreenStoreImagePath()
                    val bmp = if (!path.isNullOrBlank()) {
                        // Use decodeWithReason so we can log the specific
                        // failure mode (too large / decode failed / no input).
                        when (val r = BlockScreenImageLoader.decodeWithReason(context, path)) {
                            is BlockScreenImageLoader.DecodeResult.Success -> r.bitmap
                            is BlockScreenImageLoader.DecodeResult.TooLarge -> {
                                Timber.w("BlockScreenImageRow: thumbnail too large for path=%s", path)
                                null
                            }
                            is BlockScreenImageLoader.DecodeResult.DecodeFailed -> {
                                Timber.w("BlockScreenImageRow: thumbnail decode failed for path=%s", path)
                                null
                            }
                            is BlockScreenImageLoader.DecodeResult.NoInput -> null
                        }
                    } else null
                    thumbnail = bmp
                } catch (t: Throwable) {
                    Timber.w(t, "BlockScreenImageRow: thumbnail load failed")
                    thumbnail = null
                }
            }
        } else if (!isImageSet) {
            thumbnail = null
            thumbnailLoadAttempted = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = { viewModel.onActionClick(item) }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail or placeholder
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            BrandOrange.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = thumbnail
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = context.getString(R.string.block_screen_image_preview_content_description),
                            modifier = Modifier.size(56.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = null,
                            tint = BrandOrange,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    if (!item.info.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(item.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Primary action — Choose / Change
                TextButton(
                    onClick = { viewModel.onActionClick(item) },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = BrandOrange
                    )
                ) {
                    Text(item.actionLabel ?: "", fontWeight = FontWeight.SemiBold)
                }
                // Secondary action — Preview block screen
                TextButton(
                    onClick = { viewModel.previewBlockScreen() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(context.getString(R.string.block_screen_preview_button))
                }
                // Spacer pushes the Clear button to the right
                Spacer(modifier = Modifier.weight(1f))
                // Clear button — only shown when an image is set
                if (isImageSet) {
                    IconButton(
                        onClick = { viewModel.clearBlockScreenImage() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = context.getString(R.string.block_screen_image_action_remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dedicated settings card for the Block Screen Message.
 *
 * Shows:
 *  - Title + info text
 *  - A 1-line preview of the currently-set custom message (or "Using
 *    default message" if none is set)
 *  - A primary "Custom" / "Default" TextButton (action label comes from
 *    the ViewModel)
 *  - A "Reset to default" IconButton when a custom message is set
 *  - A "Preview block screen" TextButton
 */
@Composable
private fun BlockScreenMessageRow(
    item: SettingPageItemModel,
    viewModel: BlockerPageViewModel
) {
    val context = LocalContext.current
    // Live preview of the current custom message — loaded from the DB.
    var previewMessage by remember { mutableStateOf<String?>(null) }
    var loadAttempted by remember { mutableStateOf(false) }

    val isCustomSet = item.actionLabel == context.getString(R.string.block_screen_message_action_custom)

    LaunchedEffect(item.actionLabel) {
        if (!loadAttempted || item.actionLabel == context.getString(R.string.block_screen_message_action_custom)) {
            loadAttempted = true
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val db = protect.yourself.database.core.AppDatabase.getInstance(context)
                    val sv = protect.yourself.database.switchStatus.SwitchStatusValues(db.switchStatusDao())
                    previewMessage = sv.getBlockScreenCustomMessage()
                } catch (t: Throwable) {
                    Timber.w(t, "BlockScreenMessageRow: preview load failed")
                    previewMessage = null
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = { viewModel.onActionClick(item) }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            BrandOrange.copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Message,
                        contentDescription = null,
                        tint = BrandOrange,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.size(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    if (!item.info.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(item.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Preview line — 1 line, ellipsized
                    Spacer(modifier = Modifier.height(4.dp))
                    val msg = previewMessage
                    Text(
                        text = if (!msg.isNullOrBlank()) "\"$msg\"" else context.getString(R.string.block_page_default_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!msg.isNullOrBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        fontStyle = if (!msg.isNullOrBlank()) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { viewModel.onActionClick(item) },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = BrandOrange
                    )
                ) {
                    Text(item.actionLabel ?: "", fontWeight = FontWeight.SemiBold)
                }
                TextButton(
                    onClick = { viewModel.previewBlockScreen() },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(context.getString(R.string.block_screen_preview_button))
                }
                Spacer(modifier = Modifier.weight(1f))
                if (isCustomSet) {
                    IconButton(
                        onClick = { viewModel.clearBlockScreenMessage() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = context.getString(R.string.block_screen_message_reset_to_default),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
// PM-01: Time Delay dialog state
private data class TimeDelayDialogData(val delaySeconds: Int, val item: protect.yourself.features.blockerPage.data.SettingPageItemModel)

/**
 * PM-01: Time Delay countdown dialog. Shows a live countdown and blocks the
 * toggle until the countdown completes. The user can cancel at any time.
 */
@Composable
private fun TimeDelayDialog(
    delaySeconds: Int,
    switchName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var remaining by remember { mutableStateOf(delaySeconds) }

    // Tick down every second
    androidx.compose.runtime.LaunchedEffect(delaySeconds) {
        while (remaining > 0) {
            kotlinx.coroutines.delay(1000)
            remaining--
        }
        // Countdown complete — auto-confirm
        onConfirm()
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Time Delay") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Please wait $remaining seconds before disabling \"$switchName\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator(
                    progress = { 1f - (remaining.toFloat() / delaySeconds.toFloat()) },
                    color = BrandOrange,
                    strokeWidth = 4.dp
                )
            }
        },
        confirmButton = {
            // Confirm button is only enabled when countdown reaches 0
            androidx.compose.material3.TextButton(
                onClick = onConfirm,
                enabled = remaining == 0
            ) {
                Text("Disable now", color = if (remaining == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    )
}

sealed class SubPage {
    data class SelectApp(val title: String, val identifier: SelectedAppListIdentifier) : SubPage()
    data class CategoryPage(val title: String, val identifiers: Set<protect.yourself.features.blockerPage.identifiers.SettingPageItemIdentifiers>) : SubPage()
    data object UnifiedBlocking : SubPage()
    data object AppLockSetup : SubPage()
    data object RequestHistory : SubPage()
    data object Faq : SubPage()
    data object StopMe : SubPage()
    data object VpnManagement : SubPage()
    data object ImagePicker : SubPage()
    /** One-time setup page for granting WRITE_SECURE_SETTINGS via ADB. */
    data object ReliableAccessibility : SubPage()
    /** Lists all installed accessibility services for protection toggling. */
    data object ProtectedApps : SubPage()
}
