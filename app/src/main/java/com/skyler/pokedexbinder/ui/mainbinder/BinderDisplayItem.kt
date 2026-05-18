package com.skyler.pokedexbinder.ui.mainbinder

import com.skyler.pokedexbinder.data.model.PokemonSlot

sealed class BinderDisplayItem {
    data class Header(val title: String) : BinderDisplayItem()
    data class Slot(val pokemonSlot: PokemonSlot) : BinderDisplayItem()
}
