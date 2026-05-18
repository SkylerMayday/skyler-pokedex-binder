package com.skyler.pokedexbinder.ui.secondarybinder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.SecondaryBinderDao
import com.skyler.pokedexbinder.data.local.SecondaryBinderEntry
import com.skyler.pokedexbinder.data.model.TcgCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecondaryBinderViewModel @Inject constructor(
    private val secondaryBinderDao: SecondaryBinderDao
) : ViewModel() {

    val entries: Flow<List<SecondaryBinderEntry>> = secondaryBinderDao.observeAll()

    fun addCard(card: TcgCard) {
        viewModelScope.launch {
            secondaryBinderDao.insert(
                SecondaryBinderEntry(
                    pokemonId = card.primaryPokemonName.lowercase(),
                    pokemonName = card.primaryPokemonName,
                    cardId = card.id,
                    cardImageUrl = card.imageUrl
                )
            )
        }
    }
}
