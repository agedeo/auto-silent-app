package nl.deluxeweb.silentmode

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
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

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        // 1. Als de gebruiker handmatig de boel regelt, doen we niks.
        if (prefs.getBoolean("manual_override", false)) {
            Log.d("BackgroundWorker", "Handmatige modus actief. We doen niets.")
            return Result.success()
        }

        return withContext(Dispatchers.IO) {
            try {
                // STAP 1: Database updaten
                val updateManager = UpdateManager(applicationContext)
                updateManager.downloadUpdate()

                // STAP 2: Waar ben ik NU?
                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(applicationContext)
                val locationTask = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    null
                )
                val location = Tasks.await(locationTask)

                if (location == null) {
                    Log.w("BackgroundWorker", "⚠️ Geen locatie kunnen bepalen op achtergrond")
                    return@withContext Result.retry()
                }

                // STAP 3: De zones verversen EN controleren (Self-Heal)
                refreshGeofencesAndCheckState(location.latitude, location.longitude)

                Result.success()
            } catch (e: Exception) {
                Log.e("BackgroundWorker", "❌ Fout in achtergrond taak", e)
                Result.failure()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun refreshGeofencesAndCheckState(lat: Double, lon: Double) {
        val dbFile = applicationContext.getDatabasePath("silent_locations.db")
        if (!dbFile.exists()) return

        val db = AppDatabase.getDatabase(applicationContext)
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val activeCats = prefs.getStringSet("active_categories", setOf("church", "theater"))?.toList() ?: emptyList()

        // Haal instellingen op
        val radiusStr = prefs.getString("geofence_radius", "80") ?: "80"
        val radius = radiusStr.toFloatOrNull() ?: 80f

        // Pak 99 items
        val targets = db.locationDao().getNearby(
            lat - 0.1, lat + 0.1,
            lon - 0.1, lon + 0.1,
            activeCats
        ).take(99)

        // --- SELF HEAL CHECK ---
        // We berekenen hier handmatig of we in een zone zitten.
        // Dit is de 'Backup' voor als de Geofence EXIT gemist is.
        var actuallyInZone = false
        val results = FloatArray(1)

        for (target in targets) {
            Location.distanceBetween(lat, lon, target.lat, target.lon, results)
            if (results[0] <= (radius + 10)) { // +10m buffer voor GPS drift
                actuallyInZone = true
                break
            }
        }

        val silentManager = SilentManager(applicationContext)
        val wasActiveByApp = prefs.getBoolean("state_active_by_app", false)

        if (actuallyInZone) {
            // We zitten in een zone. Zeker weten dat stilte AAN staat.
            if (!silentManager.isSilentActive()) {
                silentManager.setSilentMode()
                prefs.edit().putBoolean("state_active_by_app", true).apply()
                Log.i("BackgroundWorker", "🔧 Self-Heal: Stiltemodus geforceerd (was onterecht uit)")
            }
        } else {
            // We zitten NERGENS in.
            // Als de app zegt "Ik heb het geluid uitgezet" (active_by_app), maar we zijn er niet meer...
            // ...dan is er iets blijven hangen. Herstel het!
            if (wasActiveByApp && silentManager.isSilentActive()) {
                silentManager.setNormalMode()
                prefs.edit().putBoolean("state_active_by_app", false).apply()
                Log.i("BackgroundWorker", "🩹 Self-Heal: Geluid hersteld! (Stuck state opgelost)")
            }
        }
        // -----------------------

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
            Log.d("BackgroundWorker", "Refreshed: ${targets.size} locaties")
        } catch (e: Exception) {
            Log.e("BackgroundWorker", "Fout bij refresh", e)
        }
    }
}