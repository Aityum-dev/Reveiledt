package fr.aityum.reveiledt

import java.time.LocalDate
import java.time.ZonedDateTime

data class Plan(val alarm: ZonedDateTime, val firstClass: ZonedDateTime, val summary: String)

/** Un jour de la vue semaine. */
data class DayView(
    val date: LocalDate,
    val events: List<Event>,
    val alarms: List<ZonedDateTime>,
    val skipped: Boolean,
)

object Planner {
    /** Un réveil par jour de cours (hors jours désactivés) : premier cours du jour moins [offsetMin]. */
    fun daily(events: List<Event>, offsetMin: Int, skip: Set<LocalDate> = emptySet()): List<Plan> =
        events.groupBy { it.start.toLocalDate() }
            .filterKeys { it !in skip }
            .values
            .map { day -> day.minBy { it.start } }
            .sortedBy { it.start }
            .map { Plan(it.start.minusMinutes(offsetMin.toLong()), it.start, it.summary) }

    /** Prochain réveil encore à venir pour ce décalage. Chaque réveil est calculé indépendamment. */
    fun next(events: List<Event>, offsetMin: Int, now: ZonedDateTime, skip: Set<LocalDate> = emptySet()): Plan? =
        daily(events, offsetMin, skip).firstOrNull { it.alarm.isAfter(now) }

    /** Les [days] jours à partir de [today], avec tous les cours et les heures de réveil. */
    fun week(
        events: List<Event>,
        offsets: List<Int>,
        today: LocalDate,
        skip: Set<LocalDate>,
        days: Int = 7,
    ): List<DayView> = (0 until days).map { i ->
        val d = today.plusDays(i.toLong())
        val evs = events.filter { it.start.toLocalDate() == d }.sortedBy { it.start }
        val first = evs.firstOrNull()
        DayView(
            date = d,
            events = evs,
            alarms = first?.let { f -> offsets.map { f.start.minusMinutes(it.toLong()) }.sorted() } ?: emptyList(),
            skipped = d in skip,
        )
    }
}
