package fr.aityum.reveiledt

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class Event(val start: ZonedDateTime, val summary: String)

class IcalException(message: String) : Exception(message)

/**
 * Lecteur iCal volontairement simple, sans dépendance.
 * Gère : événements horodatés (UTC, TZID ou heure locale), journées entières (ignorées),
 * STATUS:CANCELLED, récurrences DAILY/WEEKLY, EXDATE et RECURRENCE-ID.
 * Toute récurrence non gérée lève une erreur au lieu d'être ignorée en silence.
 */
object Ical {
    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private val DT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DAYS = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY,
        "SU" to DayOfWeek.SUNDAY,
    )

    /** Événements dont le début est dans [from, to), triés, exprimés dans [zone]. */
    fun parse(text: String, zone: ZoneId, from: ZonedDateTime, to: ZonedDateTime): List<Event> {
        if (!text.contains("BEGIN:VCALENDAR")) {
            throw IcalException("Le lien ne renvoie pas un calendrier iCal")
        }
        val vevents = mutableListOf<List<Prop>>()
        var current: MutableList<Prop>? = null
        for (line in unfold(text)) {
            when {
                line.equals("BEGIN:VEVENT", true) -> current = mutableListOf()
                line.equals("END:VEVENT", true) -> {
                    current?.let { vevents += it }
                    current = null
                }
                current != null -> parseLine(line)?.let { current.add(it) }
            }
        }

        // Occurrences remplacées par une version modifiée (RECURRENCE-ID)
        val overridden = mutableSetOf<Pair<String, Long>>()
        for (ev in vevents) {
            val rid = ev.prop("RECURRENCE-ID") ?: continue
            parseTime(rid, zone)?.let { overridden += ev.uid() to it.toEpochSecond() }
        }

        val out = mutableListOf<Event>()
        for (ev in vevents) {
            if (ev.prop("STATUS")?.value?.trim().equals("CANCELLED", true)) continue
            val start = ev.prop("DTSTART")?.let { parseTime(it, zone) } ?: continue
            val summary = unescape(ev.prop("SUMMARY")?.value ?: "")
            val rrule = ev.prop("RRULE")

            val starts = if (rrule == null || ev.prop("RECURRENCE-ID") != null) {
                sequenceOf(start)
            } else {
                val excluded = ev.filter { it.name == "EXDATE" }
                    .flatMap { p ->
                        p.value.split(',').mapNotNull { v ->
                            parseTime(Prop(p.name, p.params, v), zone)?.toEpochSecond()
                        }
                    }.toSet()
                val uid = ev.uid()
                expand(start, rrule.value, to).filter {
                    val s = it.toEpochSecond()
                    s !in excluded && (uid to s) !in overridden
                }
            }
            starts.filter { !it.isBefore(from) && it.isBefore(to) }
                .forEach { out += Event(it.withZoneSameInstant(zone), summary) }
        }
        return out.sortedBy { it.start }
    }

    private fun expand(start: ZonedDateTime, rule: String, to: ZonedDateTime): Sequence<ZonedDateTime> {
        val parts = rule.split(';').filter { '=' in it }
            .associate { it.substringBefore('=').trim().uppercase() to it.substringAfter('=').trim() }
        val freq = parts["FREQ"]?.uppercase()
        val unsupported = parts.keys - setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "WKST")
        if (unsupported.isNotEmpty() || freq !in setOf("DAILY", "WEEKLY") ||
            (freq == "DAILY" && "BYDAY" in parts)
        ) {
            throw IcalException("Récurrence non gérée : $rule")
        }
        val interval = (parts["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val count = parts["COUNT"]?.toIntOrNull()
        val until = parts["UNTIL"]?.let { parseUntil(it, start.zone) }
        val days = parts["BYDAY"]?.split(',')?.map {
            DAYS[it.trim().takeLast(2).uppercase()] ?: throw IcalException("Récurrence non gérée : $rule")
        }?.toSet() ?: setOf(start.dayOfWeek)

        val startDate = start.toLocalDate()
        val time = start.toLocalTime()
        val tz = start.zone
        val monday = TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)
        val firstWeek = startDate.with(monday)
        val lastDay = to.withZoneSameInstant(tz).toLocalDate().plusDays(1)

        return sequence {
            var n = 0
            var d = startDate
            while (!d.isAfter(lastDay)) {
                val matches = if (freq == "DAILY") {
                    ChronoUnit.DAYS.between(startDate, d) % interval == 0L
                } else {
                    d.dayOfWeek in days && ChronoUnit.WEEKS.between(firstWeek, d.with(monday)) % interval == 0L
                }
                if (matches) {
                    val occ = ZonedDateTime.of(d, time, tz)
                    if (until != null && occ.isAfter(until)) break
                    if (count != null && n >= count) break
                    n++
                    yield(occ)
                }
                d = d.plusDays(1)
            }
        }
    }

    /** null pour un événement "journée entière". */
    private fun parseTime(p: Prop, zone: ZoneId): ZonedDateTime? {
        val v = p.value.trim()
        if (p.params["VALUE"].equals("DATE", true) || v.length == 8) return null
        return try {
            if (v.endsWith("Z", true)) {
                LocalDateTime.parse(v.dropLast(1), DT).atZone(ZoneOffset.UTC)
            } else {
                LocalDateTime.parse(v, DT).atZone(p.params["TZID"]?.let(::zoneOf) ?: zone)
            }
        } catch (e: DateTimeParseException) {
            throw IcalException("Date illisible dans le calendrier : $v")
        }
    }

    private fun parseUntil(v: String, tz: ZoneId): ZonedDateTime =
        if (v.length == 8) {
            LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atTime(23, 59, 59).atZone(tz)
        } else {
            parseTime(Prop("UNTIL", emptyMap(), v), tz) ?: throw IcalException("UNTIL illisible : $v")
        }

    private fun zoneOf(id: String): ZoneId? = runCatching { ZoneId.of(id.trim('"').trim()) }.getOrNull()

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (line in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((line.startsWith(' ') || line.startsWith('\t')) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + line.substring(1)
            } else if (line.isNotBlank()) {
                out += line.trimEnd()
            }
        }
        return out
    }

    private fun parseLine(line: String): Prop? {
        var quoted = false
        var colon = -1
        for ((i, c) in line.withIndex()) {
            if (c == '"') quoted = !quoted
            else if (c == ':' && !quoted) { colon = i; break }
        }
        if (colon <= 0) return null
        val segs = line.substring(0, colon).split(';')
        val params = segs.drop(1).filter { '=' in it }
            .associate { it.substringBefore('=').uppercase() to it.substringAfter('=').trim('"') }
        return Prop(segs[0].trim().uppercase(), params, line.substring(colon + 1))
    }

    private fun unescape(s: String) = s
        .replace("\\n", " ").replace("\\N", " ")
        .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
        .trim()

    private fun List<Prop>.prop(name: String) = firstOrNull { it.name == name }
    private fun List<Prop>.uid() = prop("UID")?.value?.trim() ?: ""
}
