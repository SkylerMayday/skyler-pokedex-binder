package com.skyler.pokedexbinder.ui.mainbinder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.repository.BinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainBinderViewModel @Inject constructor(
    private val binderRepository: BinderRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    val displayItems: StateFlow<List<BinderDisplayItem>> = combine(
        binderRepository.observeSlots(),
        _searchQuery
    ) { slots, query ->
        if (query.isBlank()) buildGrouped(slots) else buildFiltered(slots, query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { binderRepository.seedIfEmpty(context) }
    }

    fun setSearch(query: String) {
        _searchQuery.value = query
    }

    private fun buildFiltered(slots: List<PokemonSlot>, query: String): List<BinderDisplayItem> {
        val q = query.trim().lowercase()
        return slots.filter { slot ->
            slot.name.lowercase().contains(q) ||
                slot.dexNumber.toString() == q
        }.map { BinderDisplayItem.Slot(it) }
    }

    private fun buildGrouped(slots: List<PokemonSlot>): List<BinderDisplayItem> {
        val base = slots.filter { it.slotType == SlotType.BASE }
        val regional = slots.filter { it.slotType == SlotType.REGIONAL }
        val mega = slots.filter { it.slotType == SlotType.MEGA }
        val gmax = slots.filter { it.slotType == SlotType.GMAX }

        val items = mutableListOf<BinderDisplayItem>()

        // Base Pokémon split by generation
        for ((genName, range) in GENERATIONS) {
            val genSlots = base.filter { it.dexNumber in range }
            if (genSlots.isNotEmpty()) {
                items += BinderDisplayItem.Header(genName)
                items += genSlots.map { BinderDisplayItem.Slot(it) }
            }
        }

        if (regional.isNotEmpty()) {
            items += BinderDisplayItem.Header("Regional Variants")
            items += regional.map { BinderDisplayItem.Slot(it) }
        }

        if (mega.isNotEmpty()) {
            items += BinderDisplayItem.Header("Mega Evolutions")
            items += mega.map { BinderDisplayItem.Slot(it) }
        }

        if (gmax.isNotEmpty()) {
            items += BinderDisplayItem.Header("V-Max")
            items += gmax.map { BinderDisplayItem.Slot(it) }
        }

        return items
    }

    companion object {
        private val GENERATIONS = listOf(
            "Generation I" to 1..151,
            "Generation II" to 152..251,
            "Generation III" to 252..386,
            "Generation IV" to 387..493,
            "Generation V" to 494..649,
            "Generation VI" to 650..721,
            "Generation VII" to 722..809,
            "Generation VIII" to 810..905,
            "Generation IX" to 906..1025
        )
    }
}
