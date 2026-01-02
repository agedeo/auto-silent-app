package nl.deluxeweb.silentmode

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.preference.PreferenceManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.deluxeweb.silentmode.data.AppDatabase

class BootReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Telefoon is opgestart! Silent Mode heractiveren...")

            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    heractiveerGeofences(context)
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Fout bij opstarten", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun heractiveerGeofences(context: Context) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        // Probeer locatie te pakken
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        laadDatabaseEnZetGeofences(context, location.latitude, location.longitude)
                    }
                } else {
                    Log.w("BootReceiver", "Geen locatie gevonden na reboot.")
                }
            }
            .addOnFailureListener {
                Log.e("BootReceiver", "Locatie fout", it)
            }
    }

    @SuppressLint("MissingPermission")
    private suspend fun laadDatabaseEnZetGeofences(context: Context, lat: Double, lon: Double) {
        val dbFile = context.getDatabasePath("silent_locations.db")
        if (!dbFile.exists()) return

        val db = AppDatabase.getDatabase(context)

        // DE FIX: Eerst de categorieën ophalen uit de instellingen
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val activeCats = prefs.getStringSet("active_categories", setOf("church", "theater"))?.toList() ?: emptyList()

        // Nu de categorieën meegeven aan de database query
        val nearbyLocations = db.locationDao().getNearby(
            lat - 0.1, lat + 0.1,
            lon - 0.1, lon + 0.1,
            activeCats // <--- Hier miste hij eerst!
        )
        val targets = nearbyLocations.take(100)

        if (targets.isEmpty()) return

        val geofenceHelper = GeofenceHelper(context)
        val geofenceList = targets.map { loc ->
            val idMetNaam = "${loc.id}|${loc.name ?: "Locatie"}"
            geofenceHelper.getGeofence(idMetNaam, loc.lat, loc.lon)
        }

        val request = geofenceHelper.getGeofencingRequest(geofenceList)
        val pendingIntent = geofenceHelper.geofencePendingIntent
        val geofencingClient = LocationServices.getGeofencingClient(context)

        geofencingClient.addGeofences(request, pendingIntent)
            .addOnSuccessListener {
                Log.d("BootReceiver", "Succes! ${targets.size} zones herladen na reboot.")
            }
            .addOnFailureListener { e ->
                Log.e("BootReceiver", "Kon zones niet laden", e)
            }
    }
}