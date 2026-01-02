package nl.deluxeweb.silentmode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ignored_locations")
data class IgnoredLocation(
    @PrimaryKey val id: Int
)