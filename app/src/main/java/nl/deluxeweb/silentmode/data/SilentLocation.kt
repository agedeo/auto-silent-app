package nl.deluxeweb.silentmode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locations")
data class SilentLocation(
    @PrimaryKey val id: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val category: String,
    val address: String // <--- DEZE IS NIEUW!
)