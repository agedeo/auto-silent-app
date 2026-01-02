package nl.deluxeweb.silentmode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val id: Long, // Let op: Dit moet Long zijn (geen Int)!
    val name: String?,
    val lat: Double,
    val lon: Double,
    val category: String?
)