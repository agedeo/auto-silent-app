package nl.deluxeweb.silentmode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IgnoredLocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ignore(location: IgnoredLocation)

    // NIEUW: Een hele lijst in één keer toevoegen
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ignoreAll(locations: List<IgnoredLocation>)

    @Query("DELETE FROM ignored_locations WHERE id = :id")
    suspend fun unignore(id: Int)

    @Query("SELECT id FROM ignored_locations")
    suspend fun getAllIgnoredIds(): List<Int>

    @Query("DELETE FROM ignored_locations")
    suspend fun clearAll()
}