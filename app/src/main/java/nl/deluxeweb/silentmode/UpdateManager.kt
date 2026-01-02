package nl.deluxeweb.silentmode

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.deluxeweb.silentmode.data.AppDatabase
import nl.deluxeweb.silentmode.data.LocationEntity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class UpdateResult { SUCCESS, UP_TO_DATE, FAILED }

class UpdateManager(private val context: Context) {

    suspend fun downloadUpdate(force: Boolean = false): UpdateResult {
        return withContext(Dispatchers.IO) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val db = AppDatabase.getDatabase(context)

            // CHECK 1: Is de database leeg? Dan ALTIJD updaten, negeer tijdstempel.
            val count = db.locationDao().getLocationCount()
            val isEmpty = count == 0

            val lastUpdate = prefs.getLong("last_update_check", 0)
            val currentTime = System.currentTimeMillis()
            val oneWeek = 7 * 24 * 60 * 60 * 1000L

            // Als niet leeg, niet geforceerd en nog geen week oud: stop.
            if (!isEmpty && !force && (currentTime - lastUpdate) < oneWeek) {
                return@withContext UpdateResult.UP_TO_DATE
            }

            // Welke categorieën wil de gebruiker?
            val activeCats = prefs.getStringSet("active_categories", setOf("church", "theater")) ?: setOf("church", "theater")
            if (activeCats.isEmpty()) {
                // Als er niets is aangevinkt, kunnen we niks downloaden.
                // Wis database voor de zekerheid als die er was.
                db.locationDao().deleteAll()
                return@withContext UpdateResult.SUCCESS
            }

            // Bouw de Overpass Query
            val query = buildOverpassQuery(activeCats)

            try {
                Log.d("UpdateManager", "Start download...")
                val url = URL("https://overpass-api.de/api/interpreter")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.outputStream.write(("data=" + query).toByteArray())

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().readText()
                    val jsonObj = JSONObject(jsonStr)
                    val elements = jsonObj.getJSONArray("elements")

                    val newLocations = mutableListOf<LocationEntity>()

                    for (i in 0 until elements.length()) {
                        val el = elements.getJSONObject(i)
                        if (el.has("lat") && el.has("lon") && el.has("tags")) {
                            val tags = el.getJSONObject("tags")
                            val name = tags.optString("name", "")

                            // Bepaal categorie
                            var cat = "unknown"
                            if (tags.has("amenity")) cat = tags.getString("amenity")
                            else if (tags.has("leisure")) cat = tags.getString("leisure")
                            else if (tags.has("building")) cat = tags.getString("building")
                            else if (tags.has("tourism")) cat = tags.getString("tourism")
                            else if (tags.has("landuse")) cat = tags.getString("landuse") // voor begraafplaatsen vaak

                            // Mapping naar onze interne namen
                            if (activeCats.contains("church") && (cat == "place_of_worship")) cat = "church"
                            if (activeCats.contains("cemetery") && (cat == "cemetery" || cat == "grave_yard")) cat = "cemetery"

                            // Let op: ID is nu een Long!
                            newLocations.add(
                                LocationEntity(
                                    id = el.getLong("id"),
                                    name = name,
                                    lat = el.getDouble("lat"),
                                    lon = el.getDouble("lon"),
                                    category = cat
                                )
                            )
                        }
                    }

                    // CHECK 2: Hebben we data?
                    if (newLocations.isNotEmpty()) {
                        // Pas NU wissen we de oude data en zetten we de nieuwe erin
                        db.runInTransaction {
                            db.locationDao().deleteAll()
                            db.locationDao().insertAll(newLocations)
                        }

                        prefs.edit().putLong("last_update_check", currentTime).apply()
                        Log.d("UpdateManager", "Succes! ${newLocations.size} locaties opgeslagen.")
                        return@withContext UpdateResult.SUCCESS
                    } else {
                        Log.e("UpdateManager", "Download geslaagd, maar lijst is leeg. Oude data behouden.")
                        // We updaten de tijdstempel NIET, zodat hij het de volgende keer weer probeert
                        return@withContext UpdateResult.FAILED
                    }

                } else {
                    Log.e("UpdateManager", "Server error: ${conn.responseCode}")
                    return@withContext UpdateResult.FAILED
                }

            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("UpdateManager", "Exception: ${e.message}")
                return@withContext UpdateResult.FAILED
            }
        }
    }

    private fun buildOverpassQuery(categories: Set<String>): String {
        val sb = StringBuilder()
        sb.append("[out:json][timeout:25];(")

        // Let op: 'area[name="Nederland"]' werkt soms niet goed als de server druk is.
        // Beter is om een bounding box te gebruiken of gewoon wereldwijd als de gebruiker inzoomed,
        // maar voor nu houden we Nederland aan via area code.
        // Code voor Nederland is 3600047796 (Relatie ID + 3600000000)

        val areaCode = "3600047796"

        if (categories.contains("church")) sb.append("node[\"amenity\"=\"place_of_worship\"](area:$areaCode);")
        if (categories.contains("theater")) sb.append("node[\"amenity\"=\"theatre\"](area:$areaCode);")
        if (categories.contains("cinema")) sb.append("node[\"amenity\"=\"cinema\"](area:$areaCode);")
        if (categories.contains("library")) sb.append("node[\"amenity\"=\"library\"](area:$areaCode);")
        if (categories.contains("museum")) sb.append("node[\"tourism\"=\"museum\"](area:$areaCode);")
        if (categories.contains("hospital")) sb.append("node[\"amenity\"=\"hospital\"](area:$areaCode);")
        if (categories.contains("community")) sb.append("node[\"amenity\"=\"community_centre\"](area:$areaCode);")
        if (categories.contains("government")) sb.append("node[\"amenity\"=\"townhall\"](area:$areaCode);")

        // Begraafplaatsen zijn soms nodes, soms ways (gebieden). We pakken hier alleen nodes voor snelheid.
        if (categories.contains("cemetery")) {
            sb.append("node[\"landuse\"=\"cemetery\"](area:$areaCode);")
            sb.append("node[\"amenity\"=\"grave_yard\"](area:$areaCode);")
        }

        sb.append(");out center;") // 'out center' zorgt dat we 1 coördinaat krijgen, ook voor gebouwen
        return sb.toString()
    }
}