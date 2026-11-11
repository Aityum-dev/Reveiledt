package fr.aityum.reveiledt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Notification permanente : verte si le réveil est bien programmé, rouge sinon. */
object StatusNotif {
    private const val ID = 1
    private const val CHANNEL = "status"

    enum class Level(val color: Int, val dot: String) {
        OK(0xFF2E9E5B.toInt(), "🟢"),
        BAD(0xFFD93A3A.toInt(), "🔴"),
        OFF(0xFF7A7F9A.toInt(), "⚪"),
    }

    data class State(val level: Level, val title: String, val text: String)

    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("EEEE d", Locale.FRENCH)

    fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM)

    fun day(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (d) {
            today -> "aujourd'hui"
            today.plusDays(1) -> "demain"
            else -> d.format(DAY)
        }
    }

    fun state(ctx: Context): State {
        val p = Prefs(ctx)
        val now = System.currentTimeMillis()
        val audio = ctx.getSystemService(AudioManager::class.java)
        return when {
            !p.enabled -> State(Level.OFF, "Réveil désactivé", "Active-le dans l'app")
            p.url.isNullOrBlank() -> State(Level.BAD, "Aucun emploi du temps", "Ajoute ton lien iCal dans l'app")
            !AlarmScheduler.canExact(ctx) -> State(Level.BAD, "Réveil impossible", "Autorise les alarmes dans l'app")
            audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0 ->
                State(Level.BAD, "Volume des alarmes à 0", "Le réveil ne sonnera pas, monte le volume")
            p.alarmAt <= now -> State(
                Level.BAD, "Aucun réveil programmé",
                p.lastError ?: "Aucun cours trouvé dans les 14 prochains jours",
            )
            now - p.lastSuccess > Engine.FRESH_MS -> State(
                Level.BAD, "Réveil ${day(p.alarmAt)} à ${hm(p.alarmAt)}, EDT non vérifié",
                "Pas de mise à jour depuis ${(now - p.lastSuccess) / 3600_000}h" +
                    (p.lastError?.let { " · $it" } ?: ""),
            )
            else -> State(
                Level.OK, "Réveil ${day(p.alarmAt)} à ${hm(p.alarmAt)}",
                "1er cours ${hm(p.classAt)} · ${p.summary.orEmpty()} · vérifié à ${hm(p.lastSuccess)}",
            )
        }
    }

    fun show(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "État du réveil", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notification permanente verte ou rouge"
                setShowBadge(false)
            },
        )
        if (!nm.areNotificationsEnabled()) return

        val s = state(ctx)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), flags)
        fun receiver(action: String, rc: Int) = PendingIntent.getBroadcast(
            ctx, rc, Intent(ctx, StatusReceiver::class.java).setAction(action), flags,
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(if (s.level == Level.OK) R.drawable.ic_alarm else R.drawable.ic_alert)
            .setContentTitle("${s.level.dot} ${s.title}")
            .setContentText(s.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.text))
            .setColor(s.level.color)
            .setColorized(true)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            // si l'utilisateur la balaie (possible depuis Android 14), elle revient
            .setDeleteIntent(receiver(StatusReceiver.ACTION_REPOST, 10))
            .addAction(0, "Synchroniser", receiver(StatusReceiver.ACTION_SYNC, 11))
            .build()
        try {
            nm.notify(ID, n)
        } catch (_: SecurityException) {
        }
    }
}
