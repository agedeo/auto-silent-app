package nl.deluxeweb.silentmode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IgnoredLocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ignore(location: IgnoredLocation)

    @Query("SELECT id FROM ignored_locations")
    suspend fun getAllIgnoredIds(): List<Int>

    // NIEUW: Gooi alles weg
    @Query("DELETE FROM ignored_locations")
    suspend fun clearAll()
}