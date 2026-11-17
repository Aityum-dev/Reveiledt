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
import androidx.work.workDataOf
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.LocalDate
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

    /** Télécharge l'EDT, met à jour le cache, reprogramme les réveils. Renvoie false si le téléchargement a échoué. */
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

    /** Recalcule les réveils à partir du cache, sans réseau. */
    fun recompute(ctx: Context) = synchronized(lock) { recomputeLocked(ctx, Prefs(ctx)) }

    fun setOffsets(ctx: Context, offsets: List<Int>) = synchronized(lock) {
        val p = Prefs(ctx)
        p.offsets = offsets.take(AlarmScheduler.MAX_ALARMS)
        recomputeLocked(ctx, p)
    }

    fun toggleSkip(ctx: Context, day: LocalDate) = synchronized(lock) {
        val p = Prefs(ctx)
        p.skipped = if (day in p.skipped) p.skipped - day else p.skipped + day
        recomputeLocked(ctx, p)
    }

    private fun recomputeLocked(ctx: Context, p: Prefs) {
        val now = now()
        val today = now.toLocalDate()
        if (p.skipped.any { it.isBefore(today) }) p.skipped = p.skipped.filterTo(mutableSetOf()) { !it.isBefore(today) }

        val cache = p.cache
        val events = if (p.enabled && cache != null) {
            try {
                parse(cache)
            } catch (e: Exception) {
                p.lastError = describe(e)
                null
            }
        } else null

        val skip = p.skipped
        val plans = p.offsets.take(AlarmScheduler.MAX_ALARMS).map { off ->
            events?.let { Planner.next(it, off, now, skip) }
        }
        AlarmScheduler.cancelAll(ctx)
        plans.forEachIndexed { i, plan ->
            if (plan != null) {
                val info = "1er cours à ${StatusNotif.hm(plan.firstClass)} · ${plan.summary}"
                AlarmScheduler.set(ctx, i, plan.alarm.toInstant().toEpochMilli(), info)
            }
        }

        val first = plans.filterNotNull().minByOrNull { it.alarm }
        p.alarmAt = first?.alarm?.toInstant()?.toEpochMilli() ?: 0
        p.classAt = first?.firstClass?.toInstant()?.toEpochMilli() ?: 0
        p.summary = first?.summary
        p.alarmCount = plans.count { it != null && it.firstClass == first?.firstClass }
        StatusNotif.show(ctx)
    }

    /** Les 7 prochains jours, avec tous les cours et les heures de réveil. */
    fun week(ctx: Context): List<DayView> {
        val p = Prefs(ctx)
        val cache = p.cache ?: return emptyList()
        val events = runCatching { parse(cache) }.getOrNull() ?: return emptyList()
        return Planner.week(events, p.offsets, now().toLocalDate(), p.skipped)
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

    /** Synchro de 19h : sans contrainte réseau, pour que la notif de vérification arrive même hors ligne. */
    fun syncDaily(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "daily", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setInputData(workDataOf(SyncWorker.DAILY to true)).build(),
        )
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val ok = Engine.sync(applicationContext)
        if (inputData.getBoolean(DAILY, false)) {
            StatusNotif.daily(applicationContext, ok)
            if (!ok) Engine.syncSoon(applicationContext) // réessaie dès que le réseau revient
            return Result.success()
        }
        return if (ok || runAttemptCount >= 5) Result.success() else Result.retry()
    }

    companion object {
        const val DAILY = "daily"
    }
}
