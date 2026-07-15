package com.skyler.pokedexbinder.ui.secondarybinder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.ui.components.EditCardDetailsDialog
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryBinderScreen(
    onOpenDrawer: () -> Unit = {},
    onScanCard: () -> Unit = {},
    onSearchCard: () -> Unit = {},
    viewModel: SecondaryBinderViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        viewModel.reorder(from.index, to.index)
    }
    var showAddSheet by remember { mutableStateOf(false) }
    var editDetailsTarget by remember { mutableStateOf<SecondaryBinderEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Card History") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add card")
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.padding(padding)
            ) {
                items(entries, key = { it.id }) { entry ->
                    ReorderableItem(reorderState, key = entry.id) { isDragging ->
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .aspectRatio(0.72f)
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .longPressDraggableHandle(),
                                elevation = CardDefaults.cardElevation(if (isDragging) 8.dp else 2.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    if (entry.cardImageUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = entry.cardImageUrl,
                                            contentDescription = entry.pokemonName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = entry.pokemonName,
                                            fontSize = 11.sp,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(4.dp)
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = { viewModel.deleteCard(entry.id) },
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(Alignment.TopEnd)
                                    .background(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Delete",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(
                                onClick = { editDetailsTarget = entry },
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(Alignment.TopStart)
                                    .background(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        shape = CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit Details",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editDetailsTarget?.let { entry ->
        EditCardDetailsDialog(
            currentLanguage = Language.fromRaw(entry.language),
            showLockAndRemarks = false,
            onDismiss = { editDetailsTarget = null },
            onSave = { language, _, _ ->
                viewModel.updateLanguage(entry.id, language)
                editDetailsTarget = null
            }
        )
    }

    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Add Card", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { showAddSheet = false; onScanCard() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan Card") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showAddSheet = false; onSearchCard() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Search Manually") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
