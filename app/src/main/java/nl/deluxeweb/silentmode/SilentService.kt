package nl.deluxeweb.silentmode

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SilentService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        // Start met een standaard lege notificatie om crash te voorkomen
        startForeground(1, buildStaticNotification(this, getString(R.string.notification_standby), R.drawable.ic_sound_on, null, null))
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Toont de huidige status van AutoStil"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val CHANNEL_ID = "SilentModeChannel"

        // Deze functie roep je aan vanuit MainActivity om de tekst (en promo) te updaten
        fun updateNotification(context: Context, text: String, iconRes: Int, promoText: String? = null, promoUrl: String? = null) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // We bouwen de notificatie via de helper functie
            val notification = buildStaticNotification(context, text, iconRes, promoText, promoUrl)

            nm.notify(1, notification)
        }

        // Statische helper om de notificatie te bouwen (zodat we geen instance van Service nodig hebben)
        fun buildStaticNotification(context: Context, text: String, iconRes: Int, promoText: String?, promoUrl: String?): Notification {
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            // LOGICA VOOR PROMOTIES
            val style = NotificationCompat.BigTextStyle()

            if (promoText != null) {
                // Als er reclame is: Titel = Locatie, Tekst = Promo
                builder.setContentTitle(text)
                builder.setContentText(promoText)
                style.bigText(promoText)
                style.setSummaryText("Aanbieding") // Klein tekstje bovenin

                // Voeg knop toe als er een URL is
                if (promoUrl != null) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(promoUrl))
                    val browserPendingIntent = PendingIntent.getActivity(
                        context,
                        text.hashCode(), // Unieke ID per locatie om conflict te voorkomen
                        browserIntent,
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    // Icoon, Tekst, Actie
                    builder.addAction(android.R.drawable.ic_menu_view, "Bekijk Actie", browserPendingIntent)
                }
            } else {
                // Geen reclame: Standaard weergave
                builder.setContentText(text)
                style.bigText(text)
            }

            builder.setStyle(style)
            return builder.build()
        }
    }
}