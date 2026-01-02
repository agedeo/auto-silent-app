package nl.deluxeweb.silentmode

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.deluxeweb.silentmode.data.AppDatabase
import nl.deluxeweb.silentmode.data.IgnoredLocation

class SafeZoneHelper(private val activity: AppCompatActivity) {

    fun scanAndIgnoreCurrentLocation() {
        // 1. Check permissie
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "⚠️ Eerst locatie toestemming nodig!", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, "🔍 Omgeving scannen...", Toast.LENGTH_SHORT).show()

        val client = LocationServices.getFusedLocationProviderClient(activity)

        // 2. Haal actuele locatie op (Hoge precisie)
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
            if (location == null) {
                Toast.makeText(activity, "❌ Kan locatie niet bepalen.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }
            analyzeSurroundings(location)
        }
    }

    private fun analyzeSurroundings(location: Location) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(activity)

            // 3. Zoek alles binnen ~150 meter (ruime marge voor GPS drift)
            // 0.0015 graad is ongeveer 160 meter
            val range = 0.0015
            val nearby = db.locationDao().getNearby(
                location.latitude - range, location.latitude + range,
                location.longitude - range, location.longitude + range,
                // We zoeken in ALLE categorieën, want thuis wil je nergens last van hebben
                listOf("church", "theater", "library", "cinema", "museum", "cemetery", "hospital", "government", "community")
            )

            // 4. Exacte afstand berekenen
            val toIgnore = nearby.filter { loc ->
                val results = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, loc.lat, loc.lon, results)
                val distance = results[0]
                distance < 150 // Alles binnen 150m van je voordeur/bureau
            }

            withContext(Dispatchers.Main) {
                if (toIgnore.isEmpty()) {
                    AlertDialog.Builder(activity)
                        .setTitle("✅ Niets gevonden")
                        .setMessage("Er zijn geen bekende stilte-locaties direct in de buurt. Je bent hier al veilig!")
                        .setPositiveButton("Ok", null)
                        .show()
                } else {
                    // 5. Bevestiging vragen
                    val names = toIgnore.joinToString("\n") { "- ${it.name ?: "Naamloos"} (${it.category})" }

                    AlertDialog.Builder(activity)
                        .setTitle("🏠 Locaties Negeren?")
                        .setMessage("Gevonden in de buurt:\n\n$names\n\nWil je deze negeren zodat 'AutoStil' hier stil blijft?")
                        .setPositiveButton("Ja, negeer alles") { _, _ ->
                            performIgnore(toIgnore.map { it.id })
                        }
                        .setNegativeButton("Nee", null)
                        .show()
                }
            }
        }
    }

    private fun performIgnore(ids: List<Int>) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(activity)
            val ignoreList = ids.map { IgnoredLocation(it) }
            db.ignoredLocationDao().ignoreAll(ignoreList)

            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Locaties opgeslagen! ✅", Toast.LENGTH_SHORT).show()

                // Forceer update van dashboard als je toevallig nu in die zone staat
                val intent = Intent("UPDATE_UI_EVENT")
                intent.setPackage(activity.packageName)
                activity.sendBroadcast(intent)
            }
        }
    }
}