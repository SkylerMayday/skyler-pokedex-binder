package com.skyler.pokedexbinder.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectingArtDao {

    // ---- Groups ----
    @Query("SELECT * FROM connecting_art_group ORDER BY position ASC, id ASC")
    fun observeGroups(): Flow<List<ConnectingArtGroup>>

    @Query("SELECT * FROM connecting_art_group ORDER BY position ASC, id ASC")
    suspend fun getGroups(): List<ConnectingArtGroup>

    @Query("SELECT * FROM connecting_art_group WHERE id = :groupId")
    suspend fun getGroup(groupId: Int): ConnectingArtGroup?

    @Insert
    suspend fun insertGroup(group: ConnectingArtGroup): Long

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM connecting_art_group")
    suspend fun getNextGroupPosition(): Int

    /** Inserts a group at the end and creates rows*cols empty slots for it. */
    @Transaction
    suspend fun createGroupWithSlots(name: String, rows: Int, cols: Int) {
        val pos = getNextGroupPosition()
        val groupId = insertGroup(
            ConnectingArtGroup(name = name, rows = rows, cols = cols, position = pos)
        ).toInt()
        val slots = (0 until rows * cols).map { idx ->
            ConnectingArtSlot(groupId = groupId, slotIndex = idx)
        }
        insertSlots(slots)
    }

    @Query("UPDATE connecting_art_group SET position = :position WHERE id = :id")
    suspend fun updateGroupPosition(id: Int, position: Int)

    /** Cascade-deletes all child slots via the FK ON DELETE CASCADE. */
    @Query("DELETE FROM connecting_art_group WHERE id = :id")
    suspend fun deleteGroupById(id: Int)

    // ---- Slots ----
    @Query("SELECT * FROM connecting_art_slot WHERE groupId = :groupId ORDER BY slotIndex ASC")
    fun observeSlots(groupId: Int): Flow<List<ConnectingArtSlot>>

    @Query("SELECT * FROM connecting_art_slot ORDER BY groupId ASC, slotIndex ASC")
    fun observeAllSlots(): Flow<List<ConnectingArtSlot>>

    @Insert
    suspend fun insertSlots(slots: List<ConnectingArtSlot>)

    @Update
    suspend fun updateSlot(slot: ConnectingArtSlot)

    @Query(
        "UPDATE connecting_art_slot SET cardId = :cardId, cardName = :cardName, " +
            "cardImageUrl = :cardImageUrl, owned = 0 WHERE id = :slotId"
    )
    suspend fun assignCard(slotId: Int, cardId: String, cardName: String, cardImageUrl: String)

    @Query(
        "UPDATE connecting_art_slot SET cardId = NULL, cardName = NULL, " +
            "cardImageUrl = NULL, owned = 0 WHERE id = :slotId"
    )
    suspend fun clearSlot(slotId: Int)

    @Query("UPDATE connecting_art_slot SET owned = :owned WHERE id = :slotId")
    suspend fun setOwned(slotId: Int, owned: Boolean)
}
