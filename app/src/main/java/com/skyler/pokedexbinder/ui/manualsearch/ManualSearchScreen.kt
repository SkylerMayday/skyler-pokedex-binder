package com.skyler.pokedexbinder.ui.manualsearch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.skyler.pokedexbinder.data.model.TcgCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualSearchScreen(
    onCardSelected: (TcgCard) -> Unit,
    onBack: () -> Unit,
    viewModel: ManualSearchViewModel = hiltViewModel()
) {
    var query by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search Cards") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Card name or set number") },
                trailingIcon = {
                    IconButton(onClick = { viewModel.search(query) }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            when (val s = state) {
                is SearchState.Idle -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("Search for a card above")
                    }
                }
                is SearchState.Loading -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is SearchState.Results -> {
                    if (s.cards.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("No cards found")
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.cards) { card ->
                                ListItem(
                                    headlineContent = { Text(card.name) },
                                    supportingContent = { Text("${card.setName} · #${card.number}") },
                                    leadingContent = {
                                        AsyncImage(
                                            model = card.imageUrl,
                                            contentDescription = null,
                                            modifier = Modifier.size(56.dp)
                                        )
                                    },
                                    modifier = Modifier.clickable { onCardSelected(card) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                is SearchState.Error -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
