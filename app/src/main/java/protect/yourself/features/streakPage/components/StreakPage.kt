package protect.yourself.features.streakPage.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import protect.yourself.database.core.AppDatabase
import protect.yourself.features.streakPage.StreakPageViewModel
import protect.yourself.features.streakPage.identifiers.RelapseTypeIdentifiers
import protect.yourself.features.streakPage.identifiers.StreakAchievement
import protect.yourself.theme.BrandOrange

/**
 * StreakPage — the "Streak" tab content.
 *
 * Shows:
 *  - Large streak day count with fire animation
 *  - Next achievement progress
 *  - Unlocked achievements grid
 *  - "Record relapse" button
 *  - Streak history (recent relapses)
 */
@Composable
fun StreakPage() {
    val context = LocalContext.current
    val viewModel: StreakPageViewModel = viewModel(
        factory = StreakPageViewModel.factory(AppDatabase.getInstance(context))
    )
    val state by viewModel.state.collectAsState()
    var showRelapseDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // === Running streak card ===
        item { RunningStreakCard(state.currentStreakDays) }

        // === Next achievement ===
        state.nextAchievement?.let { next ->
            item { NextAchievementCard(state.currentStreakDays, next) }
        }

        // === Unlocked achievements ===
        if (state.unlockedAchievements.isNotEmpty()) {
            item {
                Text(
                    text = "Achievements",
                    style = MaterialTheme.typography.titleMedium,
                    color = BrandOrange,
                    fontWeight = FontWeight.Bold
                )
            }
            items(state.unlockedAchievements) { achievement ->
                AchievementCard(achievement)
            }
        }

        // === Stats ===
        item {
            StatsCard(
                activeDays = state.activeDayCount,
                relapseCount = state.relapseCount,
                bestStreak = state.bestStreakDays
            )
        }

        // === Record relapse button ===
        item {
            Button(
                onClick = { showRelapseDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Record relapse")
            }
        }

        // === Recent history ===
        if (state.streakHistory.isNotEmpty()) {
            item {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.titleMedium,
                    color = BrandOrange,
                    fontWeight = FontWeight.Bold
                )
            }
            items(state.streakHistory.take(20)) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        val date = java.util.Date(item.startTime)
                        val isRelapse = item.type.isNotBlank()
                        Text(
                            text = if (isRelapse) "Relapse" else "Active day",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isRelapse) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
                                .format(date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isRelapse) {
                            val type = RelapseTypeIdentifiers.fromStorageValue(item.type)
                            Text(
                                text = "Type: ${type?.let { RelapseTypeIdentifiers.getDisplayName(it) } ?: item.type}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (item.freeText.isNotBlank()) {
                            Text(
                                text = "Note: ${item.freeText}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }

    if (showRelapseDialog) {
        RelapseDialog(
            onDismiss = { showRelapseDialog = false },
            onRecord = { type, note ->
                viewModel.recordRelapse(type, note)
                showRelapseDialog = false
            }
        )
    }
}

@Composable
private fun RunningStreakCard(days: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lottie fire animation
            val composition by rememberLottieComposition(
                LottieCompositionSpec.Asset("streak_fire.json")
            )
            val progress by animateLottieCompositionAsState(
                composition,
                iterations = Int.MAX_VALUE
            )

            // Pulsing scale effect
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier
                    .size(120.dp)
                    .scale(scale)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = days.toString(),
                fontSize = 64.sp,
                fontWeight = FontWeight.ExtraBold,
                color = BrandOrange
            )

            Text(
                text = "days",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NextAchievementCard(currentDays: Int, next: StreakAchievement) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Next achievement",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${next.title} (${next.daysRequired} days)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            val remaining = (next.daysRequired - currentDays).coerceAtLeast(0)
            Text(
                text = "$remaining days to go",
                style = MaterialTheme.typography.bodyMedium,
                color = BrandOrange
            )
        }
    }
}

@Composable
private fun AchievementCard(achievement: StreakAchievement) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "★",
                fontSize = 32.sp,
                color = BrandOrange
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column {
                Text(
                    text = achievement.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${achievement.daysRequired} days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatsCard(activeDays: Int, relapseCount: Int, bestStreak: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = bestStreak.toString(),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandOrange
                )
                Text(
                    text = "best streak",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = activeDays.toString(),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandOrange
                )
                Text(
                    text = "active days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = relapseCount.toString(),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "relapses",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RelapseDialog(
    onDismiss: () -> Unit,
    onRecord: (RelapseTypeIdentifiers, String) -> Unit
) {
    var selectedType by remember { mutableStateOf(RelapseTypeIdentifiers.URGE) }
    var note by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record relapse") },
        text = {
            Column {
                Text("What triggered it?")
                Spacer(modifier = Modifier.height(8.dp))
                RelapseTypeIdentifiers.values().forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selectedType == type,
                            onClick = { selectedType = type }
                        )
                        Text(
                            text = RelapseTypeIdentifiers.getDisplayName(type),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onRecord(selectedType, note) }
            ) {
                Text("Record")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
