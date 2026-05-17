package com.skyler.pokedexbinder.ui.slotdetail

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun SlotDetailScreen(
    pokemonId: String,
    onBack: () -> Unit,
    onScanCard: (String) -> Unit = {},
    onManualSearch: (String) -> Unit = {}
) {
    Text("Slot Detail: $pokemonId — coming soon")
}
