package nl.deluxeweb.silentmode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity
import nl.deluxeweb.silentmode.data.AppDatabase
import nl.deluxeweb.silentmode.data.IgnoredLocation
import nl.deluxeweb.silentmode.data.LocalDatabase

class SafeZoneHelper(private val activity: AppCompatActivity) {

    fun scanAndIgnoreCurrentLocation() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "Geen locatie toegang", Toast.LENGTH_SHORT).show()
            return
        }

        val client = LocationServices.getFusedLocationProviderClient(activity)

        Toast.makeText(activity, "Omgeving scannen...", Toast.LENGTH_SHORT).show()

        // HIER ZAT DE FOUT: We voegen ': Location?' toe om Kotlin te helpen
        client.lastLocation.addOnSuccessListener { location: Location? ->
            if (location == null) {
                Toast.makeText(activity, "Kan huidige locatie niet bepalen", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            // Start een achtergrondtaak om in de database te zoeken
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val appDb = AppDatabase.getDatabase(activity)
                val localDb = LocalDatabase.getDatabase(activity) // Gebruik de nieuwe LocalDatabase!

                // Zoek zones binnen 200 meter van je huidige plek
                val nearby = appDb.locationDao().getNearby(
                    location.latitude - 0.002, location.latitude + 0.002,
                    location.longitude - 0.002, location.longitude + 0.002,
                    listOf("church", "theater", "cinema", "library", "museum", "cemetery", "hospital", "community", "government")
                )

                var foundZoneId = -1
                var foundZoneName = ""
                var minDistance = Float.MAX_VALUE

                for (zone in nearby) {
                    val res = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, zone.lat, zone.lon, res)
                    if (res[0] < 200 && res[0] < minDistance) {
                        minDistance = res[0]
                        foundZoneId = zone.id
                        foundZoneName = zone.name ?: "Naamloze Zone"
                    }
                }

                withContext(Dispatchers.Main) {
                    if (foundZoneId != -1) {
                        // We hebben een zone gevonden! Vraag of we die moeten negeren.
                        showConfirmDialog(foundZoneName, foundZoneId, localDb)
                    } else {
                        Toast.makeText(activity, "Geen bekende stilte-zone gevonden in de buurt.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showConfirmDialog(name: String, id: Int, db: LocalDatabase) {
        AlertDialog.Builder(activity)
            .setTitle("Zone Gevonden!")
            .setMessage("We zien '$name' in de buurt.\n\nWil je deze locatie toevoegen aan je Veilige Zones (Thuis/Werk)? AutoStil wordt hier dan nooit meer stil.")
            .setPositiveButton("Ja, negeer") { _, _ ->
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    // Opslaan in de blijvende database
                    db.ignoredLocationDao().ignore(IgnoredLocation(id))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "'$name' wordt nu genegeerd! ✅", Toast.LENGTH_SHORT).show()

                        // Eventueel de app direct van het slot halen als hij nu stil was
                        val mgr = SilentManager(activity)
                        if (mgr.isSilentActive()) {
                            mgr.setNormalMode()
                            SilentService.updateNotification(activity, activity.getString(R.string.notification_standby), R.drawable.ic_sound_on, null, null)
                        }
                    }
                }
            }
            .setNegativeButton("Nee", null)
            .show()
    }
}