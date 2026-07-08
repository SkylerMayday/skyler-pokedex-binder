package com.skyler.pokedexbinder.ui.mainbinder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.ui.publish.PublishDialog
import com.skyler.pokedexbinder.ui.publish.PublishViewModel
import com.skyler.pokedexbinder.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainBinderScreen(
    onSlotClick: (PokemonSlot) -> Unit,
    onSettingsClick: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    viewModel: MainBinderViewModel = hiltViewModel()
) {
    val displayItems by viewModel.displayItems.collectAsState()
    var query by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val coroutineScope = rememberCoroutineScope()

    var showPublish by remember { mutableStateOf(false) }
    val publishVm: PublishViewModel = hiltViewModel()
    val publishState by publishVm.state.collectAsState()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val publishConfig by settingsVm.publishConfig.collectAsState()
    val pageUrl = "https://${publishConfig.githubOwner}.github.io/${publishConfig.githubRepo}/"

    if (showPublish) {
        LaunchedEffect(Unit) { publishVm.startPublish() }
        PublishDialog(
            state = publishState,
            pageUrl = pageUrl,
            onDismiss = { showPublish = false; publishVm.dismiss() }
        )
    }

    // Map of section title → item index in displayItems (only when not searching)
    val sectionIndices: List<Pair<String, Int>> = remember(displayItems) {
        displayItems.mapIndexedNotNull { index, item ->
            if (item is BinderDisplayItem.Header) item.title to index else null
        }
    }

    var dropdownExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pokédex Binder") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { showPublish = true }) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Publish")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {

            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.setSearch(it)
                },
                placeholder = { Text("Search by name or #number") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.setSearch(query) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // Section jump dropdown — only shown when not searching
            if (query.isBlank() && sectionIndices.isNotEmpty()) {
                Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
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
                        sectionIndices.forEach { (title, index) ->
                            DropdownMenuItem(
                                text = { Text(title) },
                                onClick = {
                                    dropdownExpanded = false
                                    coroutineScope.launch {
                                        gridState.animateScrollToItem(index)
                                    }
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
                modifier = Modifier.weight(1f)
            ) {
                displayItems.forEach { item ->
                    when (item) {
                        is BinderDisplayItem.Header -> {
                            item(
                                key = "header_${item.title}",
                                span = { GridItemSpan(maxLineSpan) }
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 8.dp)
                                )
                            }
                        }
                        is BinderDisplayItem.Slot -> {
                            item(key = item.pokemonSlot.id) {
                                SlotCard(
                                    slot = item.pokemonSlot,
                                    onClick = {
                                        // Clear search so the binder isn't still filtered when we return.
                                        query = ""
                                        viewModel.setSearch("")
                                        onSlotClick(item.pokemonSlot)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCard(slot: PokemonSlot, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(0.72f)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (slot.isOccupied) {
                AsyncImage(
                    model = slot.assignedCardImageUrl,
                    contentDescription = slot.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "#${slot.dexOrder}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = slot.name,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
    }
}
