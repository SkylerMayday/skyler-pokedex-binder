package com.skyler.pokedexbinder.repository

import com.skyler.pokedexbinder.data.local.ConnectingArtDao
import com.skyler.pokedexbinder.data.local.ConnectingArtGroup
import com.skyler.pokedexbinder.data.local.ConnectingArtSlot
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectingArtRepository @Inject constructor(
    private val dao: ConnectingArtDao
) {
    fun observeGroups(): Flow<List<ConnectingArtGroup>> = dao.observeGroups()
    fun observeAllSlots(): Flow<List<ConnectingArtSlot>> = dao.observeAllSlots()
    fun observeSlots(groupId: Int): Flow<List<ConnectingArtSlot>> = dao.observeSlots(groupId)

    suspend fun getAllGroups(): List<ConnectingArtGroup> = dao.getGroups()
    suspend fun getAllSlots(): List<ConnectingArtSlot> = dao.getAllSlots()
    suspend fun updateSlots(slots: List<ConnectingArtSlot>) = dao.updateSlots(slots)

    suspend fun createGroup(name: String, rows: Int, cols: Int) =
        dao.createGroupWithSlots(name, rows, cols)

    suspend fun deleteGroup(groupId: Int) = dao.deleteGroupById(groupId)

    suspend fun reorderGroups(orderedGroupIds: List<Int>) {
        orderedGroupIds.forEachIndexed { index, id -> dao.updateGroupPosition(id, index) }
    }

    suspend fun assignCard(slotId: Int, cardId: String, cardName: String, cardImageUrl: String) =
        dao.assignCard(slotId, cardId, cardName, cardImageUrl)

    suspend fun removeCard(slotId: Int) = dao.clearSlot(slotId)

    suspend fun setOwned(slotId: Int, owned: Boolean) = dao.setOwned(slotId, owned)
}
