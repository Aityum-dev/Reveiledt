package fr.aityum.reveiledt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalTime
import java.time.ZonedDateTime

object AlarmScheduler {
    const val MAX_ALARMS = 10
    private const val RC_LEGACY = 1 // ancien réveil unique (v1)
    private const val RC_SNOOZE = 2
    private const val RC_DAILY = 3
    private const val RC_BASE = 100
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    const val EXTRA_INFO = "info"
    val DAILY_SYNC: LocalTime = LocalTime.of(19, 0)

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    /** Réveil n°[index]. setAlarmClock = l'API la plus fiable, ignore le mode Doze. */
    fun set(ctx: Context, index: Int, at: Long, info: String) = setClock(ctx, RC_BASE + index, at, info)

    fun cancel(ctx: Context, index: Int) = am(ctx).cancel(ringIntent(ctx, RC_BASE + index, null))

    fun cancelAll(ctx: Context) {
        am(ctx).cancel(ringIntent(ctx, RC_LEGACY, null))
        for (i in 0 until MAX_ALARMS) cancel(ctx, i)
    }

    fun snooze(ctx: Context, minutes: Int, info: String?) =
        setClock(ctx, RC_SNOOZE, System.currentTimeMillis() + minutes * 60_000L, info)

    fun test(ctx: Context) = setClock(ctx, RC_SNOOZE, System.currentTimeMillis() + 10_000L, "Test de sonnerie")

    private fun setClock(ctx: Context, rc: Int, at: Long, info: String?) {
        if (!canExact(ctx)) return
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ringIntent(ctx, rc, info))
    }

    private fun ringIntent(ctx: Context, rc: Int, info: String?) = PendingIntent.getBroadcast(
        ctx, rc, Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_INFO, info), FLAGS,
    )

    /** Synchro garantie chaque jour à 19h, même téléphone en veille. */
    fun scheduleDaily(ctx: Context) {
        val now = ZonedDateTime.now()
        var next = now.with(DAILY_SYNC)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val pi = PendingIntent.getBroadcast(
            ctx, RC_DAILY,
            Intent(ctx, StatusReceiver::class.java).setAction(StatusReceiver.ACTION_DAILY), FLAGS,
        )
        val at = next.toInstant().toEpochMilli()
        if (canExact(ctx)) am(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am(ctx).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}
