package fr.aityum.reveiledt

import java.time.ZonedDateTime

data class Plan(val alarm: ZonedDateTime, val firstClass: ZonedDateTime, val summary: String)

object Planner {
    /** Un réveil par jour de cours : premier cours du jour moins [offsetMin]. */
    fun daily(events: List<Event>, offsetMin: Int): List<Plan> =
        events.groupBy { it.start.toLocalDate() }.values
            .map { day -> day.minBy { it.start } }
            .sortedBy { it.start }
            .map { Plan(it.start.minusMinutes(offsetMin.toLong()), it.start, it.summary) }

    /** Prochain réveil encore à venir. */
    fun next(events: List<Event>, offsetMin: Int, now: ZonedDateTime): Plan? =
        daily(events, offsetMin).firstOrNull { it.alarm.isAfter(now) }
}
