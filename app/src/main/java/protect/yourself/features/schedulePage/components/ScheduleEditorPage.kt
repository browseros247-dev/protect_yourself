package protect.yourself.features.schedulePage.components

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.database.core.AppDatabase
import protect.yourself.features.schedulePage.SchedulePageViewModel
import protect.yourself.features.schedulePage.identifiers.ScheduleTypeIdentifiers
import protect.yourself.theme.BrandOrange
import java.util.Calendar

/**
 * ScheduleEditorPage — create or edit a scheduled restriction.
 *
 * @param editKey If non-null, edit the existing schedule with this key.
 *                If null, create a new schedule.
 * @param onBack Called when the user presses Back or saves.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorPage(
    editKey: String? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SchedulePageViewModel = viewModel(
        factory = SchedulePageViewModel.factory(
            context.applicationContext as android.app.Application,
            AppDatabase.getInstance(context)
        )
    )

    // Form state
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ScheduleTypeIdentifiers.INTERNET) }
    var startMinutes by remember { mutableIntStateOf(540) }  // 9:00 AM default
    var endMinutes by remember { mutableIntStateOf(1020) }   // 5:00 PM default
    val selectedDays = remember { mutableStateListOf<Int>().apply { addAll(listOf(1, 2, 3, 4, 5)) } } // Mon-Fri default
    val selectedApps = remember { mutableStateListOf<Pair<String, String>>() } // (packageName, appName)

    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var isLoadingExisting by remember { mutableStateOf(editKey != null) }

    // Load existing schedule for editing
    LaunchedEffect(editKey) {
        if (editKey != null) {
            try {
                val db = AppDatabase.getInstance(context)
                val rule = db.scheduledRestrictionDao().getByKey(editKey)
                if (rule != null) {
                    name = rule.name
                    type = ScheduleTypeIdentifiers.fromValue(rule.type)
                    startMinutes = rule.startTimeMinutes
                    endMinutes = rule.endTimeMinutes
                    selectedDays.clear()
                    for (i in 0..6) {
                        if (rule.daysOfWeek and (1 shl i) != 0) {
                            selectedDays.add(i)
                        }
                    }
                    val apps = db.scheduledRestrictionAppDao().getAppsForRule(editKey)
                    selectedApps.clear()
                    selectedApps.addAll(apps.map { it.packageName to it.appName })
                }
            } catch (t: Throwable) {
                // Ignore — start with defaults
            } finally {
                isLoadingExisting = false
            }
        } else {
            isLoadingExisting = false
        }
    }

    if (isLoadingExisting) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = BrandOrange)
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editKey != null) "Edit Schedule" else "New Schedule") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Name field
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Schedule name") },
                    placeholder = { Text("e.g. YouTube Work Hours") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaultsColors()
                )
            }

            // Type selector
            item {
                Text("Restriction Type", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ScheduleTypeIdentifiers.values().forEachIndexed { index, t ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(index, ScheduleTypeIdentifiers.values().size)
                        ) {
                            Text(when (t) {
                                ScheduleTypeIdentifiers.INTERNET -> "Internet"
                                ScheduleTypeIdentifiers.LAUNCH -> "Launch"
                                ScheduleTypeIdentifiers.BOTH -> "Both"
                            }, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Time range
            item {
                Text("Time Window", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TimeField(
                        label = "Start",
                        minutes = startMinutes,
                        onClick = { showStartTimePicker = true },
                        modifier = Modifier.weight(1f)
                    )
                    TimeField(
                        label = "End",
                        minutes = endMinutes,
                        onClick = { showEndTimePicker = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Day-of-week chips
            item {
                Text("Repeat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                val dayLabels = listOf("S", "M", "T", "W", "T", "F", "S")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (i in 0..6) {
                        FilterChip(
                            selected = selectedDays.contains(i),
                            onClick = {
                                if (selectedDays.contains(i)) {
                                    selectedDays.remove(i)
                                } else {
                                    selectedDays.add(i)
                                }
                            },
                            label = { Text(dayLabels[i]) }
                        )
                    }
                }
            }

            // App selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showAppPicker = true },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, tint = BrandOrange)
                        Spacer(modifier = Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Apps", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${selectedApps.size} app${if (selectedApps.size != 1) "s" else ""} selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text("Tap to edit", color = BrandOrange, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // Save button
            item {
                val isValid = name.isNotBlank() && selectedDays.isNotEmpty() && selectedApps.isNotEmpty()
                Button(
                    onClick = {
                        val daysBitmask = selectedDays.fold(0) { acc, i -> acc or (1 shl i) }
                        if (editKey != null) {
                            viewModel.updateSchedule(
                                key = editKey,
                                name = name,
                                type = type.value,
                                startTimeMinutes = startMinutes,
                                endTimeMinutes = endMinutes,
                                daysOfWeek = daysBitmask,
                                selectedPackages = selectedApps.toList()
                            )
                        } else {
                            viewModel.createSchedule(
                                name = name,
                                type = type.value,
                                startTimeMinutes = startMinutes,
                                endTimeMinutes = endMinutes,
                                daysOfWeek = daysBitmask,
                                selectedPackages = selectedApps.toList()
                            )
                        }
                        onBack()
                    },
                    enabled = isValid,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandOrange,
                        contentColor = Color.White
                    )
                ) {
                    Text("Save Schedule", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }

    // Time picker dialogs
    if (showStartTimePicker) {
        val state = rememberTimePickerState(
            initialHour = startMinutes / 60,
            initialMinute = startMinutes % 60,
            is24Hour = false
        )
        TimePickerDialog(
            state = state,
            onConfirm = {
                startMinutes = state.hour * 60 + state.minute
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false }
        )
    }
    if (showEndTimePicker) {
        val state = rememberTimePickerState(
            initialHour = endMinutes / 60,
            initialMinute = endMinutes % 60,
            is24Hour = false
        )
        TimePickerDialog(
            state = state,
            onConfirm = {
                endMinutes = state.hour * 60 + state.minute
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false }
        )
    }

    // App picker dialog
    if (showAppPicker) {
        AppPickerDialog(
            selectedApps = selectedApps,
            onDismiss = { showAppPicker = false }
        )
    }
}

@Composable
private fun TimeField(
    label: String,
    minutes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val h = minutes / 60
    val m = minutes % 60
    val ampm = if (h < 12) "AM" else "PM"
    val displayH = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
    val timeStr = "%d:%02d %s".format(displayH, m, ampm)

    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(timeStr, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    state: TimePickerState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        text = { TimePicker(state = state) }
    )
}

@Composable
private fun AppPickerDialog(
    selectedApps: MutableList<Pair<String, String>>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<Triple<String, String, Boolean>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName } // exclude self
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { ai ->
                    val name = pm.getApplicationLabel(ai).toString()
                    val pkg = ai.packageName
                    val isSelected = selectedApps.any { it.first == pkg }
                    Triple(name, pkg, isSelected)
                }
                .sortedBy { it.first.lowercase() }
            installedApps = packages
        } catch (t: Throwable) {
            // Ignore
        }
        loading = false
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        title = { Text("Select Apps") },
        text = {
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BrandOrange)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(installedApps, key = { it.second }) { (appName, pkg, isSelected) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val existing = selectedApps.indexOfFirst { it.first == pkg }
                                    if (existing >= 0) {
                                        selectedApps.removeAt(existing)
                                    } else {
                                        selectedApps.add(pkg to appName)
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = selectedApps.any { it.first == pkg },
                                onCheckedChange = {
                                    val existing = selectedApps.indexOfFirst { it.first == pkg }
                                    if (existing >= 0) {
                                        selectedApps.removeAt(existing)
                                    } else {
                                        selectedApps.add(pkg to appName)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(appName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun OutlinedTextFieldDefaultsColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrandOrange,
    focusedLabelColor = BrandOrange,
    cursorColor = BrandOrange
)
