package com.skyler.pokedexbinder.ui.secondarybinder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.TcgCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecondaryBinderViewModel @Inject constructor(
    private val secondaryBinderDao: SecondaryBinderDao
) : ViewModel() {

    private val _entries = MutableStateFlow<List<SecondaryBinderEntry>>(emptyList())
    val entries: StateFlow<List<SecondaryBinderEntry>> = _entries

    init {
        viewModelScope.launch {
            secondaryBinderDao.observeAll().collect { _entries.value = it }
        }
    }

    fun addCard(card: TcgCard) {
        viewModelScope.launch {
            val primaryName = card.pokemonNames.firstOrNull() ?: card.name
            secondaryBinderDao.insertAtEnd(
                SecondaryBinderEntry(
                    pokemonId = primaryName.lowercase(),
                    pokemonName = primaryName,
                    cardId = card.id,
                    cardImageUrl = card.imageUrl
                )
            )
        }
    }

    fun deleteCard(id: Int) {
        viewModelScope.launch { secondaryBinderDao.deleteById(id) }
    }

    fun reorder(fromIndex: Int, toIndex: Int) {
        val current = _entries.value.toMutableList()
        current.add(toIndex, current.removeAt(fromIndex))
        _entries.value = current
        viewModelScope.launch {
            current.forEachIndexed { index, entry ->
                secondaryBinderDao.updatePosition(entry.id, index)
            }
        }
    }
}
