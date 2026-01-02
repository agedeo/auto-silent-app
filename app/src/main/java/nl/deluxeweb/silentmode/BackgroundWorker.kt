package nl.deluxeweb.silentmode

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.deluxeweb.silentmode.data.AppDatabase

class BackgroundWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        Log.d("BackgroundWorker", "👷‍♂️ Achtergrond taak gestart...")

        return withContext(Dispatchers.IO) {
            try {
                // STAP 1: Database updaten
                val updateManager = UpdateManager(applicationContext)
                updateManager.downloadUpdate()

                // STAP 2: Waar ben ik NU?
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)

                // We wachten op de GPS (Tasks.await maakt het synchroon)
                val locationTask = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    null
                )
                val location = Tasks.await(locationTask)

                if (location == null) {
                    Log.w("BackgroundWorker", "⚠️ Geen locatie kunnen bepalen op achtergrond")
                    return@withContext Result.retry()
                }

                // STAP 3: De zones verversen
                refreshGeofences(location.latitude, location.longitude)

                Result.success()
            } catch (e: Exception) {
                Log.e("BackgroundWorker", "❌ Fout in achtergrond taak", e)
                Result.failure()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun refreshGeofences(lat: Double, lon: Double) {
        val dbFile = applicationContext.getDatabasePath("silent_locations.db")
        if (!dbFile.exists()) return

        val db = AppDatabase.getDatabase(applicationContext)
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val activeCats = prefs.getStringSet("active_categories", setOf("church", "theater"))?.toList() ?: emptyList()

        // Pak 99 items
        val targets = db.locationDao().getNearby(
            lat - 0.1, lat + 0.1,
            lon - 0.1, lon + 0.1,
            activeCats
        ).take(99)

        if (targets.isEmpty()) return

        val geofenceHelper = GeofenceHelper(applicationContext)
        val geofenceList = targets.map { loc ->
            val idMetNaam = "${loc.id}|${loc.name ?: "Locatie"}"
            geofenceHelper.getGeofence(idMetNaam, loc.lat, loc.lon)
        }.toMutableList()

        // Voeg Magic Fence toe
        geofenceList.add(geofenceHelper.getUpdateGeofence(lat, lon))

        val request = geofenceHelper.getGeofencingRequest(geofenceList)
        val pendingIntent = geofenceHelper.geofencePendingIntent
        val client = LocationServices.getGeofencingClient(applicationContext)

        try {
            Tasks.await(client.removeGeofences(pendingIntent))
            Tasks.await(client.addGeofences(request, pendingIntent))
            Log.d("BackgroundWorker", "Refreshed: ${targets.size} locaties + Update Fence")
        } catch (e: Exception) {
            Log.e("BackgroundWorker", "Fout bij refresh", e)
        }
    }
}