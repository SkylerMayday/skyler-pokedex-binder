package com.skyler.pokedexbinder.ui.unown

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.UnownBinderEntry
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.UnownBinderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    /** One-shot event: a clear/reassign was blocked by a locked slot (defense in depth). */
    private val _blockedByLock = MutableSharedFlow<Unit>()
    val blockedByLock: SharedFlow<Unit> = _blockedByLock

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
        viewModelScope.launch {
            val proceeded = repository.clearCard(letterId)
            if (!proceeded) _blockedByLock.emit(Unit)
        }
    }

    fun updateDetails(letterId: String, language: Language, remarks: String?, isLocked: Boolean) {
        viewModelScope.launch { repository.updateDetails(letterId, language, remarks, isLocked) }
    }
}
