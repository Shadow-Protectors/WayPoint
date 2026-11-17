package com.shadowprotectors.alarmapp.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedLocationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(savedLocation: SavedLocation): Long

    @Update
    suspend fun update(savedLocation: SavedLocation)

    @Delete
    suspend fun delete(savedLocation: SavedLocation)

    @Query("DELETE FROM saved_locations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM saved_locations ORDER BY last_used_at DESC")
    fun getAllLocationsFlow(): Flow<List<SavedLocation>>

    @Query("SELECT * FROM saved_locations ORDER BY last_used_at DESC")
    suspend fun getAllLocations(): List<SavedLocation>

    @Query("SELECT * FROM saved_locations WHERE id = :id")
    suspend fun getById(id: Long): SavedLocation?

    @Query("UPDATE saved_locations SET last_used_at = :timestamp, use_count = use_count + 1 WHERE id = :id")
    suspend fun recordUsage(id: Long, timestamp: Long)

    @Query("UPDATE saved_locations SET custom_name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)
}
