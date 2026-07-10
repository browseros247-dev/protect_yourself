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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // VPN permission launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Permission granted — start VPN + persist switch
            MyVpnService.start(context)
            viewModel.onVpnPermissionGranted()
        } else {
            // Permission denied — don't toggle
        }
    }

    // Image picker launcher (for block screen motivation image)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            // Save the image URI to DB
            viewModel.saveTextField(
                protect.yourself.database.switchStatus.SwitchIdentifier.BLOCK_SCREEN_STORE_IMAGE_PATH,
                uri.toString()
            )
            android.widget.Toast.makeText(context, "Image selected", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Collect navigation events
    LaunchedEffect(Unit) {
        viewModel.navigation.collect { nav ->
            when (nav) {
                is BlockerPageNavigation.OpenSelectAppPage -> {
                    currentPage = SubPage.SelectApp(nav.title, nav.identifier)
                }
                is BlockerPageNavigation.OpenAccessibilitySettings -> {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
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
                        // Already prepared — start VPN directly
                        MyVpnService.start(context)
                        viewModel.onVpnPermissionGranted()
                    }
                }
                is BlockerPageNavigation.StopVpn -> {
                    MyVpnService.stop(context)
                }
                is BlockerPageNavigation.OpenAppLockSetup -> {
                    currentPage = SubPage.AppLockSetup
                }
                is BlockerPageNavigation.OpenKeywordManager -> {
                    currentPage = SubPage.KeywordManager
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
                        adminManager.requestActive()
                    } catch (t: Throwable) {
                        android.widget.Toast.makeText(context, "Could not open Device Admin settings", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Edit text dialog
    editDialog?.let { dialog ->
        EditTextDialog(
            title = dialog.title,
            currentValue = dialog.currentValue,
            hint = dialog.hint,
            onDismiss = { editDialog = null },
            onSave = { value ->
                viewModel.saveTextField(dialog.switchKey, value)
                editDialog = null
            }
        )
    }

    // Edit number dialog
    numberDialog?.let { dialog ->
        EditNumberDialog(
            title = dialog.title,
            currentValue = dialog.currentValue,
            min = dialog.min,
            max = dialog.max,
            onDismiss = { numberDialog = null },
            onSave = { value ->
                viewModel.saveNumberField(dialog.switchKey, value)
                numberDialog = null
            }
        )
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
        SubPage.AppLockSetup -> {
            protect.yourself.features.appPasswordPage.AppLockSetupPage(onBack = { currentPage = null })
        }
        SubPage.KeywordManager -> {
            SimpleSubPage(title = "Keyword Manager") { currentPage = null }
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
        SubPage.ImagePicker -> {
            SimpleSubPage(title = "Choose Motivation Image") { currentPage = null }
        }
        null -> {
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
private fun EditTextDialog(
    title: String,
    currentValue: String,
    hint: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(hint) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) {
                Text("Save", color = BrandOrange, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EditNumberDialog(
    title: String,
    currentValue: Int,
    min: Int,
    max: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var text by remember { mutableStateOf(currentValue.toString()) }
    val parsed = text.toIntOrNull()
    val isValid = parsed != null && parsed >= min && parsed <= max

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    label = { Text("Seconds ($min-$max)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = !isValid,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!isValid && text.isNotBlank()) {
                    Text(
                        text = "Must be between $min and $max",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(parsed ?: min) },
                enabled = isValid
            ) {
                Text("Save", color = BrandOrange, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AccessibilityWarningCard(context: android.content.Context) {
    // Reactive state — re-checks when lifecycle resumes (user returns from settings)
    var isAccessibilityEnabled by remember { mutableStateOf(MyAccessibilityService.isEnabled(context)) }

    // Re-check accessibility status when the app resumes (user returns from settings)
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
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
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
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Steps: Settings → Accessibility → Protect Yourself → ON",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    } else {
        // Show success card when accessibility IS enabled
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF1B5E20))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "✅ Accessibility enabled",
                    style = MaterialTheme.typography.titleSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Content blocking is active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(item: SettingPageItemModel) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)
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
private fun SwitchRow(item: SettingPageItemModel, onToggle: (SettingPageItemModel) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("← Back", color = BrandOrange) }
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Text("This feature is being implemented. Check back soon.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class EditDialogData(val title: String, val currentValue: String, val hint: String, val switchKey: String)
private data class NumberDialogData(val title: String, val currentValue: Int, val min: Int, val max: Int, val switchKey: String)

sealed class SubPage {
    data class SelectApp(val title: String, val identifier: SelectedAppListIdentifier) : SubPage()
    data object KeywordManager : SubPage()
    data object AppLockSetup : SubPage()
    data object RequestHistory : SubPage()
    data object Faq : SubPage()
    data object StopMe : SubPage()
    data object ImagePicker : SubPage()
}
