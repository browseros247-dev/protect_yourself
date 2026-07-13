package protect.yourself.features.schedulePage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val DAY_LABELS = listOf("S", "M", "T", "W", "T", "F", "S")
private val DAY_FULL = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

@Composable
fun DayOfWeekPicker(
    selectedDays: String,
    onDaysChanged: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedSet = parseDays(selectedDays).toMutableSet()

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        DAY_LABELS.forEachIndexed { index, label ->
            val isSelected = selectedSet.contains(index)
            FilterChip(
                selected = isSelected,
                onClick = {
                    if (isSelected) {
                        selectedSet.remove(index)
                    } else {
                        selectedSet.add(index)
                    }
                    onDaysChanged(formatDays(selectedSet))
                },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

fun parseDays(daysOfWeek: String): Set<Int> {
    if (daysOfWeek == "*") return (0..6).toSet()
    return daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..6 }.toSet()
}

fun formatDays(days: Set<Int>): String {
    if (days.size == 7) return "*"
    return days.sorted().joinToString(",")
}

fun formatDaysSummary(daysOfWeek: String): String {
    if (daysOfWeek == "*") return "Every day"
    val days = parseDays(daysOfWeek)
    if (days.isEmpty()) return "No days"
    return days.sorted().map { DAY_FULL[it] }.joinToString(", ")
}
