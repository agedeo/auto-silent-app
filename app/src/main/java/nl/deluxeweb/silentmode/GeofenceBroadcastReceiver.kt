package nl.deluxeweb.silentmode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val isManualMode = prefs.getBoolean("manual_override", false)

        if (isManualMode) return

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null || geofencingEvent.hasError()) return

        val transition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

        for (fence in triggeringGeofences) {
            if (fence.requestId == "MAGIC_UPDATE_FENCE" && transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
                val workRequest = OneTimeWorkRequestBuilder<BackgroundWorker>().build()
                WorkManager.getInstance(context).enqueue(workRequest)
                return
            }
        }

        val rawId = triggeringGeofences.first().requestId
        val parts = rawId.split("|")
        val locationId = if (parts.isNotEmpty()) parts[0].toIntOrNull() ?: -1 else -1
        val locationName = if (parts.size > 1) parts[1] else "Locatie"

        // EXTRA BEVEILIGING: NEGEER NAAMLOZE LOCATIES
        if (locationName.equals("Locatie", ignoreCase = true) || locationName.equals("Naamloos", ignoreCase = true)) {
            return
        }

        val silentManager = SilentManager(context)
        val uiUpdateIntent = Intent("UPDATE_UI_EVENT")

        when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_DWELL -> {
                silentManager.setSilentMode()
                stuurNotificatie(context, context.getString(R.string.notif_enter_title), context.getString(R.string.notif_enter_text, locationName))
                uiUpdateIntent.putExtra("MODE", "SILENT")
                uiUpdateIntent.putExtra("LOC_NAAM", locationName)
                uiUpdateIntent.putExtra("LOC_ID", locationId)
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                silentManager.setNormalMode()
                stuurNotificatie(context, context.getString(R.string.notif_exit_title), context.getString(R.string.notif_exit_text, locationName))
                uiUpdateIntent.putExtra("MODE", "NORMAL")
            }
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(uiUpdateIntent)
    }

    private fun stuurNotificatie(context: Context, titel: String, tekst: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "silent_mode_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(channelId, context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH)
                notificationManager.createNotificationChannel(channel)
            }
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(titel)
            .setContentText(tekst)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}