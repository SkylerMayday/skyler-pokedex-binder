package com.skyler.pokedexbinder.ui.connectingart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.skyler.pokedexbinder.data.local.ConnectingArtGroup
import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import com.skyler.pokedexbinder.data.model.Language
import com.skyler.pokedexbinder.data.model.TcgCard
import com.skyler.pokedexbinder.repository.ConnectingArtRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectingArtUiState(
    val groups: List<ConnectingArtGroup> = emptyList(),
    val slotsByGroup: Map<Int, List<ConnectingArtSlot>> = emptyMap()
)

@HiltViewModel
class ConnectingArtViewModel @Inject constructor(
    private val repository: ConnectingArtRepository
) : ViewModel() {

    val uiState: StateFlow<ConnectingArtUiState> = combine(
        repository.observeGroups(),
        repository.observeAllSlots()
    ) { groups, slots ->
        ConnectingArtUiState(groups = groups, slotsByGroup = slots.groupBy { it.groupId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectingArtUiState())

    // Tracks which slot the user is currently assigning a card to, so AppNavigation's
    // search result knows the target. Set on tapping an empty slot, cleared after assign.
    private val _pendingAssignSlotId = MutableStateFlow<Int?>(null)
    val pendingAssignSlotId: StateFlow<Int?> = _pendingAssignSlotId

    fun beginAssign(slotId: Int) {
        _pendingAssignSlotId.value = slotId
    }

    fun cancelAssign() {
        _pendingAssignSlotId.value = null
    }

    fun assignPendingCard(card: TcgCard) {
        val slotId = _pendingAssignSlotId.value ?: return
        viewModelScope.launch {
            repository.assignCard(slotId, card.id, card.name, card.imageUrl)
        }
        _pendingAssignSlotId.value = null
    }

    fun createGroup(name: String, rows: Int, cols: Int) {
        viewModelScope.launch { repository.createGroup(name, rows, cols) }
    }

    fun deleteGroup(groupId: Int) {
        // Cascade FK handles slot cleanup — no manual deletion needed here.
        viewModelScope.launch { repository.deleteGroup(groupId) }
    }

    fun reorderGroups(fromIndex: Int, toIndex: Int) {
        val ids = uiState.value.groups.map { it.id }.toMutableList()
        if (fromIndex !in ids.indices || toIndex !in ids.indices) return
        ids.add(toIndex, ids.removeAt(fromIndex))
        viewModelScope.launch { repository.reorderGroups(ids) }
    }

    fun setOwned(slotId: Int, owned: Boolean) {
        viewModelScope.launch { repository.setOwned(slotId, owned) }
    }

    fun removeCard(slotId: Int) {
        viewModelScope.launch { repository.removeCard(slotId) }
    }

    fun updateLanguage(slotId: Int, language: Language) {
        viewModelScope.launch { repository.updateLanguage(slotId, language) }
    }
}
