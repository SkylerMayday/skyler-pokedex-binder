package com.skyler.pokedexbinder.ui.slotdetail

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
    onScanCard: (String) -> Unit = {},
    onManualSearch: (String) -> Unit = {},
    viewModel: SlotDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(pokemonId) { viewModel.loadSlot(pokemonId) }
    val slot by viewModel.slot.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(slot?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (slot?.isOccupied == true) {
                AsyncImage(
                    model = slot!!.assignedCardImageUrl,
                    contentDescription = "Assigned card",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Button(onClick = { onScanCard(pokemonId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Replace with Scanned Card")
                }
                OutlinedButton(onClick = { onManualSearch(pokemonId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Search Manually")
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No card assigned yet", style = MaterialTheme.typography.bodyLarge)
                }
                Button(onClick = { onScanCard(pokemonId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Scan a Card")
                }
                OutlinedButton(onClick = { onManualSearch(pokemonId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Search Manually")
                }
            }
        }
    }
}
