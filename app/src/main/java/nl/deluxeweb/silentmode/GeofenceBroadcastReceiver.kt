package nl.deluxeweb.silentmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // CHECK: Staan we wel op AUTO?
        val overrideMode = prefs.getString("override_mode", "AUTO")
        if (overrideMode != "AUTO") return

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null || geofencingEvent.hasError()) return

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

        // 1. Magic Fence Check
        for (fence in triggeringGeofences) {
            if (fence.requestId == "MAGIC_UPDATE_FENCE" && transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                val workRequest = OneTimeWorkRequestBuilder<BackgroundWorker>().build()
                WorkManager.getInstance(context).enqueue(workRequest)
                return
            }
        }

        // 2. Normale Geofences
        val rawId = triggeringGeofences.first().requestId
        val parts = rawId.split("|")
        val locationId = if (parts.isNotEmpty()) parts[0].toIntOrNull() ?: -1 else -1
        val locationName = if (parts.size > 1) parts[1] else "Locatie"

        if (locationName.equals("Locatie", ignoreCase = true) || locationName.equals("Naamloos", ignoreCase = true)) {
            return
        }

        val silentManager = SilentManager(context)
        val uiUpdateIntent = Intent("UPDATE_UI_EVENT")
        uiUpdateIntent.setPackage(context.packageName)

        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_DWELL -> {
                vibrateModern(context, longArrayOf(0, 200, 100, 200))

                silentManager.setSilentMode()
                prefs.edit().putBoolean("state_active_by_app", true).apply()

                SilentService.updateNotification(context, "🔴 Stil bij: $locationName", R.drawable.ic_sound_off)

                uiUpdateIntent.putExtra("MODE", "SILENT")
                uiUpdateIntent.putExtra("LOC_NAAM", locationName)
                uiUpdateIntent.putExtra("LOC_ID", locationId)
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                silentManager.setNormalMode()
                prefs.edit().putBoolean("state_active_by_app", false).apply()

                vibrateModern(context, longArrayOf(0, 500))

                // NIEUW: Duidelijkere tekst
                SilentService.updateNotification(context, context.getString(R.string.notification_standby), R.drawable.ic_sound_on)

                uiUpdateIntent.putExtra("MODE", "NORMAL")
            }
        }

        context.sendBroadcast(uiUpdateIntent)
    }

    private fun vibrateModern(context: Context, pattern: LongArray) {
        try {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            val effect = VibrationEffect.createWaveform(pattern, -1)
            val combinedEffect = CombinedVibration.createParallel(effect)
            val attributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()

            vibratorManager.vibrate(combinedEffect, attributes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}