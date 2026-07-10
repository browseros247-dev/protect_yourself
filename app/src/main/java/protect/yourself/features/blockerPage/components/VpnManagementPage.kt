package protect.yourself.features.blockerPage.components

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.R
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel
import protect.yourself.features.blockerPage.BlockerPageNavigation
import protect.yourself.features.blockerPage.BlockerPageViewModel
import protect.yourself.features.blockerPage.identifiers.VpnConnectionTypeIdentifiers
import protect.yourself.features.blockerPage.service.MyVpnService
import protect.yourself.theme.BrandOrange

/**
 * VpnManagementPage — redesigned VPN management experience.
 *
 * Replaces the old "tap-to-cycle Normal/Powerful/Custom" action row with a
 * dedicated, self-explanatory page that shows:
 *
 *  1. A status header card with the master VPN switch + live state.
 *  2. Three mode selector cards (Balanced / Strict / Custom DNS), each with
 *     its own icon, title, multi-line description, DNS provider chip, and
 *     recommendation tag. Tapping a card immediately switches the mode and
 *     restarts the VPN if it is running.
 *  3. A custom DNS provider picker, shown when CUSTOM mode is active (or
 *     always, so users can pre-pick a provider before switching to Custom).
 *  4. Advanced settings: VPN whitelist apps, notification message, hide
 *     notification content — all in one consistent place.
 *
 * This design fixes the original UX problems:
 *   - Mode labels were confusing ("Normal", "Powerful", "Custom").
 *   - Tap-to-cycle gave no preview of the other options.
 *   - VPN_NOTIFICATION_MESSAGE identifier was overloaded (mode cycling +
 *     notification editing shared the same identifier, so only one row ever
 *     rendered).
 *   - There was no UI to manage the custom DNS presets even though the DAO
 *     and database table existed.
 */
