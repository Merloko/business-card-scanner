package com.businesscard.scanner.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface InteractionLogDao {

    @Insert
    suspend fun insert(log: InteractionLog): Long

    @Delete
    suspend fun delete(log: InteractionLog)

    @Query("SELECT * FROM interaction_log WHERE cardId = :cardId ORDER BY timestamp DESC")
    fun getLogsForCard(cardId: Long): LiveData<List<InteractionLog>>
}
