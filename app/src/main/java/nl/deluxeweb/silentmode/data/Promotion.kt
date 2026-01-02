package nl.deluxeweb.silentmode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "promotions")
data class Promotion(
    @PrimaryKey val locationId: Int,
    val text: String,
    val url: String? = null
)