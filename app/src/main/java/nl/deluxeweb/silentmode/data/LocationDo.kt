package nl.deluxeweb.silentmode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import nl.deluxeweb.silentmode.data.LocationEntity

@Dao
interface LocationDao {
    @Query("SELECT * FROM locations")
    fun getAll(): List<LocationEntity>

    // BELANGRIJK: Haal 'suspend' hier weg!
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(locations: List<LocationEntity>)

    // BELANGRIJK: Haal 'suspend' hier ook weg!
    @Query("DELETE FROM locations")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM locations")
    fun getLocationCount(): Int

    // Deze mag wel suspend blijven omdat je die los aanroept (buiten een transactie)
    @Query("SELECT * FROM locations WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon")
    suspend fun getNearby(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<LocationEntity>

    // Let op: deze query moet waarschijnlijk ook aangepast worden om categories te filteren als je dat gebruikt
    @Query("SELECT * FROM locations WHERE lat BETWEEN :minLat AND :maxLat AND lon BETWEEN :minLon AND :maxLon AND category IN (:categories)")
    suspend fun getNearby(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, categories: List<String>): List<LocationEntity>
}