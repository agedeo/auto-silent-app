package nl.deluxeweb.silentmode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager

class SilentService : Service() {

    companion object {
        const val CHANNEL_ID = "autostil_foreground_channel"
        const val NOTIFICATION_ID = 1337

        // CENTRALE FUNCTIE: Deze regelt de updates vanuit de hele app
        fun updateNotification(context: Context, text: String, iconRes: Int) {
            // 1. Opslaan in geheugen (zodat hij na herstart bewaard blijft)
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit()
                .putString("last_notif_text", text)
                .putInt("last_notif_icon", iconRes)
                .apply()

            // 2. Direct de melding updaten
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("AutoStil")
                .setContentText(text)
                .setSmallIcon(iconRes)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // AANGEPAST: Lees eerst de laatst bekende status!
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val savedText = prefs.getString("last_notif_text", "🟢 AutoStil is actief")
        val savedIcon = prefs.getInt("last_notif_icon", R.drawable.ic_sound_on)

        // Bouw de notificatie met de OPGESLAGEN tekst
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoStil")
            .setContentText(savedText)
            .setSmallIcon(savedIcon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AutoStil Status", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}