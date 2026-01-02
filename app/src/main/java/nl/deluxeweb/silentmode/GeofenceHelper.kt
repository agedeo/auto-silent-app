package nl.deluxeweb.silentmode

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.preference.PreferenceManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest

class GeofenceHelper(base: Context) : ContextWrapper(base) {

    val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    fun getGeofence(id: String, lat: Double, lon: Double): Geofence {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val delayMs = prefs.getString("loitering_delay", "0")?.toInt() ?: 0
        val radiusStr = prefs.getString("geofence_radius", "80") ?: "80"
        val radius = radiusStr.toFloatOrNull() ?: 80f

        val type = if (delayMs > 0)
            Geofence.GEOFENCE_TRANSITION_DWELL or Geofence.GEOFENCE_TRANSITION_EXIT
        else
            Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT

        return Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(lat, lon, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setNotificationResponsiveness(0) // <--- AANGEPAST: 0 = Direct reageren (Sneller/Stabieler)
            .setTransitionTypes(type)
            .setLoiteringDelay(delayMs)
            .build()
    }

    fun getUpdateGeofence(lat: Double, lon: Double): Geofence {
        return Geofence.Builder()
            .setRequestId("MAGIC_UPDATE_FENCE")
            .setCircularRegion(lat, lon, 1500f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setNotificationResponsiveness(0) // <--- AANGEPAST
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
    }

    fun getGeofencingRequest(geofenceList: List<Geofence>): GeofencingRequest {
        return GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofenceList)
            .build()
    }
}