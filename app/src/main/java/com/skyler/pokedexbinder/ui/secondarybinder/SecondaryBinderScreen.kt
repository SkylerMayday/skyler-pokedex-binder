package com.skyler.pokedexbinder.ui.secondarybinder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryBinderScreen(
    onAddCard: () -> Unit = {},
    viewModel: SecondaryBinderViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState(initial = emptyList())

    Scaffold(
        topBar = { TopAppBar(title = { Text("Card History") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddCard) {
                Icon(Icons.Default.Add, contentDescription = "Add card manually")
            }
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No cards yet — tap + to add one")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding)) {
                items(entries, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.pokemonName) },
                        supportingContent = { Text("Card ID: ${entry.cardId}") },
                        leadingContent = {
                            AsyncImage(
                                model = entry.cardImageUrl,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp)
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
