package fr.aityum.reveiledt

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

object Engine {
    private const val WINDOW_DAYS = 14L
    /** Au-delà, l'EDT est considéré comme non vérifié et la notif passe au rouge. */
    const val FRESH_MS = 12 * 3600_000L
    private val lock = Any()

    private fun zone(): ZoneId = ZoneId.systemDefault()
    private fun now(): ZonedDateTime = ZonedDateTime.now(zone())

    /** Télécharge l'EDT, met à jour le cache, reprogramme le réveil. Renvoie false si le téléchargement a échoué. */
    fun sync(ctx: Context): Boolean = synchronized(lock) {
        val p = Prefs(ctx)
        val url = p.url
        var ok = false
        if (p.enabled && !url.isNullOrBlank()) {
            try {
                val body = fetch(url)
                parse(body) // valide avant d'écraser le cache
                p.cache = body
                p.lastSuccess = System.currentTimeMillis()
                p.lastError = null
                ok = true
            } catch (e: Exception) {
                p.lastError = describe(e)
            }
        }
        recomputeLocked(ctx, p)
        ok
    }

    /** Recalcule le réveil à partir du cache, sans réseau. */
    fun recompute(ctx: Context) = synchronized(lock) { recomputeLocked(ctx, Prefs(ctx)) }

    private fun recomputeLocked(ctx: Context, p: Prefs) {
        val cache = p.cache
        val plan = if (p.enabled && cache != null) {
            try {
                Planner.next(parse(cache), p.offsetMin, now())
            } catch (e: Exception) {
                p.lastError = describe(e)
                null
            }
        } else null

        if (plan != null) {
            val at = plan.alarm.toInstant().toEpochMilli()
            AlarmScheduler.set(ctx, at)
            p.alarmAt = at
            p.classAt = plan.firstClass.toInstant().toEpochMilli()
            p.summary = plan.summary
        } else {
            AlarmScheduler.cancel(ctx)
            p.alarmAt = 0
            p.classAt = 0
            p.summary = null
        }
        StatusNotif.show(ctx)
    }

    /** Les prochains jours de cours, pour l'aperçu dans l'app. */
    fun preview(ctx: Context): List<Plan> {
        val p = Prefs(ctx)
        val cache = p.cache ?: return emptyList()
        val now = now()
        return runCatching { Planner.daily(parse(cache), p.offsetMin).filter { it.alarm.isAfter(now) } }
            .getOrDefault(emptyList())
    }

    private fun parse(text: String): List<Event> {
        val from = now().toLocalDate().atStartOfDay(zone())
        return Ical.parse(text, zone(), from, from.plusDays(WINDOW_DAYS))
    }

    private fun fetch(url: String): String {
        val fixed = url.trim().replaceFirst(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://")
        val c = URL(fixed).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "ReveilEDT/1.0")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is IcalException -> e.message ?: "Calendrier illisible"
        is UnknownHostException -> "Pas de connexion internet"
        is SocketTimeoutException -> "Le site de l'université ne répond pas"
        is IOException -> "Erreur réseau : ${e.message}"
        else -> "${e.javaClass.simpleName} : ${e.message}"
    }

    // ---------- planification des synchros ----------

    fun ensureSchedules(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "hourly", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build(),
        )
        AlarmScheduler.scheduleDaily(ctx)
    }

    fun syncSoon(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ok = Engine.sync(applicationContext)
        return if (ok || runAttemptCount >= 5) Result.success() else Result.retry()
    }
}
