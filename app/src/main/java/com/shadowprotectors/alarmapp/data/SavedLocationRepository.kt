package com.shadowprotectors.alarmapp.data

import com.shadowprotectors.alarmapp.engine.DistanceEngine
import kotlinx.coroutines.flow.Flow

class SavedLocationRepository(private val dao: SavedLocationDao) {

    fun getAllLocationsFlow(): Flow<List<SavedLocation>> = dao.getAllLocationsFlow()

    suspend fun getAllLocations(): List<SavedLocation> = dao.getAllLocations()

    suspend fun getById(id: Long): SavedLocation? = dao.getById(id)

    suspend fun insert(savedLocation: SavedLocation): Long = dao.insert(savedLocation)

    suspend fun update(savedLocation: SavedLocation) = dao.update(savedLocation)

    suspend fun rename(id: Long, name: String) = dao.rename(id, name)

    suspend fun delete(savedLocation: SavedLocation) = dao.delete(savedLocation)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /**
     * Finds an existing saved location near the given coordinates within toleranceMeters.
     */
    suspend fun findNear(lat: Double, lng: Double, toleranceMeters: Double = 50.0): SavedLocation? {
        val all = dao.getAllLocations()
        var closest: SavedLocation? = null
        var minDistanceMeters = Double.MAX_VALUE

        for (loc in all) {
            val distKm = DistanceEngine.calculateDistanceKm(lat, lng, loc.latitude, loc.longitude)
            val distMeters = distKm * 1000.0
            if (distMeters <= toleranceMeters && distMeters < minDistanceMeters) {
                minDistanceMeters = distMeters
                closest = loc
            }
        }
        return closest
    }

    /**
     * Marks a location as used by updating timestamp and usage count.
     */
    suspend fun recordUsage(id: Long) {
        dao.recordUsage(id, System.currentTimeMillis())
    }
}
