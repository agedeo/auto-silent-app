package nl.deluxeweb.silentmode.data

// Dit vertelt de app hoe de JSON van GitHub eruit ziet
data class RemoteMetadata(
    val regions: List<RegionInfo>
)

data class RegionInfo(
    val id: String,         // bijv "nl"
    val file: String,       // bijv "silent_locations_nl.db"
    val count: Int
)