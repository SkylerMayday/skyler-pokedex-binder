package com.skyler.pokedexbinder.ui.mainbinder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.R
import com.skyler.pokedexbinder.data.local.MainBinderEntry
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.repository.BinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject

@HiltViewModel
class MainBinderViewModel @Inject constructor(
    private val binderRepository: BinderRepository,
    @ApplicationContext private val context: Context? = null
) : ViewModel() {

    val slots: StateFlow<List<PokemonSlot>> = binderRepository.observeSlots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch { seedIfEmpty() }
    }

    private suspend fun seedIfEmpty() {
        val existing = binderRepository.getSlotByPokemonId("bulbasaur")
        if (existing == null && context != null) {
            val json = context.resources.openRawResource(R.raw.pokemon_slots)
                .bufferedReader().readText()
            val arr = JSONArray(json)
            val entries = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                MainBinderEntry(
                    pokemonId = obj.getString("id"),
                    pokemonName = obj.getString("name"),
                    dexOrder = obj.getInt("dex_order")
                )
            }
            binderRepository.seedFromJson(entries)
        }
    }
}
