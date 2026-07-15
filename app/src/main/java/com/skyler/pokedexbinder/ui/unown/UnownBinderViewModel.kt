package com.skyler.pokedexbinder.ui.unown

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.UnownBinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UnownBinderViewModel @Inject constructor(
    private val repository: UnownBinderRepository
) : ViewModel() {

    val entries: StateFlow<List<UnownBinderEntry>> =
        repository.observeEntries()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Which letter slot is currently being assigned (set on empty-slot tap / reassign,
    // read by AppNavigation's search result). Mirrors ConnectingArtViewModel.
    private val _pendingAssignLetterId = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch { repository.seedIfEmpty() }
    }

    fun beginAssign(letterId: String) { _pendingAssignLetterId.value = letterId }

    fun cancelAssign() { _pendingAssignLetterId.value = null }

    fun assignPendingCard(card: TcgCard) {
        val letterId = _pendingAssignLetterId.value ?: return
        viewModelScope.launch {
            repository.assignCard(letterId, card.id, card.imageUrl, card.name, card.setName)
        }
        _pendingAssignLetterId.value = null
    }

    fun clearCard(letterId: String) {
        viewModelScope.launch { repository.clearCard(letterId) }
    }
}
