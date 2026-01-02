package nl.deluxeweb.silentmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SilentService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
    }

    private fun startForegroundService() {
        val channelId = "autostil_foreground_channel"
        val channelName = "AutoStil Achtergrond Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoStil is actief")
            .setContentText("We houden de wacht op de achtergrond 🌍")
            .setSmallIcon(R.drawable.ic_sound_on) // Zorg dat dit icoon bestaat
            .setOngoing(true)
            .build()

        // ID 1 voor de permanente notificatie
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY zorgt dat Android de service herstart als hij crasht
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}