package protect.yourself.features.schedulePage.components

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.database.core.AppDatabase
import protect.yourself.features.schedulePage.ScheduleDisplayItem
import protect.yourself.features.schedulePage.SchedulePageViewModel
import protect.yourself.features.schedulePage.identifiers.ScheduleTypeIdentifiers
import protect.yourself.theme.BrandOrange
import java.util.Calendar

/**
 * SchedulePage — the main content of the Schedule tab.
 *
 * Shows a list of scheduled app restriction rules with their status,
 * a FAB to add new rules, and tap-to-edit / toggle / delete actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePage(
    onCreateSchedule: () -> Unit,
    onEditSchedule: (String) -> Unit
) {
    val viewModel: SchedulePageViewModel = viewModel(
        factory = SchedulePageViewModel.factory(
            androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application,
            AppDatabase.getInstance(androidx.compose.ui.platform.LocalContext.current)
        )
    )
    val state by viewModel.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Reload schedules every time the page is opened (updates "Active now" status)
    LaunchedEffect(Unit) {
        viewModel.loadSchedules()
    }

    // FIX: Reload schedules + VPN status when the page resumes (e.g. after user
    // returns from VPN settings where they may have enabled/disabled VPN).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.loadSchedules()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Collect navigation events (VPN warnings, errors) → show toasts
    LaunchedEffect(Unit) {
        viewModel.navigation.collect { nav ->
            when (nav) {
                is protect.yourself.features.schedulePage.ScheduleNavigation.VpnRequired -> {
                    android.widget.Toast.makeText(context, nav.message, android.widget.Toast.LENGTH_LONG).show()
                }
                is protect.yourself.features.schedulePage.ScheduleNavigation.Error -> {
                    android.widget.Toast.makeText(context, nav.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Check if any schedules need VPN (internet or both type) but VPN is off
    val hasInternetSchedulesWithoutVpn = state.schedules.any {
        (it.rule.type == "internet" || it.rule.type == "both") && it.rule.isEnabled
    } && !state.isVpnEnabled

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Schedule", fontWeight = FontWeight.Bold, color = BrandOrange) },
                actions = {
                    // Settings button — opens Protected Accessibility Services page
                    androidx.compose.material3.IconButton(onClick = {
                        val intent = android.content.Intent(context,
                            protect.yourself.features.protectedApps.ProtectedAppsActivity::class.java)
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    }) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.Settings,
                            contentDescription = "Protected Accessibility Services"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateSchedule,
                icon = { Icon(Icons.Filled.Add, contentDescription = "Add") },
                text = { Text("New Schedule") },
                containerColor = BrandOrange,
                contentColor = Color.White
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            if (state.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = BrandOrange)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // VPN dependency warning banner
                    if (hasInternetSchedulesWithoutVpn) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "VPN is not enabled",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Some schedules block internet, but VPN is off. " +
                                                "Enable VPN in Home → Advanced Features → VPN " +
                                                "for internet blocking to work.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Empty state
                    if (state.schedules.isEmpty()) {
                        item { ScheduleEmptyState() }
                    } else {
                        items(state.schedules, key = { it.rule.key }) { item ->
                            ScheduleCard(
                                item = item,
                                onToggle = { viewModel.toggleSchedule(item.rule.key, it) },
                                onDelete = { viewModel.deleteSchedule(item.rule.key) },
                                onClick = { onEditSchedule(item.rule.key) }
                            )
                        }
                    }
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ScheduleEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = null,
            tint = BrandOrange.copy(alpha = 0.5f),
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Schedules Yet",
            style = MaterialTheme.typography.headlineSmall,
            color = BrandOrange,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create a schedule to automatically restrict apps by time.\n" +
                "Block internet, block launching, or both.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScheduleCard(
    item: ScheduleDisplayItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val rule = item.rule
    val typeLabel = ScheduleTypeIdentifiers.label(rule.type)
    val timeLabel = formatTimeRange(rule.startTimeMinutes, rule.endTimeMinutes)
    val daysLabel = formatDays(rule.daysOfWeek)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isActiveNow)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (item.isActiveNow) 4.dp else 1.dp
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (item.isActiveNow) BrandOrange else Color.Gray,
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    color = if (rule.isEnabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = timeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = daysLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = BrandOrange,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "• ${item.appCount} app${if (item.appCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.isActiveNow) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "• Active now",
                        style = MaterialTheme.typography.labelMedium,
                        color = BrandOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                androidx.compose.material3.TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Format minutes-from-midnight as "h:mm AM/PM" */
private fun formatTime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    val ampm = if (h < 12) "AM" else "PM"
    val displayH = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "%d:%02d %s".format(displayH, m, ampm)
}

private fun formatTimeRange(start: Int, end: Int): String {
    return "${formatTime(start)} – ${formatTime(end)}"
}

private fun formatDays(daysOfWeek: Int): String {
    if (daysOfWeek == 127) return "Every day"
    if (daysOfWeek == 0) return "No days"
    val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    val active = mutableListOf<String>()
    for (i in 0..6) {
        if (daysOfWeek and (1 shl i) != 0) {
            active.add(dayNames[i])
        }
    }
    return active.joinToString(", ")
}
