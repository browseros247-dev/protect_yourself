package protect.yourself.features.schedulePage

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePage() {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val viewModel: SchedulePageViewModel = viewModel(
        factory = SchedulePageViewModel.factory(app)
    )
    val schedules by viewModel.schedules.collectAsState()

    var editingKey by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ScheduledRestrictionItemModel?>(null) }

    if (showEditor) {
        ScheduleEditorPage(
            restrictionKey = editingKey,
            onBack = {
                showEditor = false
                editingKey = null
            },
            onSaved = {
                showEditor = false
                editingKey = null
            }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Scheduled Restrictions") }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        editingKey = null
                        showEditor = true
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Schedule")
                }
            }
        ) { padding ->
            if (schedules.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            modifier = Modifier.padding(bottom = 8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "No schedules yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Tap + to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(schedules, key = { it.restrictionKey }) { schedule ->
                        ScheduleListItem(
                            schedule = schedule,
                            onToggle = {
                                viewModel.toggleEnabled(schedule.restrictionKey, schedule.isEnabled)
                            },
                            onDelete = {
                                deleteTarget = schedule
                            },
                            onClick = {
                                editingKey = schedule.restrictionKey
                                showEditor = true
                            }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Schedule", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete this schedule?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSchedule(target.restrictionKey)
                        deleteTarget = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
