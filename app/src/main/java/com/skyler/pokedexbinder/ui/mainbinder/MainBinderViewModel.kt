package com.skyler.pokedexbinder.ui.mainbinder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.data.model.SlotType
import com.skyler.pokedexbinder.domain.BackfillCardNamesUseCase
import com.skyler.pokedexbinder.repository.BinderRepository
import com.skyler.pokedexbinder.repository.SettingsRepository
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
    private val settingsRepository: SettingsRepository,
    private val backfillCardNamesUseCase: BackfillCardNamesUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")

    val displayItems: StateFlow<List<BinderDisplayItem>> = combine(
        binderRepository.observeSlots(),
        _searchQuery,
        settingsRepository.settings
    ) { slots, query, settings ->
        val visible = slots.filter { slot ->
            when (slot.slotType) {
                SlotType.BASE -> true
                SlotType.REGIONAL -> settings.showRegional
                SlotType.ALTERNATE_FORM -> settings.showAlternateForms
                SlotType.MEGA -> settings.showMega
                SlotType.GMAX -> settings.showGmax
            }
        }
        if (query.isBlank()) buildGrouped(visible) else buildFiltered(visible, query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            binderRepository.seedIfEmpty(context)
            binderRepository.seedAlternateFormsIfMissing()
            binderRepository.seedMegasIfMissing()
            binderRepository.migrateSlotNamesIfNeeded()
            backfillCardNamesUseCase.backfill()
        }
    }

    fun setSearch(query: String) {
        _searchQuery.value = query
    }

    private fun buildFiltered(slots: List<PokemonSlot>, query: String): List<BinderDisplayItem> {
        val q = query.trim().lowercase()
        return slots.filter { slot ->
            val name = slot.name.lowercase()
            slot.dexNumber.toString() == q ||
                name.contains(q) ||
                name.split(" ").any { word -> levenshtein(q, word) <= (q.length / 4).coerceIn(1, 2) }
        }.map { BinderDisplayItem.Slot(it) }
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                       else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }

    private fun buildGrouped(slots: List<PokemonSlot>): List<BinderDisplayItem> {
        val base = slots.filter { it.slotType == SlotType.BASE }
        val regional = slots.filter { it.slotType == SlotType.REGIONAL }
        val alternateForms = slots.filter { it.slotType == SlotType.ALTERNATE_FORM }
        val mega = slots.filter { it.slotType == SlotType.MEGA }
        val gmax = slots.filter { it.slotType == SlotType.GMAX }

        val items = mutableListOf<BinderDisplayItem>()

        for ((genName, range) in GENERATIONS) {
            val genSlots = base.filter { it.dexNumber in range }
            if (genSlots.isNotEmpty()) {
                items += BinderDisplayItem.Header(genName)
                items += genSlots.map { BinderDisplayItem.Slot(it) }
            }
        }

        if (regional.isNotEmpty()) {
            items += BinderDisplayItem.Header("Regional Variants")
            items += regional.sortedWith(compareBy({ it.dexNumber }, { it.dexOrder }))
                .map { BinderDisplayItem.Slot(it) }
        }

        if (alternateForms.isNotEmpty()) {
            items += BinderDisplayItem.Header("Alternate Forms")
            items += alternateForms.sortedWith(compareBy({ it.dexNumber }, { it.dexOrder }))
                .map { BinderDisplayItem.Slot(it) }
        }

        if (mega.isNotEmpty()) {
            items += BinderDisplayItem.Header("Mega Evolutions")
            items += mega.sortedWith(compareBy({ it.dexNumber }, { it.dexOrder }))
                .map { BinderDisplayItem.Slot(it) }
        }

        if (gmax.isNotEmpty()) {
            items += BinderDisplayItem.Header("VMax")
            items += gmax.sortedWith(compareBy({ it.dexNumber }, { it.dexOrder }))
                .map { BinderDisplayItem.Slot(it) }
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
