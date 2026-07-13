package protect.yourself.features.schedulePage

import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import protect.yourself.core.ScheduleTypeIdentifiers
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionAppItemModel
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorPage(
    restrictionKey: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val db = remember { AppDatabase.getInstance(app) }

    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ScheduleTypeIdentifiers.LAUNCH_BLOCKING) }
    var startTime by remember { mutableStateOf(22 * 60) }
    var endTime by remember { mutableStateOf(6 * 60) }
    var daysOfWeek by remember { mutableStateOf("*") }
    var isStrictMode by remember { mutableStateOf(false) }
    var blockedApps by remember { mutableStateOf<List<ScheduledRestrictionAppItemModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(restrictionKey != null) }
    var showAppPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(restrictionKey) {
        if (restrictionKey != null) {
            withContext(Dispatchers.IO) {
                val schedule = db.scheduledRestrictionDao().get(restrictionKey)
                if (schedule != null) {
                    name = schedule.name
                    type = schedule.type
                    startTime = schedule.startTimeMinutes
                    endTime = schedule.endTimeMinutes
                    daysOfWeek = schedule.daysOfWeek
                    isStrictMode = schedule.isStrictMode
                    blockedApps = db.scheduledRestrictionAppDao().getAppsForRestriction(restrictionKey)
                }
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (restrictionKey == null) "Add Schedule" else "Edit Schedule")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            val key = restrictionKey ?: java.util.UUID.randomUUID().toString()
                            val now = System.currentTimeMillis()
                            scope.launch {
                                try {
                                    val createdAt = if (restrictionKey == null) now else db.scheduledRestrictionDao().get(key)?.createdAt ?: now
                                    val item = ScheduledRestrictionItemModel(
                                        restrictionKey = key,
                                        name = name,
                                        type = type,
                                        startTimeMinutes = startTime,
                                        endTimeMinutes = endTime,
                                        daysOfWeek = daysOfWeek,
                                        isEnabled = true,
                                        isStrictMode = isStrictMode,
                                        focusProfile = null,
                                        createdAt = createdAt,
                                        updatedAt = now
                                    )
                                    db.scheduledRestrictionDao().upsert(item)
                                    onSaved()
                                } catch (_: Exception) {}
                            }
                        }
                    ) {
                        Text("Save")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Schedule name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Schedule Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Schedule type
                Text("Type", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == ScheduleTypeIdentifiers.LAUNCH_BLOCKING,
                        onClick = { type = ScheduleTypeIdentifiers.LAUNCH_BLOCKING },
                        label = { Text("Launch Blocking") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    FilterChip(
                        selected = type == ScheduleTypeIdentifiers.INTERNET,
                        onClick = { type = ScheduleTypeIdentifiers.INTERNET },
                        label = { Text("Internet Blocking") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }

                // Strict mode (only shown for Internet type)
                if (type == ScheduleTypeIdentifiers.INTERNET) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Strict Mode", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Blocks all internet traffic when active",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = isStrictMode, onCheckedChange = { isStrictMode = it })
                    }
                }

                HorizontalDivider()

                // Days of week
                Text("Active Days", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                DayOfWeekPicker(selectedDays = daysOfWeek, onDaysChanged = { daysOfWeek = it })

                HorizontalDivider()

                // Time range
                Text("Time Range", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                TimeRangePicker(
                    startTimeMinutes = startTime,
                    endTimeMinutes = endTime,
                    onStartChanged = { startTime = it },
                    onEndChanged = { endTime = it }
                )

                HorizontalDivider()

                // Blocked apps section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Blocked Apps", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { showAppPicker = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Add App")
                    }
                }

                if (blockedApps.isEmpty()) {
                    Text(
                        "No apps selected. The schedule will block all apps of the selected type.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    blockedApps.forEach { appItem ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(appItem.appName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        appItem.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    blockedApps = blockedApps.filter { it.packageName != appItem.packageName }
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            currentSelected = blockedApps,
            restrictionKey = restrictionKey ?: "",
            onDismiss = { showAppPicker = false },
            onAppsSelected = { selected ->
                blockedApps = selected
                showAppPicker = false
            }
        )
    }
}

@Composable
private fun AppPickerDialog(
    currentSelected: List<ScheduledRestrictionAppItemModel>,
    restrictionKey: String,
    onDismiss: () -> Unit,
    onAppsSelected: (List<ScheduledRestrictionAppItemModel>) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedApps by remember { mutableStateOf(currentSelected.toMutableSet()) }

    val allApps = remember {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        val resolved = pm.queryIntentActivities(intent, 0)
        resolved.map {
            val appName = it.loadLabel(pm).toString()
            val pkgName = it.activityInfo.packageName
            AppListItem(appName, pkgName)
        }.sortedBy { it.appName }
    }

    val filteredApps = remember(searchQuery) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter { it.appName.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Select Apps", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (filteredApps.isEmpty()) {
                    Text("No apps found", style = MaterialTheme.typography.bodyMedium)
                } else {
                    filteredApps.forEach { app ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedApps.any { it.packageName == app.packageName },
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selectedApps = (selectedApps + ScheduledRestrictionAppItemModel(
                                            restrictionKey = restrictionKey,
                                            packageName = app.packageName,
                                            appName = app.appName
                                        )).toMutableSet()
                                    } else {
                                        selectedApps = selectedApps.filter { it.packageName != app.packageName }.toMutableSet()
                                    }
                                }
                            )
                            Text(app.appName, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAppsSelected(selectedApps.toList()) }) {
                Text("Done (" + selectedApps.size + " selected)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private data class AppListItem(
    val appName: String,
    val packageName: String
)