@Composable
fun VpnManagementPage(
    onBack: () -> Unit,
    onOpenVpnWhitelistApps: () -> Unit,
    onEditNotificationMessage: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: BlockerPageViewModel = viewModel(
        factory = BlockerPageViewModel.factory(AppDatabase.getInstance(context))
    )
    val state by viewModel.vpnManagementState.collectAsState()

    // Dialog state for the "Add custom DNS" + "Delete preset" flows.
    var showAddDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<VpnCustomDnsItemModel?>(null) }

    // Load the VPN management state when the page opens.
    LaunchedEffect(Unit) { viewModel.loadVpnManagementState() }

    // Collect navigation events (RestartVpn + ShowToast + ShowToastRes) while
    // this page is on top.
    LaunchedEffect(Unit) {
        viewModel.navigation.collect { nav ->
            when (nav) {
                is BlockerPageNavigation.RestartVpn -> MyVpnService.restart(context)
                is BlockerPageNavigation.ShowToast -> {
                    android.widget.Toast.makeText(context, nav.message, android.widget.Toast.LENGTH_SHORT).show()
                }
                is BlockerPageNavigation.ShowToastRes -> {
                    // VPN-14 fix: resolve the string resource in the UI layer.
                    val msg = if (nav.args.isEmpty()) {
                        context.getString(nav.resId)
                    } else {
                        context.getString(nav.resId, *nav.args.toTypedArray())
                    }
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                else -> Unit // Other navigation events are handled by the parent.
            }
        }
    }

    // VPN permission launcher — required to turn the VPN on.
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            MyVpnService.start(context)
            viewModel.onVpnPermissionGranted()
        }
    }

    if (state.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = BrandOrange)
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // === Back button + title ===
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) {
                    Text("← Back", color = BrandOrange)
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.vpn_page_title),
                style = MaterialTheme.typography.headlineSmall,
                color = BrandOrange,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.vpn_page_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // === Status header card ===
        item {
            VpnStatusHeader(
                isVpnEnabled = state.isVpnEnabled,
                currentMode = state.currentMode,
                onToggle = { newValue ->
                    if (newValue) {
                        val intent = VpnService.prepare(context)
                        if (intent != null) {
                            vpnPermissionLauncher.launch(intent)
                        } else {
                            // Permission already granted
                            MyVpnService.start(context)
                            viewModel.onVpnPermissionGranted()
                        }
                    } else {
                        viewModel.toggleVpnOff()
                        MyVpnService.stop(context)
                    }
                }
            )
        }

        // === Filtering mode section header ===
        item { SectionHeader(R.string.vpn_mode_section_title, R.string.vpn_mode_section_subtitle) }

        // === Mode selector cards ===
        item {
            ModeSelectorCard(
                mode = VpnConnectionTypeIdentifiers.NORMAL,
                isSelected = state.currentMode == VpnConnectionTypeIdentifiers.NORMAL,
                icon = Icons.Filled.Eco,
                titleRes = R.string.vpn_mode_balanced_label,
                descriptionRes = R.string.vpn_mode_balanced_description,
                dnsRes = R.string.vpn_mode_balanced_dns,
                tagRes = R.string.vpn_mode_balanced_tag,
                onSelect = { viewModel.setVpnMode(VpnConnectionTypeIdentifiers.NORMAL) }
            )
        }
        item {
            ModeSelectorCard(
                mode = VpnConnectionTypeIdentifiers.POWERFUL,
                isSelected = state.currentMode == VpnConnectionTypeIdentifiers.POWERFUL,
                icon = Icons.Filled.GppGood,
                titleRes = R.string.vpn_mode_strict_label,
                descriptionRes = R.string.vpn_mode_strict_description,
                dnsRes = R.string.vpn_mode_strict_dns,
                tagRes = R.string.vpn_mode_strict_tag,
                onSelect = { viewModel.setVpnMode(VpnConnectionTypeIdentifiers.POWERFUL) }
            )
        }
        item {
            ModeSelectorCard(
                mode = VpnConnectionTypeIdentifiers.CUSTOM,
                isSelected = state.currentMode == VpnConnectionTypeIdentifiers.CUSTOM,
                icon = Icons.Filled.Dns,
                titleRes = R.string.vpn_mode_custom_label,
                descriptionRes = R.string.vpn_mode_custom_description,
                dnsRes = R.string.vpn_mode_custom_dns,
                tagRes = R.string.vpn_mode_custom_tag,
                onSelect = { viewModel.setVpnMode(VpnConnectionTypeIdentifiers.CUSTOM) }
            )
        }

        // === Custom DNS provider picker ===
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            SectionHeader(
                titleRes = R.string.vpn_custom_dns_section_title,
                subtitleRes = R.string.vpn_custom_dns_section_subtitle
            )
        }
        items(state.customDnsPresets, key = { it.key }) { preset ->
            CustomDnsPresetRow(
                preset = preset,
                isSelected = preset.key == state.selectedCustomDnsKey,
                isCustomModeActive = state.currentMode == VpnConnectionTypeIdentifiers.CUSTOM,
                onSelect = { viewModel.selectCustomDnsPreset(preset.key) },
                // Only user-added presets (key starts with "user_") can be deleted.
                // Default presets (key starts with "preset_") are not deletable.
                onDelete = if (preset.key.startsWith("user_")) {
                    { presetToDelete = preset }
                } else null
            )
        }
        // "+ Add custom DNS" button — opens the add dialog.
        item {
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.vpn_custom_dns_add_button),
                    color = BrandOrange,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // === Advanced settings ===
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SectionHeader(R.string.vpn_advanced_section_title, null) }

        item {
            AdvancedActionRow(
                icon = Icons.Filled.Apps,
                titleRes = R.string.vpn_whitelist_apps_title,
                subtitleRes = R.string.vpn_whitelist_apps_subtitle,
                actionLabel = stringResource(R.string.vpn_manage_action_label),
                onClick = onOpenVpnWhitelistApps
            )
        }
        item {
            AdvancedActionRow(
                icon = Icons.Filled.Notifications,
                titleRes = R.string.vpn_notification_message_title,
                subtitleRes = R.string.vpn_notification_message_subtitle,
                actionLabel = if (state.notificationMessage.isBlank()) "Default" else "Custom",
                onClick = onEditNotificationMessage
            )
        }
        item {
            AdvancedToggleRow(
                icon = Icons.Filled.VisibilityOff,
                titleRes = R.string.vpn_hide_notification_title,
                subtitleRes = R.string.vpn_hide_notification_subtitle,
                checked = state.isNotificationHidden,
                onCheckedChange = { newValue ->
                    viewModel.setVpnNotificationHidden(newValue)
                }
            )
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    // === Add custom DNS dialog ===
    if (showAddDialog) {
        AddCustomDnsDialog(
            onDismiss = { showAddDialog = false },
            onSave = { name, dns1, dns2 ->
                if (viewModel.addCustomDnsPreset(name, dns1, dns2)) {
                    showAddDialog = false
                }
                // If validation failed, the ViewModel already showed a toast —
                // keep the dialog open so the user can fix their input.
            }
        )
    }

    // === Delete preset confirmation dialog ===
    presetToDelete?.let { preset ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text(stringResource(R.string.vpn_custom_dns_delete_dialog_title)) },
            text = { Text(stringResource(R.string.vpn_custom_dns_delete_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteCustomDnsPreset(preset.key)
                        presetToDelete = null
                    }
                ) {
                    Text(
                        stringResource(R.string.vpn_custom_dns_delete_dialog_confirm),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text(stringResource(R.string.vpn_custom_dns_delete_dialog_cancel))
                }
            }
        )
    }
}

