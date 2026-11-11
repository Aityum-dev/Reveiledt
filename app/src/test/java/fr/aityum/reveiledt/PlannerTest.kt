package fr.aityum.reveiledt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PlannerTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), paris)

    private fun cal(vararg events: String) =
        "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events.joinToString("") {
            "BEGIN:VEVENT\r\n${it.trimIndent().replace("\n", "\r\n")}\r\nEND:VEVENT\r\n"
        } + "END:VCALENDAR\r\n"

    private fun parse(text: String, now: ZonedDateTime) =
        Ical.parse(text, paris, now.toLocalDate().atStartOfDay(paris), now.toLocalDate().atStartOfDay(paris).plusDays(14))

    // Mercredi 11/11/2026 à 19h
    private val now = at("2026-11-11T19:00")

    @Test fun utcEventLikeAde() {
        // 07:30 UTC = 08:30 Paris en novembre
        val c = cal("UID:a\nDTSTART:20261112T073000Z\nDTEND:20261112T093000Z\nSUMMARY:R3.01 Dev\\, Web")
        val p = Planner.next(parse(c, now), 60, now)!!
        assertEquals(at("2026-11-12T07:30"), p.alarm.withZoneSameInstant(paris))
        assertEquals("R3.01 Dev, Web", p.summary)
    }

    @Test fun earliestClassOfTheDayWins() {
        val c = cal(
            "UID:a\nDTSTART;TZID=Europe/Paris:20261112T140000\nSUMMARY:Aprem",
            "UID:b\nDTSTART;TZID=Europe/Paris:20261112T093000\nSUMMARY:Matin",
        )
        val p = Planner.next(parse(c, now), 45, now)!!
        assertEquals(at("2026-11-12T08:45"), p.alarm)
        assertEquals("Matin", p.summary)
    }

    @Test fun allDayAndCancelledAreIgnored() {
        val c = cal(
            "UID:a\nDTSTART;VALUE=DATE:20261112\nSUMMARY:Férié",
            "UID:b\nDTSTART:20261112T060000Z\nSTATUS:CANCELLED\nSUMMARY:Annulé",
            "UID:c\nDTSTART:20261112T090000Z\nSUMMARY:Vrai cours",
        )
        assertEquals("Vrai cours", Planner.next(parse(c, now), 60, now)!!.summary)
    }

    @Test fun weekendSkipsToMonday() {
        val sat = at("2026-11-13T19:00") // vendredi soir
        val c = cal("UID:a\nDTSTART:20261116T070000Z\nSUMMARY:Lundi")
        assertEquals(at("2026-11-16T07:00"), Planner.next(parse(c, sat), 60, sat)!!.alarm.withZoneSameInstant(paris))
    }

    @Test fun passedAlarmTodayGoesToNextDay() {
        val morning = at("2026-11-12T07:40") // réveil 07:00 déjà passé, cours à 08:00
        val c = cal(
            "UID:a\nDTSTART;TZID=Europe/Paris:20261112T080000\nSUMMARY:Jeudi",
            "UID:b\nDTSTART;TZID=Europe/Paris:20261112T140000\nSUMMARY:Jeudi aprem",
            "UID:c\nDTSTART;TZID=Europe/Paris:20261113T100000\nSUMMARY:Vendredi",
        )
        assertEquals("Vendredi", Planner.next(parse(c, morning), 60, morning)!!.summary)
    }

    @Test fun weeklyRecurrenceWithExdateAndOverride() {
        val c = cal(
            "UID:r\nDTSTART;TZID=Europe/Paris:20260910T080000\nRRULE:FREQ=WEEKLY\nEXDATE;TZID=Europe/Paris:20261112T080000\nSUMMARY:Hebdo",
            "UID:r\nRECURRENCE-ID;TZID=Europe/Paris:20261119T080000\nDTSTART;TZID=Europe/Paris:20261119T100000\nSUMMARY:Hebdo décalé",
        )
        val plans = Planner.daily(parse(c, now), 60)
        // 12/11 exclu, 19/11 décalé à 10h, 26/11 hors fenêtre de 14 jours (25/11 inclus)
        assertEquals(listOf(at("2026-11-19T09:00")), plans.map { it.alarm })
        assertEquals("Hebdo décalé", plans[0].summary)
    }

    @Test fun weeklyByDayAcrossDst() {
        // Série commencée en été (UTC+2), doit rester à 08:00 locale en hiver
        val c = cal("UID:r\nDTSTART;TZID=Europe/Paris:20260907T080000\nRRULE:FREQ=WEEKLY;BYDAY=MO,TH;UNTIL=20261231T000000Z\nSUMMARY:X")
        val plans = Planner.daily(parse(c, now), 30)
        assertEquals(listOf(at("2026-11-12T07:30"), at("2026-11-16T07:30"), at("2026-11-19T07:30"), at("2026-11-23T07:30")), plans.map { it.alarm })
    }

    @Test fun foldedLinesAndCount() {
        val c = cal("UID:r\nDTSTART;TZID=Europe/Paris:20261112T080000\nRRULE:FREQ=DAILY;COUNT=2\nSUMMARY:Cours avec un nom\n  très long")
        val plans = Planner.daily(parse(c, now), 0)
        assertEquals(2, plans.size)
        assertEquals("Cours avec un nom très long", plans[0].summary)
    }

    @Test fun noClassMeansNoAlarm() {
        assertNull(Planner.next(parse(cal(), now), 60, now))
    }

    @Test fun unsupportedRecurrenceFailsLoudly() {
        val c = cal("UID:r\nDTSTART:20261112T080000Z\nRRULE:FREQ=MONTHLY;BYMONTHDAY=12\nSUMMARY:X")
        assertTrue(runCatching { parse(c, now) }.exceptionOrNull() is IcalException)
    }

    @Test fun htmlPageIsRejected() {
        assertTrue(runCatching { parse("<html>Connexion requise</html>", now) }.exceptionOrNull() is IcalException)
    }
}
