package nl.deluxeweb.silentmode.data

import android.content.Context
import androidx.room.*

// Hier stonden eerst de data classes, die staan nu in hun eigen bestanden!

@Dao
interface IgnoredLocationDao {
    @Query("SELECT id FROM ignored_locations")
    fun getAllIgnoredIds(): List<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ignore(location: IgnoredLocation)

    @Query("DELETE FROM ignored_locations")
    suspend fun clearAll()
}

@Dao
interface PromotionDao {
    @Query("SELECT * FROM promotions WHERE locationId = :id LIMIT 1")
    fun getPromotion(id: Int): Promotion?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPromotion(promo: Promotion)
}

@Database(entities = [IgnoredLocation::class, Promotion::class], version = 1, exportSchema = false)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun ignoredLocationDao(): IgnoredLocationDao
    abstract fun promotionDao(): PromotionDao

    companion object {
        @Volatile
        private var INSTANCE: LocalDatabase? = null

        fun getDatabase(context: Context): LocalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LocalDatabase::class.java,
                    "local_user_data.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}