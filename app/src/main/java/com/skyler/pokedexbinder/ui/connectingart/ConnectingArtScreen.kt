package com.skyler.pokedexbinder.ui.connectingart

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.skyler.pokedexbinder.data.local.ConnectingArtGroup
import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import com.skyler.pokedexbinder.ui.common.DimmableCardImage
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val GRID_PRESETS = listOf(1 to 2, 1 to 3, 1 to 4, 2 to 2, 3 to 3)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ConnectingArtScreen(
    onOpenDrawer: () -> Unit,
    onOpenSlotSearch: (slotId: Int) -> Unit,
    viewModel: ConnectingArtViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var showCreateSheet by remember { mutableStateOf(false) }
    var groupPendingDelete by remember { mutableStateOf<ConnectingArtGroup?>(null) }
    var slotSheetTarget by remember { mutableStateOf<ConnectingArtSlot?>(null) }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        viewModel.reorderGroups(from.index, to.index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connecting Art") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateSheet = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add group")
            }
        }
    ) { padding ->
        if (uiState.groups.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No groups yet — tap + to create one")
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(padding).fillMaxSize()
            ) {
                items(uiState.groups, key = { it.id }) { group ->
                    ReorderableItem(reorderState, key = group.id) { _ ->
                        GroupSection(
                            group = group,
                            slots = uiState.slotsByGroup[group.id].orEmpty(),
                            onDeleteClick = { groupPendingDelete = group },
                            onEmptySlotTap = { slot ->
                                viewModel.beginAssign(slot.id)
                                onOpenSlotSearch(slot.id)
                            },
                            onAssignedSlotTap = { slot -> slotSheetTarget = slot }
                        )
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateGroupSheet(
            onDismiss = { showCreateSheet = false },
            onCreate = { name, rows, cols ->
                viewModel.createGroup(name, rows, cols)
                showCreateSheet = false
            }
        )
    }

    slotSheetTarget?.let { slot ->
        SlotActionSheet(
            slot = slot,
            onDismiss = { slotSheetTarget = null },
            onMarkOwned = { viewModel.setOwned(slot.id, true); slotSheetTarget = null },
            onMarkUnowned = { viewModel.setOwned(slot.id, false); slotSheetTarget = null },
            onRemoveCard = { viewModel.removeCard(slot.id); slotSheetTarget = null }
        )
    }

    groupPendingDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { groupPendingDelete = null },
            title = { Text("Delete \"${group.name}\"?") },
            text = { Text("This removes the group and all its slots. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteGroup(group.id)
                    groupPendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { groupPendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun sh.calvin.reorderable.ReorderableCollectionItemScope.GroupSection(
    group: ConnectingArtGroup,
    slots: List<ConnectingArtSlot>,
    onDeleteClick: () -> Unit,
    onEmptySlotTap: (ConnectingArtSlot) -> Unit,
    onAssignedSlotTap: (ConnectingArtSlot) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().longPressDraggableHandle()
        ) {
            Icon(
                Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDeleteClick) {
                Icon(Icons.Default.Delete, contentDescription = "Delete group")
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(group.rows) { r ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(group.cols) { c ->
                        val slotIndex = r * group.cols + c
                        val slot = slots.getOrNull(slotIndex)
                        SlotTile(
                            slot = slot,
                            onEmptyTap = { slot?.let(onEmptySlotTap) },
                            onAssignedTap = { slot?.let(onAssignedSlotTap) },
                            modifier = Modifier.weight(1f).aspectRatio(0.72f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotTile(
    slot: ConnectingArtSlot?,
    onEmptyTap: () -> Unit,
    onAssignedTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        if (slot?.cardId == null) {
            Box(
                modifier = Modifier.fillMaxSize().clickable(onClick = onEmptyTap),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Assign card",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            DimmableCardImage(
                imageUrl = slot.cardImageUrl,
                contentDescription = slot.cardName,
                dimmed = !slot.owned,
                modifier = Modifier.fillMaxSize().clickable(onClick = onAssignedTap)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroupSheet(
    onDismiss: () -> Unit,
    onCreate: (name: String, rows: Int, cols: Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(GRID_PRESETS.first()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("New Group", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Group name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            FlowRowPresets(selected = selected, onSelect = { selected = it })
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onCreate(name.trim(), selected.first, selected.second) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Create") }
        }
    }
}

@Composable
private fun FlowRowPresets(selected: Pair<Int, Int>, onSelect: (Pair<Int, Int>) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        GRID_PRESETS.forEach { preset ->
            FilterChip(
                selected = selected == preset,
                onClick = { onSelect(preset) },
                label = { Text("${preset.first}×${preset.second}") }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotActionSheet(
    slot: ConnectingArtSlot,
    onDismiss: () -> Unit,
    onMarkOwned: () -> Unit,
    onMarkUnowned: () -> Unit,
    onRemoveCard: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(slot.cardName ?: "Card", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            if (!slot.owned) {
                Button(onClick = onMarkOwned, modifier = Modifier.fillMaxWidth()) {
                    Text("Mark as Owned")
                }
            } else {
                Button(onClick = onMarkUnowned, modifier = Modifier.fillMaxWidth()) {
                    Text("Mark as Unowned")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRemoveCard, modifier = Modifier.fillMaxWidth()) {
                Text("Remove Card")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
        }
    }
}
