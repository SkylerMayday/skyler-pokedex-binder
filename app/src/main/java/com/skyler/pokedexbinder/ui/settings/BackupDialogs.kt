package com.skyler.pokedexbinder.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.skyler.pokedexbinder.data.local.backup.ImportPreview
import com.skyler.pokedexbinder.publish.RestoreStep

/**
 * Dialogs for Settings' local Backup / Restore-from-file flows, kept out of `SettingsScreen` in the
 * same file-per-flow shape as `ui/publish/PublishDialog` and `ui/restore/RestoreDialog`.
 */

/** Backup only ever needs to say something when it failed — success opens the share sheet instead. */
@Composable
fun BackupDialog(state: BackupUiState, onDismiss: () -> Unit) {
    if (state !is BackupUiState.Error) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup failed") },
        text = { Text(state.message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun ImportDialog(state: ImportUiState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        is ImportUiState.NeedsConfirmation -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Restore from file?") },
            text = { Text(confirmationText(state.preview)) },
            confirmButton = { TextButton(onClick = onConfirm) { Text("Restore") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )

        is ImportUiState.Applying -> AlertDialog(
            onDismissRequest = { /* non-dismissable while applying */ },
            title = { Text("Restoring…") },
            text = { Text(if (state.step is RestoreStep.Done) "Finishing up…" else "Applying backup file…") },
            confirmButton = {}
        )

        is ImportUiState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Restore complete") },
            text = {
                Text(
                    "Restored ${state.restoredCount} slots • Cleared ${state.clearedCount} • " +
                        "Skipped ${state.skippedCount}"
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )

        is ImportUiState.Rejected -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Can't import this file") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )

        is ImportUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Restore failed") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )

        ImportUiState.Idle, ImportUiState.Preparing -> Unit
    }
}

/**
 * The two import paths do genuinely different things, so they can't share one warning: the JSON
 * path applies the snapshot as an overlay (rows the backup doesn't mention survive), while the
 * `.db` path swaps the whole database file out.
 */
private fun confirmationText(preview: ImportPreview): String = when (preview) {
    is ImportPreview.Json ->
        "This applies the backup over your current binder data. Slots the backup doesn't mention " +
            "are left as they are, and Card History entries you've added since are kept. Continue?"
    is ImportPreview.Db ->
        "This replaces your entire database with the backup file (schema v${preview.onDiskVersion}) " +
            "and restarts the app. Everything currently in your binders is overwritten. Continue?"
}
