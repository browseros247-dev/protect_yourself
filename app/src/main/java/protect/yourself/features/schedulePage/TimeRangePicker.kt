package protect.yourself.features.schedulePage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TimeRangePicker(
    startTimeMinutes: Int,
    endTimeMinutes: Int,
    onStartChanged: (Int) -> Unit,
    onEndChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Start", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { showStartPicker = true }) {
                Text(formatTime(startTimeMinutes))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("End", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { showEndPicker = true }) {
                Text(formatTime(endTimeMinutes))
            }
        }
        if (endTimeMinutes < startTimeMinutes) {
            Text(
                text = "Crosses midnight",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showStartPicker) {
        TimePickerDialog(
            initialMinutes = startTimeMinutes,
            onDismiss = { showStartPicker = false },
            onConfirm = { minutes ->
                onStartChanged(minutes)
                showStartPicker = false
            }
        )
    }
    if (showEndPicker) {
        TimePickerDialog(
            initialMinutes = endTimeMinutes,
            onDismiss = { showEndPicker = false },
            onConfirm = { minutes ->
                onEndChanged(minutes)
                showEndPicker = false
            }
        )
    }
}

@Composable
private fun TimePickerDialog(
    initialMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val initialHour = initialMinutes / 60
    val initialMinute = initialMinutes % 60
    var hour by remember { mutableStateOf(initialHour) }
    var minute by remember { mutableStateOf(initialMinute) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Time") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = hour.toString().padStart(2, '0'),
                        onValueChange = { text ->
                            val h = text.filter { it.isDigit() }.take(2).toIntOrNull()
                            if (h != null && h in 0..23) hour = h
                        },
                        label = { Text("HH") },
                        singleLine = true,
                        modifier = Modifier.width(80.dp)
                    )
                    Text(" : ", style = MaterialTheme.typography.titleLarge)
                    androidx.compose.material3.OutlinedTextField(
                        value = minute.toString().padStart(2, '0'),
                        onValueChange = { text ->
                            val m = text.filter { it.isDigit() }.take(2).toIntOrNull()
                            if (m != null && m in 0..59) minute = m
                        },
                        label = { Text("MM") },
                        singleLine = true,
                        modifier = Modifier.width(80.dp)
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(hour * 60 + minute) }) {
                Text("OK")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun formatTime(minutesFromMidnight: Int): String {
    val h = minutesFromMidnight / 60
    val m = minutesFromMidnight % 60
    val hh = h.toString().padStart(2, '0')
    val mm = m.toString().padStart(2, '0')
    return hh + ":" + mm
}
