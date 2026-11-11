package fr.aityum.reveiledt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Déclenché par AlarmManager à l'heure du réveil. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, AlarmService::class.java))
    }
}

/** Actions de la notif permanente + synchro quotidienne de 19h. */
class StatusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPOST -> StatusNotif.show(ctx)
            ACTION_SYNC -> Engine.syncSoon(ctx)
            ACTION_DAILY -> {
                Engine.syncSoon(ctx)
                AlarmScheduler.scheduleDaily(ctx)
            }
        }
    }

    companion object {
        const val ACTION_REPOST = "fr.aityum.reveiledt.REPOST"
        const val ACTION_SYNC = "fr.aityum.reveiledt.SYNC"
        const val ACTION_DAILY = "fr.aityum.reveiledt.DAILY"
    }
}

/** Les alarmes Android sont effacées au redémarrage : on reprogramme tout. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Engine.ensureSchedules(ctx)
        val pending = goAsync()
        Thread {
            try {
                Engine.recompute(ctx) // réveil immédiat depuis le cache, même sans réseau
            } finally {
                pending.finish()
            }
            Engine.syncSoon(ctx)
        }.start()
    }
}
