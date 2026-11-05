package io.github.hypercar.autowake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import io.github.hypercar.session.AutomaticConnectionPause
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.settings.SettingsStore

/** Inexact wake-up backup when Android reclaims the process during the manual-stop pause. */
class AutomaticConnectionResume : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        HyperCarRepo.startAutomaticListening(context).invokeOnCompletion { result.finish() }
    }

    companion object {
        private fun boot(context: Context) = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        }.getOrDefault(-1)

        fun remaining(context: Context): Long = AutomaticConnectionPause.decode(SettingsStore.automaticPause)
            ?.remaining(boot(context), SystemClock.elapsedRealtime(), System.currentTimeMillis()) ?: 0L

        fun pause(context: Context) {
            SettingsStore.automaticPause = AutomaticConnectionPause.start(
                boot(context), SystemClock.elapsedRealtime(), System.currentTimeMillis()).encode()
        }

        private fun pending(context: Context) = PendingIntent.getBroadcast(context, 551,
            Intent(context, AutomaticConnectionResume::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun schedule(context: Context, remaining: Long) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = pending(context)
            if (remaining <= 0 || !SettingsStore.autoConnect) alarms.cancel(intent)
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + remaining, intent)
        }
    }
}
