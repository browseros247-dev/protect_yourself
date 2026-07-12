package protect.yourself.features.stopMePage

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import protect.yourself.database.stopMeDuration.StopMeDurationItemModel
import protect.yourself.theme.BrandOrange
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * StopMePage — in-app UI for managing Stop Me (focus mode) sessions.
 *
 * Features:
 *  - Active session card with live countdown (updates every second)
 *  - Stop button to end active session
 *  - Quick-start buttons (15/30/60/120 min)
 *  - Total completed session count
 *  - Scheduled sessions list with cancel buttons
 *
 * The page uses StopMeManager for all session operations and
 * observes the DB via Flow for live updates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopMePage(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: StopMePageViewModel = viewModel(
        factory = StopMePageViewModel.factory(context.applicationContext as android.app.Application)
    )
    val state by viewModel.state.collectAsState()

    // Ticker — refresh every second to update the countdown
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            viewModel.refreshActiveSession()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Stop Me")
                        if (state.sessionCount > 0) {
                            Text(
                                text = "${state.sessionCount} sessions completed",
                                style = MaterialTheme.typography.bodySmall,
                                color = BrandOrange
                            )
                        }
                    }
                },
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
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Active session card (only shown if a session is active)
            state.activeSession?.let { active ->
                item {
                    ActiveSessionCard(
                        remainingFormatted = state.remainingFormatted(),
                        durationMinutes = TimeUnit.MILLISECONDS.toMinutes(active.duration).toInt(),
                        onStop = { viewModel.stopActiveSession() },
                        isOperating = state.isOperating
                    )
                }
            }

            // Quick-start section (hidden while session is active)
            if (state.activeSession == null) {
                item {
                    Text(
                        text = "Start a focus session",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = "During a session, apps not on your whitelist will be blocked. Use this to focus without distractions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    QuickStartCard(
                        icon = Icons.Filled.Timer,
                        title = "15 minutes",
                        subtitle = "Quick focus burst",
                        enabled = !state.isOperating,
                        onClick = { viewModel.startInstantSession(15L * 60 * 1000) }
                    )
                }

                item {
                    QuickStartCard(
                        icon = Icons.Filled.Timer,
                        title = "30 minutes",
                        subtitle = "Pomodoro-style session",
                        enabled = !state.isOperating,
                        onClick = { viewModel.startInstantSession(30L * 60 * 1000) }
                    )
                }

                item {
                    QuickStartCard(
                        icon = Icons.Filled.Timer,
                        title = "60 minutes",
                        subtitle = "Deep work block",
                        enabled = !state.isOperating,
                        onClick = { viewModel.startInstantSession(60L * 60 * 1000) }
                    )
                }

                item {
                    QuickStartCard(
                        icon = Icons.Filled.Timer,
                        title = "120 minutes",
                        subtitle = "Extended focus session",
                        enabled = !state.isOperating,
                        onClick = { viewModel.startInstantSession(120L * 60 * 1000) }
                    )
                }
            }

            // Scheduled sessions section
            if (state.scheduledSessions.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Scheduled sessions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                items(state.scheduledSessions, key = { it.key }) { schedule ->
                    ScheduledSessionCard(
                        schedule = schedule,
                        onCancel = { viewModel.cancelScheduledSession(schedule.key) }
                    )
                }
            }

            // Footer info
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "How it works",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = BrandOrange
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• During a session, the accessibility service blocks any app not in your Stop Me whitelist.\n" +
                                "• The session ends automatically when the timer reaches zero.\n" +
                                "• You can stop a session early using the Stop button.\n" +
                                "• Sessions survive screen lock + app switches.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

// ===== Sub-components =====

@Composable
private fun ActiveSessionCard(
    remainingFormatted: String,
    durationMinutes: Int,
    onStop: () -> Unit,
    isOperating: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = BrandOrange.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Session active",
                style = MaterialTheme.typography.titleMedium,
                color = BrandOrange,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = remainingFormatted,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "remaining of $durationMinutes min",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onStop,
                enabled = !isOperating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.size(8.dp))
                Text("Stop Session")
            }
        }
    }
}

@Composable
private fun QuickStartCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick,
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Start",
                tint = BrandOrange,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ScheduledSessionCard(
    schedule: StopMeDurationItemModel,
    onCancel: () -> Unit
) {
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dayFormat = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    val triggerDate = Date(schedule.startTimeDayMillis)

    val dayNames = buildList {
        val names = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        for (i in 0..6) {
            if (schedule.days and (1 shl i) != 0) add(names[i])
        }
    }.joinToString(", ")

    // Calculate time of day from startTime (ms within day)
    val cal = Calendar.getInstance().apply {
        timeInMillis = schedule.startTime
    }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    val timeOfDayCal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val timeOfDay = timeFormat.format(timeOfDayCal.time)

    val durationMinutes = TimeUnit.MILLISECONDS.toMinutes(schedule.duration)

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
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$timeOfDay • $durationMinutes min",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Every: $dayNames",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Next: ${dayFormat.format(triggerDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Cancel scheduled session",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
