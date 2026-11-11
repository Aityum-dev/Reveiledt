package fr.aityum.reveiledt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Fait sonner le réveil en service de premier plan : le son joue même si l'écran plein écran ne s'affiche pas. */
class AlarmService : Service() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finish()
            ACTION_SNOOZE -> {
                AlarmScheduler.snooze(this, SNOOZE_MIN)
                finish()
            }
            else -> ring()
        }
        return START_NOT_STICKY
    }

    private fun ring() {
        val p = Prefs(this)
        val info = if (p.classAt > 0) "1er cours à ${StatusNotif.hm(p.classAt)} · ${p.summary.orEmpty()}" else "Réveil"
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(info),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        // l'écran de réveil s'ouvre via le full-screen intent de la notification
        stopSound()
        playSound()
        vibrate()
        // programme tout de suite le réveil suivant, même si personne n'appuie sur "Arrêter"
        val app = applicationContext
        Thread { Engine.recompute(app) }.start()
    }

    private fun playSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val candidates = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    setWakeMode(this@AlarmService, PowerManager.PARTIAL_WAKE_LOCK)
                    prepare()
                    start()
                }
                return
            } catch (_: Exception) {
                player?.release()
                player = null
            }
        }
    }

    private fun vibrate() {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), attrs)
    }

    private fun stopSound() {
        player?.run { runCatching { stop() }; release() }
        player = null
        vibrator?.cancel()
    }

    private fun finish() {
        stopSound()
        sendBroadcast(Intent(ACTION_DONE).setPackage(packageName))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }

    private fun notification(info: String): android.app.Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Sonnerie", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // le son est joué par le service
                enableVibration(false)
            },
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val full = PendingIntent.getActivity(
            this, 0,
            Intent(this, AlarmActivity::class.java).putExtra(AlarmActivity.EXTRA_INFO, info), flags,
        )
        fun action(a: String, rc: Int) =
            PendingIntent.getService(this, rc, Intent(this, AlarmService::class.java).setAction(a), flags)

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("⏰ Debout !")
            .setContentText(info)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "Arrêter", action(ACTION_STOP, 1))
            .addAction(0, "Snooze $SNOOZE_MIN min", action(ACTION_SNOOZE, 2))
            .build()
    }

    companion object {
        const val ACTION_STOP = "fr.aityum.reveiledt.STOP"
        const val ACTION_SNOOZE = "fr.aityum.reveiledt.SNOOZE"
        const val ACTION_DONE = "fr.aityum.reveiledt.DONE"
        const val SNOOZE_MIN = 5
        private const val NOTIF_ID = 2
        private const val CHANNEL = "ring"
    }
}
