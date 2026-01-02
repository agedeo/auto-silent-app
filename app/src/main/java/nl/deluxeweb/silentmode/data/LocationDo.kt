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

    // NIEUW: Haal specifiek één locatie op
    @Query("SELECT * FROM locations WHERE id = :id LIMIT 1")
    suspend fun getLocationById(id: Int): SilentLocation?

    // NIEUW: Tel hoeveel locaties er in totaal zijn (voor debug/status check)
    // Dit lost de 'Unresolved reference' fout op.
    @Query("SELECT COUNT(*) FROM locations")
    suspend fun getLocationCount(): Int
}