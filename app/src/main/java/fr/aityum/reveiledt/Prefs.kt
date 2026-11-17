package fr.aityum.reveiledt

import android.content.Context
import java.time.LocalDate

/** Réglages et état, écrits de façon synchrone (commit) pour ne rien perdre si le process est tué. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("reveil", Context.MODE_PRIVATE)

    private fun str(k: String) = sp.getString(k, null)
    private fun put(k: String, v: String?) { sp.edit().putString(k, v).commit() }
    private fun put(k: String, v: Long) { sp.edit().putLong(k, v).commit() }
    private fun put(k: String, v: Boolean) { sp.edit().putBoolean(k, v).commit() }
    private fun put(k: String, v: Int) { sp.edit().putInt(k, v).commit() }

    var url: String? get() = str("url"); set(v) = put("url", v)
    var enabled: Boolean get() = sp.getBoolean("enabled", true); set(v) = put("enabled", v)
    var persistent: Boolean get() = sp.getBoolean("persistent", true); set(v) = put("persistent", v)
    var dailyNotif: Boolean get() = sp.getBoolean("dailyNotif", true); set(v) = put("dailyNotif", v)

    /** Minutes avant le 1er cours, un réveil par valeur. Ancien réglage unique repris s'il existe. */
    var offsets: List<Int>
        get() = str("offsets")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?: listOf(sp.getInt("offset", 60))
        set(v) = put("offsets", v.distinct().sortedDescending().joinToString(","))

    /** Jours où le réveil ne doit pas sonner (malade, grève...). */
    var skipped: Set<LocalDate>
        get() = str("skipped")?.split(',')?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }?.toSet()
            ?: emptySet()
        set(v) = put("skipped", v.sorted().joinToString(","))

    /** Dernier calendrier téléchargé avec succès, utilisé si le réseau tombe. */
    var cache: String? get() = str("cache"); set(v) = put("cache", v)
    var lastSuccess: Long get() = sp.getLong("lastSuccess", 0); set(v) = put("lastSuccess", v)
    var lastError: String? get() = str("lastError"); set(v) = put("lastError", v)

    /** Prochain réveil (le plus tôt) et nombre de réveils pour ce même jour de cours. */
    var alarmAt: Long get() = sp.getLong("alarmAt", 0); set(v) = put("alarmAt", v)
    var classAt: Long get() = sp.getLong("classAt", 0); set(v) = put("classAt", v)
    var summary: String? get() = str("summary"); set(v) = put("summary", v)
    var alarmCount: Int get() = sp.getInt("alarmCount", 0); set(v) = put("alarmCount", v)
}
