package com.skyler.pokedexbinder.ui.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skyler.pokedexbinder.publish.RestoreStep

private val RESTORE_STEPS_ORDER = listOf(
    RestoreStep.Fetching,
    RestoreStep.Restoring
)

private fun stepLabel(step: RestoreStep): String = when (step) {
    is RestoreStep.Fetching -> "Fetching snapshot"
    is RestoreStep.Restoring -> "Restoring"
    is RestoreStep.Done -> "Done"
    is RestoreStep.NoSnapshot -> "No snapshot"
}

private fun stepOrdinal(step: RestoreStep): Int = when (step) {
    is RestoreStep.Fetching -> 0
    is RestoreStep.Restoring -> 1
    is RestoreStep.Done -> 2
    is RestoreStep.NoSnapshot -> 2
}

private fun formatElapsed(elapsedMs: Long): String = "%.1fs".format(elapsedMs / 1000f)

@Composable
fun RestoreDialog(
    state: RestoreUiState,
    onDismiss: () -> Unit
) {
    when (state) {
        is RestoreUiState.Idle -> Unit

        is RestoreUiState.Running -> {
            val activeOrdinal = stepOrdinal(state.step)
            AlertDialog(
                onDismissRequest = { /* non-dismissable while running */ },
                title = { Text("Restoring…") },
                text = {
                    Column {
                        RESTORE_STEPS_ORDER.forEachIndexed { index, step ->
                            StepRow(
                                label = stepLabel(step),
                                isDone = index < activeOrdinal,
                                isActive = index == activeOrdinal
                            )
                        }
                        StepRow(label = "Done", isDone = false, isActive = false)
                        Spacer(Modifier.height(12.dp))
                        Text("Elapsed: ${formatElapsed(state.elapsedMs)}")
                    }
                },
                confirmButton = {}
            )
        }

        is RestoreUiState.Done -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Restore complete") },
                text = {
                    Column {
                        Text(
                            "Restored ${state.restoredCount} slots • Cleared ${state.clearedCount} • " +
                                "Skipped ${state.skippedCount}"
                        )
                        Text("Took ${formatElapsed(state.elapsedMs)}")
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            )
        }

        is RestoreUiState.NoSnapshot -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Nothing to restore") },
                text = { Text("No published snapshot found on GitHub yet. Publish first, then you can restore.") },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
            )
        }

        is RestoreUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Restore failed") },
                text = {
                    Column {
                        Text("Failed at: ${stepLabel(state.step)}")
                        Text(state.message)
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            )
        }
    }
}

@Composable
private fun StepRow(label: String, isDone: Boolean, isActive: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        when {
            isDone -> Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            isActive -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else -> Icon(
                Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(0.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
