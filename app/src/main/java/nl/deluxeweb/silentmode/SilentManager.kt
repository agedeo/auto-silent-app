package nl.deluxeweb.silentmode

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager

class SilentManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun setSilentMode() {
        if (!hasPermission()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val modeType = prefs.getString("silent_mode_type", "priority") ?: "priority"
        val filter = if (modeType == "none") NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_PRIORITY
        notificationManager.setInterruptionFilter(filter)
    }

    fun setNormalMode() {
        if (!hasPermission()) return
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    fun isSilentActive(): Boolean {
        if (!hasPermission()) return false
        return notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) notificationManager.isNotificationPolicyAccessGranted else true
    }
}