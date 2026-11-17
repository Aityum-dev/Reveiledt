package fr.aityum.reveiledt

/** Conversion entre la saisie ("1h30", "1h", "40", "40min") et des minutes. */
object Offsets {
    private val HM = Regex("^(\\d{1,2})h(\\d{0,2})$")
    private val MIN = Regex("^(\\d{1,3})(min|mn|m)?$")

    /** null si la saisie est invalide ou hors de 0..600 min. */
    fun parse(input: String): Int? {
        val s = input.trim().lowercase().replace(" ", "")
        val minutes = HM.matchEntire(s)?.let { m ->
            val h = m.groupValues[1].toInt()
            val mn = m.groupValues[2].ifEmpty { "0" }.toInt()
            if (mn >= 60) null else h * 60 + mn
        } ?: MIN.matchEntire(s)?.groupValues?.get(1)?.toInt()
        return minutes?.takeIf { it in 0..600 }
    }

    fun format(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}"
    }
}
