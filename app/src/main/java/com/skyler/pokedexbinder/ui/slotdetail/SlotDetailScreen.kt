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

            Button(
                onClick = { slot?.let { onSearch(it.effectiveSearchName, it.id, occupied) } },
                modifier = Modifier.fillMaxWidth(),
                enabled = slot != null
            ) {
                Text(if (occupied) "Replace" else "Search")
            }

            if (occupied) {
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
    }
}
