package nl.deluxeweb.silentmode.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SilentLocation::class, IgnoredLocation::class, LocationEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun locationDao(): LocationDao
    abstract fun ignoredLocationDao(): IgnoredLocationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "silent_locations.db"
                )
                    .fallbackToDestructiveMigration() // Reset DB bij nieuwe versie (veilig voor updates)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}