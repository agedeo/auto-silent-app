package nl.deluxeweb.silentmode.data

import com.google.gson.annotations.SerializedName

data class RemoteMetadata(
    // We mappen de JSON velden naar variabelen
    val timestamp: Long,          // bijv: 1767341301

    @SerializedName("last_updated")
    val lastUpdated: String,      // bijv: "2026-01-02 08:08:21"

    val regions: List<RegionInfo>
)

data class RegionInfo(
    val id: String,
    val file: String,
    val count: Int,
    @SerializedName("size_bytes")
    val sizeBytes: Long
)