package fr.aityum.reveiledt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalTime
import java.time.ZonedDateTime

object AlarmScheduler {
    private const val RC_MAIN = 1
    private const val RC_SNOOZE = 2
    private const val RC_DAILY = 3
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    val DAILY_SYNC: LocalTime = LocalTime.of(19, 0)

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    /** Le réveil principal. setAlarmClock = l'API la plus fiable, ignore le mode Doze. */
    fun set(ctx: Context, at: Long) = setClock(ctx, RC_MAIN, at)
    fun cancel(ctx: Context) = am(ctx).cancel(ringIntent(ctx, RC_MAIN))

    fun snooze(ctx: Context, minutes: Int) = setClock(ctx, RC_SNOOZE, System.currentTimeMillis() + minutes * 60_000L)
    fun test(ctx: Context) = setClock(ctx, RC_SNOOZE, System.currentTimeMillis() + 10_000L)

    private fun setClock(ctx: Context, rc: Int, at: Long) {
        if (!canExact(ctx)) return
        val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), FLAGS)
        am(ctx).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ringIntent(ctx, rc))
    }

    private fun ringIntent(ctx: Context, rc: Int) =
        PendingIntent.getBroadcast(ctx, rc, Intent(ctx, AlarmReceiver::class.java), FLAGS)

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
