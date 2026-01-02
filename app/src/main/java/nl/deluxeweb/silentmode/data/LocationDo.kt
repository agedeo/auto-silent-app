package nl.deluxeweb.silentmode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon AND category IN (:categories)")
    suspend fun getNearby(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, categories: List<String>): List<SilentLocation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(locations: List<SilentLocation>)

    @Query("DELETE FROM locations")
    suspend fun deleteAll()

    @Query("SELECT * FROM locations")
    suspend fun getAllLocations(): List<SilentLocation>

    // NIEUW: Haal specifiek één locatie op (voor de categorie check op dashboard)
    @Query("SELECT * FROM locations WHERE id = :id LIMIT 1")
    suspend fun getLocationById(id: Int): SilentLocation?
}