// ===== Status header =====

@Composable
private fun VpnStatusHeader(
    isVpnEnabled: Boolean,
    currentMode: VpnConnectionTypeIdentifiers,
    onToggle: (Boolean) -> Unit
) {
    val gradient = if (isVpnEnabled) {
        Brush.linearGradient(listOf(BrandOrange, BrandOrange.copy(alpha = 0.75f)))
    } else {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
    val contentColor = if (isVpnEnabled) Color.White else MaterialTheme.colorScheme.onSurface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isVpnEnabled) 8.dp else 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = if (isVpnEnabled) Color.White.copy(alpha = 0.18f) else BrandOrange.copy(alpha = 0.15f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            // VPN-12 fix: use a different icon for the disconnected
                            // state so the user can tell at a glance whether the VPN
                            // is active. Shield = active, ShieldMoon = inactive.
                            imageVector = if (isVpnEnabled) Icons.Filled.Shield else Icons.Filled.ShieldMoon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isVpnEnabled) stringResource(R.string.vpn_status_connected)
                                   else stringResource(R.string.vpn_status_disconnected),
                            style = MaterialTheme.typography.titleLarge,
                            color = contentColor,
                            fontWeight = FontWeight.Bold
                        )
                        val modeLabel = when (currentMode) {
                            VpnConnectionTypeIdentifiers.NORMAL -> stringResource(R.string.vpn_mode_balanced_label)
                            VpnConnectionTypeIdentifiers.POWERFUL -> stringResource(R.string.vpn_mode_strict_label)
                            VpnConnectionTypeIdentifiers.CUSTOM -> stringResource(R.string.vpn_mode_custom_label)
                            VpnConnectionTypeIdentifiers.OFF -> stringResource(R.string.vpn_mode_balanced_label)
                        }
                        Text(
                            text = "Mode: $modeLabel",
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.85f)
                        )
                    }
                    Switch(checked = isVpnEnabled, onCheckedChange = onToggle)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (isVpnEnabled) stringResource(R.string.vpn_status_connected_hint)
                           else stringResource(R.string.vpn_status_disconnected_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}

// ===== Section header =====

