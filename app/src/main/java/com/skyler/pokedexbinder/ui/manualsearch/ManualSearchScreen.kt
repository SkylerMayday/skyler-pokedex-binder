package com.skyler.pokedexbinder.ui.manualsearch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.skyler.pokedexbinder.data.model.TcgCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ManualSearchScreen(
    onCardSelected: (TcgCard) -> Unit,
    onBack: () -> Unit,
    viewModel: ManualSearchViewModel = hiltViewModel()
) {
    var query by remember { mutableStateOf("") }
    val state by viewModel.state.collectAsState()
    val promoOnly by viewModel.promoOnly.collectAsState()
    var previewCard by remember { mutableStateOf<TcgCard?>(null) }

    previewCard?.let { card ->
        CardPreviewDialog(card = card, onDismiss = { previewCard = null })
    }

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
                label = { Text("Card name or promo number (e.g. SWSH236)") },
                trailingIcon = {
                    IconButton(onClick = { viewModel.search(query) }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) })
            )
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilterChip(
                    selected = promoOnly,
                    onClick = { viewModel.togglePromoFilter() },
                    label = { Text("Promos only") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                        )
                    }
                )
            }

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
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("No cards found", style = MaterialTheme.typography.bodyLarge)
                        }
                    } else {
                        Text(
                            "Tap to select · Long-press to preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(s.cards, key = { it.id }) { card ->
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
                                    modifier = Modifier.combinedClickable(
                                        onClick = { onCardSelected(card) },
                                        onLongClick = { previewCard = card }
                                    )
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                is SearchState.Error -> {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.retry() }) { Text("Try Again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardPreviewDialog(card: TcgCard, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = MaterialTheme.shapes.large) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                )
                Text(card.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${card.setName} · #${card.number}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}
