package fr.aityum.reveiledt

import android.content.Context

/** Réglages et état, écrits de façon synchrone (commit) pour ne rien perdre si le process est tué. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("reveil", Context.MODE_PRIVATE)

    private fun str(k: String) = sp.getString(k, null)
    private fun put(k: String, v: String?) { sp.edit().putString(k, v).commit() }
    private fun put(k: String, v: Long) { sp.edit().putLong(k, v).commit() }

    var url: String? get() = str("url"); set(v) = put("url", v)
    var offsetMin: Int get() = sp.getInt("offset", 60); set(v) { sp.edit().putInt("offset", v).commit() }
    var enabled: Boolean get() = sp.getBoolean("enabled", true); set(v) { sp.edit().putBoolean("enabled", v).commit() }

    /** Dernier calendrier téléchargé avec succès, utilisé si le réseau tombe. */
    var cache: String? get() = str("cache"); set(v) = put("cache", v)
    var lastSuccess: Long get() = sp.getLong("lastSuccess", 0); set(v) = put("lastSuccess", v)
    var lastError: String? get() = str("lastError"); set(v) = put("lastError", v)

    var alarmAt: Long get() = sp.getLong("alarmAt", 0); set(v) = put("alarmAt", v)
    var classAt: Long get() = sp.getLong("classAt", 0); set(v) = put("classAt", v)
    var summary: String? get() = str("summary"); set(v) = put("summary", v)
}
