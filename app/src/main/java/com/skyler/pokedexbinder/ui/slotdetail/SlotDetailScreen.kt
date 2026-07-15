package com.skyler.pokedexbinder.ui.slotdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.ui.components.EditCardDetailsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotDetailScreen(
    pokemonId: String,
    onBack: () -> Unit,
    onSearch: (pokemonName: String, slotId: String, replacing: Boolean) -> Unit,
    viewModel: SlotDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(pokemonId) { viewModel.loadSlot(pokemonId) }
    val slot by viewModel.slot.collectAsState()
    var showRemoveDialog by remember { mutableStateOf(false) }
    var showEditDetailsDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.blockedByLock.collect { showBlockedDialog = true }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("Remove card?") },
            text = { Text("This will clear the card from ${slot?.name ?: "this slot"}. The slot will be empty.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCard()
                    showRemoveDialog = false
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text("Cancel") }
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

    if (showEditDetailsDialog) {
        slot?.let { currentSlot ->
            EditCardDetailsDialog(
                currentLanguage = Language.fromRaw(currentSlot.language),
                currentRemarks = currentSlot.remarks,
                currentIsLocked = currentSlot.isLocked,
                showLockAndRemarks = true,
                onDismiss = { showEditDetailsDialog = false },
                onSave = { language, remarks, isLocked ->
                    viewModel.updateDetails(language, remarks, isLocked)
                    showEditDetailsDialog = false
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(slot?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (slot?.isOccupied == true && slot!!.assignedCardImageUrl != null) {
                AsyncImage(
                    model = slot!!.assignedCardImageUrl,
                    contentDescription = "Assigned card",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (slot?.isOccupied == true) "Card image unavailable"
                        else "No card assigned yet",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            val occupied = slot?.isOccupied == true
            val locked = slot?.isLocked == true

            Button(
                onClick = { slot?.let { onSearch(it.effectiveSearchName, it.id, occupied) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = slot != null && !(occupied && locked)
            ) {
                Text(if (occupied) "Replace" else "Search")
            }

            if (occupied) {
                if (locked) {
                    OutlinedButton(
                        onClick = { showEditDetailsDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Unlock to Remove")
                    }
                } else {
                    OutlinedButton(
                        onClick = { showRemoveDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Remove Card")
                    }
                }
            }

            if (slot != null) {
                OutlinedButton(
                    onClick = { showEditDetailsDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Edit Details")
                }
            }
        }
    }
}
