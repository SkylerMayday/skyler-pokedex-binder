package com.skyler.pokedexbinder.ui.publish

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.skyler.pokedexbinder.publish.PublishStep

private val PUBLISH_STEPS_ORDER = listOf(
    PublishStep.FetchingCurrent,
    PublishStep.Uploading,
    PublishStep.NotifyingDiscord
)

private fun stepLabel(step: PublishStep): String = when (step) {
    is PublishStep.FetchingCurrent -> "Fetching current"
    is PublishStep.Uploading -> "Uploading"
    is PublishStep.NotifyingDiscord -> "Notifying Discord"
    is PublishStep.Done -> "Done"
    is PublishStep.NoChanges -> "No changes"
}

private fun stepOrdinal(step: PublishStep): Int = when (step) {
    is PublishStep.FetchingCurrent -> 0
    is PublishStep.Uploading -> 1
    is PublishStep.NotifyingDiscord -> 2
    is PublishStep.Done -> 3
    is PublishStep.NoChanges -> 3
}

private fun formatElapsed(elapsedMs: Long): String = "%.1fs".format(elapsedMs / 1000f)

@Composable
fun PublishDialog(
    state: PublishUiState,
    pageUrl: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    when (state) {
        is PublishUiState.Idle -> Unit

        is PublishUiState.Running -> {
            val activeOrdinal = stepOrdinal(state.step)
            AlertDialog(
                onDismissRequest = { /* non-dismissable while running */ },
                title = { Text("Publishing…") },
                text = {
                    Column {
                        PUBLISH_STEPS_ORDER.forEachIndexed { index, step ->
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

        is PublishUiState.Done -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Published") },
                text = {
                    Column {
                        Text(
                            "Added ${state.diff.added}, Replaced ${state.diff.replaced}, " +
                                "Removed ${state.diff.removed} • Pokédex ${state.diff.pokedexComplete}/${state.diff.pokedexTotal}"
                        )
                        Text("Took ${formatElapsed(state.elapsedMs)}")
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("Close") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))
                        context.startActivity(intent)
                    }) { Text("View page") }
                }
            )
        }

        is PublishUiState.NoChanges -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("No changes") },
                text = { Text("No changes since last publish.") },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("OK") }
                }
            )
        }

        is PublishUiState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Publish failed") },
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
