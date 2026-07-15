package com.skyler.pokedexbinder.ui.personalcollection

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.ui.common.DimmableCardImage
import com.skyler.pokedexbinder.ui.components.EditCardDetailsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalCollectionScreen(
    onOpenDrawer: () -> Unit,
    viewModel: PersonalCollectionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showSheetFor by remember { mutableStateOf<PersonalCard?>(null) }
    var editDetailsTarget by remember { mutableStateOf<PersonalCard?>(null) }
    var collapsedSections by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var pendingScrollTo by remember { mutableStateOf<String?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    val hasAnyCards = state.sections.values.any { it.isNotEmpty() }

    // Flat item index of each section's header — used to jump straight to a section without
    // scrolling past every section before it. Recomputed whenever cards or collapse state change.
    val sectionItemIndex = remember(state.sections, collapsedSections, state.isRefreshing) {
        val map = mutableMapOf<String, Int>()
        var idx = 0
        PERSONAL_COLLECTION_SECTIONS.forEach { section ->
            val cards = state.sections[section.key].orEmpty()
            if (cards.isEmpty() && !state.isRefreshing) return@forEach
            map[section.key] = idx
            idx += 1
            if (section.key !in collapsedSections) idx += cards.size
        }
        map
    }

    // Expanding a collapsed section shifts every later index, so the actual scroll must happen
    // AFTER that recomposition lands, not in the same click handler.
    LaunchedEffect(pendingScrollTo, sectionItemIndex) {
        val target = pendingScrollTo ?: return@LaunchedEffect
        val idx = sectionItemIndex[target] ?: return@LaunchedEffect
        gridState.animateScrollToItem(idx)
        pendingScrollTo = null
    }

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
              Column(Modifier.fillMaxSize()) {
                // Section jump dropdown — same pattern as the main Pokédex binder's "Jump to
                // section" menu. Replaced the horizontal chip row now that there are 33 sections
                // (5 original + 28 Unown letters), which made a scrollable row impractical.
                if (sectionItemIndex.isNotEmpty()) {
                    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        OutlinedButton(
                            onClick = { dropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Jump to section", modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            PERSONAL_COLLECTION_SECTIONS.forEach { section ->
                                if (sectionItemIndex[section.key] == null) return@forEach
                                DropdownMenuItem(
                                    text = { Text(section.title) },
                                    onClick = {
                                        dropdownExpanded = false
                                        collapsedSections = collapsedSections - section.key
                                        pendingScrollTo = section.key
                                    }
                                )
                            }
                        }
                    }
                }
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    PERSONAL_COLLECTION_SECTIONS.forEach { section ->
                        val cards = state.sections[section.key].orEmpty()
                        if (cards.isEmpty() && !state.isRefreshing) return@forEach

                        val isCollapsed = section.key in collapsedSections
                        val ownedCount = cards.count { it.owned }

                        item(span = { GridItemSpan(maxLineSpan) }) {
                            val rotation by animateFloatAsState(
                                targetValue = if (isCollapsed) -90f else 0f,
                                label = "sectionChevron"
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        collapsedSections = if (isCollapsed) {
                                            collapsedSections - section.key
                                        } else {
                                            collapsedSections + section.key
                                        }
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = if (isCollapsed) "Expand" else "Collapse",
                                    modifier = Modifier.rotate(rotation)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "$ownedCount/${cards.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (!isCollapsed) {
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
        }
    }

    showSheetFor?.let { card ->
        CardActionSheet(
            card = card,
            onDismiss = { showSheetFor = null },
            onToggleOwned = {
                viewModel.toggleOwned(card.cardId, card.owned)
                showSheetFor = null
            },
            onEditDetails = {
                showSheetFor = null
                editDetailsTarget = card
            }
        )
    }

    editDetailsTarget?.let { card ->
        EditCardDetailsDialog(
            currentLanguage = Language.fromRaw(card.language),
            showLockAndRemarks = false,
            onDismiss = { editDetailsTarget = null },
            onSave = { language, _, _ ->
                viewModel.updateLanguage(card.cardId, language)
                editDetailsTarget = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardActionSheet(
    card: PersonalCard,
    onDismiss: () -> Unit,
    onToggleOwned: () -> Unit,
    onEditDetails: () -> Unit
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
