package com.skyler.pokedexbinder.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skyler.pokedexbinder.data.model.Language

/**
 * Shared edit-details modal used by every binder. [showLockAndRemarks] toggles the
 * remarks field + lock switch — only Pokédex and Unown expose those; the other three
 * binders (Connecting Art, Personal Collection, Card History) are language-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditCardDetailsDialog(
    currentLanguage: Language,
    currentRemarks: String? = null,
    currentIsLocked: Boolean = false,
    showLockAndRemarks: Boolean,
    onDismiss: () -> Unit,
    onSave: (language: Language, remarks: String?, isLocked: Boolean) -> Unit
) {
    var language by remember { mutableStateOf(currentLanguage) }
    var remarks by remember { mutableStateOf(currentRemarks.orEmpty()) }
    var isLocked by remember { mutableStateOf(currentIsLocked) }
    var languageDropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Details") },
        text = {
            Column {
                Text("Language", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { languageDropdownExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(language.displayName, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = languageDropdownExpanded,
                    onDismissRequest = { languageDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Language.entries.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(entry.displayName) },
                            onClick = {
                                language = entry
                                languageDropdownExpanded = false
                            }
                        )
                    }
                }

                if (showLockAndRemarks) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = remarks,
                        onValueChange = { remarks = it },
                        label = { Text("Remarks") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Locked", modifier = Modifier.weight(1f))
                        Switch(checked = isLocked, onCheckedChange = { isLocked = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(language, if (showLockAndRemarks) remarks.ifBlank { null } else null, isLocked)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
