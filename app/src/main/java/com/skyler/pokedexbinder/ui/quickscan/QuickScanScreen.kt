package com.skyler.pokedexbinder.ui.quickscan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.TcgCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickScanScreen(
    onDone: () -> Unit,
    onBack: () -> Unit = onDone,
    viewModel: QuickScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Intercept system back button so it behaves the same as the in-app arrow.
    BackHandler { onBack() }

    LaunchedEffect(state) {
        if (state is QuickScanState.Success) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Card") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                is QuickScanState.Idle -> {
                    SearchPanel(
                        initialQuery = "",
                        onSearch = { viewModel.search(it) }
                    )
                }

                is QuickScanState.Searching -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Searching…")
                        }
                    }
                }

                is QuickScanState.CardSelection -> {
                    CardPickerList(
                        cards = s.cards,
                        onSelect = { viewModel.selectCard(it) },
                        onSearch = { viewModel.search(it) }
                    )
                }

                is QuickScanState.CardConfirm -> {
                    CardConfirmPanel(
                        card = s.card,
                        isReplace = s.isReplace,
                        onConfirm = { viewModel.confirmCard(s.card) },
                        onWrongCard = { viewModel.wrongCard(s.allCards) }
                    )
                }

                is QuickScanState.SlotSelection -> {
                    SlotPickerList(
                        card = s.card,
                        slots = s.slots,
                        onSelect = { viewModel.assignToSlot(it, s.card) },
                        onBack = { viewModel.reset() }
                    )
                }

                is QuickScanState.Success -> Unit

                is QuickScanState.NoSlot -> {
                    NoSlotPanel(
                        card = s.card,
                        onAddToSecondary = { viewModel.addToSecondary(s.card) },
                        onBack = { viewModel.reset() }
                    )
                }

                is QuickScanState.NotFound -> {
                    SearchPanel(
                        initialQuery = s.query,
                        onSearch = { viewModel.search(it) },
                        noResultsFor = s.query,
                        onAddManually = { viewModel.showManualEntry() }
                    )
                }

                is QuickScanState.ManualEntry -> {
                    ManualEntryPanel(
                        prefillName = s.prefillName,
                        onConfirm = { name, set, num, url ->
                            viewModel.confirmCustomCard(name, set, num, url)
                        },
                        onBack = { viewModel.reset() }
                    )
                }

                is QuickScanState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
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
private fun SearchPanel(
    initialQuery: String,
    onSearch: (String) -> Unit,
    noResultsFor: String? = null,
    onAddManually: (() -> Unit)? = null
) {
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Search by Pokémon name or Pokédex number",
            style = MaterialTheme.typography.titleMedium
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("e.g. Pikachu or 25") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { if (query.isNotBlank()) onSearch(query) }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSearch(query) }),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { if (query.isNotBlank()) onSearch(query) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Search")
        }
        if (noResultsFor != null) {
            Text(
                "No cards found for \"$noResultsFor\".",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            if (onAddManually != null) {
                OutlinedButton(
                    onClick = onAddManually,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Card not in database? Add manually")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardPickerList(
    cards: List<TcgCard>,
    onSelect: (TcgCard) -> Unit,
    onSearch: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var previewCard by remember { mutableStateOf<TcgCard?>(null) }
    val displayed = if (query.isBlank()) cards
    else cards.filter { it.name.contains(query, ignoreCase = true) }

    previewCard?.let { card ->
        CardPreviewDialog(card = card, onDismiss = { previewCard = null })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Filter or search again (e.g. Pikachu or 25)") },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { onSearch(query) }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            "Tap to select · Long-press to preview",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(displayed, key = { it.id }) { card ->
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
                        onClick = { onSelect(card) },
                        onLongClick = { previewCard = card }
                    )
                )
                HorizontalDivider()
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

@Composable
private fun ManualEntryPanel(
    prefillName: String,
    onConfirm: (name: String, setName: String, number: String, imageUrl: String) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(prefillName) }
    var setName by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Add Card Manually", style = MaterialTheme.typography.titleMedium)
        Text(
            "For cards not in the database (regional promos, special sets, etc.)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Card name *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = setName,
            onValueChange = { setName = it },
            label = { Text("Set name *") },
            placeholder = { Text("e.g. Scarlet & Violet Promos") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            label = { Text("Card number *") },
            placeholder = { Text("e.g. SVP213 or 013/034") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = imageUrl,
            onValueChange = { imageUrl = it },
            label = { Text("Image URL (optional)") },
            placeholder = { Text("https://...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onConfirm(name.trim(), setName.trim(), number.trim(), imageUrl.trim()) },
            enabled = name.isNotBlank() && setName.isNotBlank() && number.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Card")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

@Composable
private fun CardConfirmPanel(
    card: TcgCard,
    isReplace: Boolean,
    onConfirm: () -> Unit,
    onWrongCard: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (isReplace) "Replace your current card?" else "Is this the right card?",
            style = MaterialTheme.typography.titleMedium
        )
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Text(
            "${card.name} · ${card.setName} · #${card.number}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isReplace) {
            Text(
                "The old card will be moved to your Secondary Binder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onWrongCard, modifier = Modifier.weight(1f)) {
                Text("Wrong card")
            }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text(if (isReplace) "Yes, replace it" else "Yes, add it")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SlotPickerList(
    card: TcgCard,
    slots: List<PokemonSlot>,
    onSelect: (PokemonSlot) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().height(180.dp)
        )
        Text(
            "Which slot should this go into?",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(slots, key = { it.id }) { slot ->
                ListItem(
                    headlineContent = { Text(slot.name) },
                    supportingContent = { Text("#${slot.dexNumber} · ${slot.slotType.name.lowercase().replaceFirstChar { it.uppercase() }}") },
                    modifier = Modifier.clickable { onSelect(slot) }
                )
                HorizontalDivider()
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Back")
        }
    }
}

@Composable
private fun NoSlotPanel(card: TcgCard, onAddToSecondary: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Text("${card.name} · ${card.setName} · #${card.number}")
        Text(
            "No matching slot found in the main binder.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onAddToSecondary, modifier = Modifier.fillMaxWidth()) {
            Text("Add to Secondary Binder")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Search Again")
        }
    }
}
