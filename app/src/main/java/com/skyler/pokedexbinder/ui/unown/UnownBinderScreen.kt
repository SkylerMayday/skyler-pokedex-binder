package com.skyler.pokedexbinder.ui.unown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.ui.common.DimmableCardImage
import com.skyler.pokedexbinder.ui.components.EditCardDetailsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnownBinderScreen(
    onOpenDrawer: () -> Unit,
    onOpenSlotSearch: (letterId: String) -> Unit,
    viewModel: UnownBinderViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    var assignedSheetTarget by remember { mutableStateOf<UnownBinderEntry?>(null) }
    var editDetailsTarget by remember { mutableStateOf<UnownBinderEntry?>(null) }
    var showBlockedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.blockedByLock.collect { showBlockedDialog = true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Unown") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(8.dp),
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            items(entries, key = { it.letterId }) { entry ->
                Card(
                    modifier = Modifier
                        .padding(4.dp)
                        .aspectRatio(0.72f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (entry.assignedCardId == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        viewModel.beginAssign(entry.letterId)
                                        onOpenSlotSearch(entry.letterId)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(entry.letterId, style = MaterialTheme.typography.titleLarge)
                            }
                        } else {
                            DimmableCardImage(
                                imageUrl = entry.assignedCardImageUrl.orEmpty(),
                                contentDescription = entry.assignedCardName,
                                dimmed = false,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { assignedSheetTarget = entry }
                            )
                        }
                        if (entry.isLocked) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(20.dp)
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = "Locked", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    assignedSheetTarget?.let { entry ->
        SlotActionSheet(
            entry = entry,
            onDismiss = { assignedSheetTarget = null },
            onReassign = {
                assignedSheetTarget = null
                viewModel.beginAssign(entry.letterId)
                onOpenSlotSearch(entry.letterId)
            },
            onRemoveCard = {
                viewModel.clearCard(entry.letterId)
                assignedSheetTarget = null
            },
            onEditDetails = {
                assignedSheetTarget = null
                editDetailsTarget = entry
            }
        )
    }

    editDetailsTarget?.let { entry ->
        EditCardDetailsDialog(
            currentLanguage = Language.fromRaw(entry.language),
            currentRemarks = entry.remarks,
            currentIsLocked = entry.isLocked,
            showLockAndRemarks = true,
            onDismiss = { editDetailsTarget = null },
            onSave = { language, remarks, isLocked ->
                viewModel.updateDetails(entry.letterId, language, remarks, isLocked)
                editDetailsTarget = null
            }
        )
    }

    if (showBlockedDialog) {
        AlertDialog(
            onDismissRequest = { showBlockedDialog = false },
            title = { Text("Card locked") },
            text = { Text("Unlock this card before removing or reassigning it.") },
            confirmButton = {
                TextButton(onClick = { showBlockedDialog = false }) { Text("OK") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotActionSheet(
    entry: UnownBinderEntry,
    onDismiss: () -> Unit,
    onReassign: () -> Unit,
    onRemoveCard: () -> Unit,
    onEditDetails: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(entry.assignedCardName ?: "Unown ${entry.letterId}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            if (entry.isLocked) {
                Button(onClick = onEditDetails, modifier = Modifier.fillMaxWidth()) {
                    Text("Unlock to Edit")
                }
            } else {
                Button(onClick = onReassign, modifier = Modifier.fillMaxWidth()) {
                    Text("Reassign")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRemoveCard, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove Card")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onEditDetails, modifier = Modifier.fillMaxWidth()) {
                Text("Edit Details")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}
