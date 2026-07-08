package com.skyler.pokedexbinder.ui.personalcollection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.skyler.pokedexbinder.ui.common.DimmableCardImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalCollectionScreen(
    onOpenDrawer: () -> Unit,
    viewModel: PersonalCollectionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showSheetFor by remember { mutableStateOf<PersonalCard?>(null) }

    val hasAnyCards = state.sections.values.any { it.isNotEmpty() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal Collection") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refreshAll() },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            if (state.isRefreshing && !hasAnyCards) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.errorMessage != null && !hasAnyCards) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.refreshAll() }) { Text("Retry") }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    PERSONAL_COLLECTION_SECTIONS.forEach { section ->
                        val cards = state.sections[section.key].orEmpty()
                        if (cards.isEmpty() && !state.isRefreshing) return@forEach

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        items(cards, key = { it.cardId }) { card ->
                            Card(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .aspectRatio(0.72f)
                                    .clickable { showSheetFor = card }
                            ) {
                                DimmableCardImage(
                                    imageUrl = card.imageUrl,
                                    contentDescription = card.name,
                                    dimmed = !card.owned,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    showSheetFor?.let { card ->
        CardActionSheet(
            card = card,
            onDismiss = { showSheetFor = null },
            onToggleOwned = {
                viewModel.toggleOwned(card.cardId, card.owned)
                showSheetFor = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardActionSheet(
    card: PersonalCard,
    onDismiss: () -> Unit,
    onToggleOwned: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            if (!card.owned) {
                Button(onClick = onToggleOwned, modifier = Modifier.fillMaxWidth()) {
                    Text("Add Card")
                }
            } else {
                Button(onClick = onToggleOwned, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove Card")
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}