@Composable
private fun SectionHeader(titleRes: Int, subtitleRes: Int?) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )
        if (subtitleRes != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(subtitleRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== Mode selector card =====

@Composable
private fun ModeSelectorCard(
    mode: VpnConnectionTypeIdentifiers,
    isSelected: Boolean,
    icon: ImageVector,
    titleRes: Int,
    descriptionRes: Int,
    dnsRes: Int,
    tagRes: Int,
    onSelect: () -> Unit
) {
    val borderColor = if (isSelected) BrandOrange else Color.Transparent
    val borderWidth = if (isSelected) 2.dp else 0.dp
    val iconBg = if (isSelected) BrandOrange.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(iconBg, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(titleRes),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(dnsRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Tag chip (e.g. "Recommended", "Max protection", "Advanced")
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isSelected) BrandOrange.copy(alpha = 0.15f)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(50)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(tagRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = BrandOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            // VPN-11 fix: use a string resource instead of a
                            // hardcoded English "Active" label.
                            text = stringResource(R.string.vpn_mode_active),
                            style = MaterialTheme.typography.labelMedium,
                            color = BrandOrange,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ===== Custom DNS preset row =====

@Composable
private fun CustomDnsPresetRow(
    preset: VpnCustomDnsItemModel,
    isSelected: Boolean,
    isCustomModeActive: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val borderColor = if (isSelected) BrandOrange else Color.Transparent
    val borderWidth = if (isSelected) 2.dp else 0.dp
    // VPN-10 fix: when Custom mode is not active, dim the row to hint to the
    // user that their selection won't take effect immediately. The row stays
    // clickable so the user can pre-pick a provider before switching to Custom.
    val contentAlpha = if (isCustomModeActive) 1f else 0.55f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = if (isSelected) BrandOrange.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Dns,
                    contentDescription = null,
                    tint = if (isSelected) BrandOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.displayName.ifBlank { preset.key },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${preset.firstDns}  ·  ${preset.secondDns}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                )
                // VPN-10 fix: show a hint when Custom mode is not active so the
                // user understands why the row looks dimmed.
                if (!isCustomModeActive && isSelected) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.vpn_custom_dns_inactive_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isSelected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = BrandOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.vpn_custom_dns_active),
                        style = MaterialTheme.typography.labelMedium,
                        color = BrandOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                TextButton(onClick = onSelect) {
                    Text(
                        text = stringResource(R.string.vpn_custom_dns_make_active),
                        color = BrandOrange,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Delete affordance — only shown on user-added presets (onDelete != null).
            if (onDelete != null) {
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    onClick = onDelete,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.vpn_custom_dns_delete_dialog_confirm),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ===== Add custom DNS dialog =====

@Composable
private fun AddCustomDnsDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, dns1: String, dns2: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var dns1 by remember { mutableStateOf("") }
    var dns2 by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vpn_custom_dns_add_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.vpn_custom_dns_add_dialog_hint_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandOrange,
                        focusedLabelColor = BrandOrange,
                        cursorColor = BrandOrange
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = dns1,
                    onValueChange = { dns1 = it },
                    label = { Text(stringResource(R.string.vpn_custom_dns_add_dialog_hint_dns1)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandOrange,
                        focusedLabelColor = BrandOrange,
                        cursorColor = BrandOrange
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = dns2,
                    onValueChange = { dns2 = it },
                    label = { Text(stringResource(R.string.vpn_custom_dns_add_dialog_hint_dns2)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandOrange,
                        focusedLabelColor = BrandOrange,
                        cursorColor = BrandOrange
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name, dns1, dns2) },
                enabled = name.isNotBlank() && dns1.isNotBlank() && dns2.isNotBlank(),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = BrandOrange,
                    contentColor = Color.White
                )
            ) {
                Text(
                    stringResource(R.string.vpn_custom_dns_add_dialog_save),
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.vpn_custom_dns_add_dialog_cancel))
            }
        }
    )
}

// ===== Advanced rows =====

@Composable
private fun AdvancedActionRow(
    icon: ImageVector,
    titleRes: Int,
    subtitleRes: Int,
    actionLabel: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onClick) {
                Text(actionLabel, color = BrandOrange, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AdvancedToggleRow(
    icon: ImageVector,
    titleRes: Int,
    subtitleRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
