package com.skyler.pokedexbinder.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skyler.pokedexbinder.data.local.ResetSummary

/** Dialogs for Settings' "Reset collection data" flow, driven by [ResetCollectionViewModel]. */
@Composable
fun ResetCollectionDialog(
    state: ResetUiState,
    onExport: () -> Unit,
    onConfirm: (typed: String) -> Unit,
    onShare: (Intent) -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        ResetUiState.Idle -> Unit

        is ResetUiState.Confirm1 -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Reset collection data?") },
            text = { Text(confirm1Text(state.summary)) },
            confirmButton = { TextButton(onClick = onExport) { Text("Export backup & continue") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )

        ResetUiState.Exporting -> AlertDialog(
            onDismissRequest = { /* non-dismissable while exporting */ },
            title = { Text("Exporting backup…") },
            text = { Text("Nothing has been reset yet.") },
            confirmButton = {}
        )

        is ResetUiState.Confirm2 -> {
            var typed by rememberSaveable { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Type $RESET_CONFIRM_WORD to confirm") },
                text = {
                    Column {
                        Text(
                            "Backup created. Make sure you saved the file somewhere off this phone " +
                                "(use Share). This can only be undone with Restore from file."
                        )
                        TextButton(onClick = { onShare(state.shareIntent) }) { Text("Share backup again") }
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text(RESET_CONFIRM_WORD) },
                            singleLine = true,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onConfirm(typed) }, enabled = typed == RESET_CONFIRM_WORD) {
                        Text("Reset")
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
            )
        }

        ResetUiState.Wiping -> AlertDialog(
            onDismissRequest = { /* non-dismissable while wiping */ },
            title = { Text("Resetting…") },
            text = { Text("Clearing collection data.") },
            confirmButton = {}
        )

        ResetUiState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Collection reset") },
            text = { Text("Card assignments are cleared. Scan or search to fill your binders again.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )

        is ResetUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Reset didn't complete") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
    }
}

private fun confirm1Text(s: ResetSummary): String =
    "Clears: ${s.mainBinderCards} Pokédex cards, ${s.unownCards} Unown cards, " +
        "${s.connectingArtCards} Connecting Art cards, ${s.cardHistoryCards} Card History entries, " +
        "and the Personal Collection (${s.personalCollectionOwned} owned) with its cache.\n\n" +
        "Kept: slot layouts, Connecting Art grid layouts, all settings, and your API keys, GitHub token " +
        "and Discord webhook.\n\n" +
        "A backup is exported first. If it fails, nothing is reset."

/**
 * Shown in both restore confirmations: a backup/snapshot made before the reset carries the old
 * card ids and images, so restoring it undoes the reset.
 */
const val PRE_RESET_RESTORE_WARNING =
    "Don't restore a backup or snapshot made before the collection reset: it brings back the old " +
        "card ids and images and undoes the reset."
