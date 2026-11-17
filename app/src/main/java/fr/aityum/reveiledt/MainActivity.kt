package fr.aityum.reveiledt

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var save: Button
    private lateinit var newOffset: EditText

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        url = findViewById(R.id.url)
        save = findViewById(R.id.save)
        newOffset = findViewById(R.id.newOffset)

        val p = Prefs(this)
        url.setText(p.url.orEmpty())
        bindSwitch(R.id.enabled, p.enabled) { p.enabled = it; inBackground { Engine.recompute(it) } }
        bindSwitch(R.id.persistent, p.persistent) { p.persistent = it; StatusNotif.show(this) }
        bindSwitch(R.id.dailyNotif, p.dailyNotif) { p.dailyNotif = it }

        save.setOnClickListener { saveAndSync() }
        findViewById<Button>(R.id.addOffset).setOnClickListener { addOffset() }
        newOffset.setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) addOffset() }
        }
        findViewById<Button>(R.id.test).setOnClickListener {
            if (!AlarmScheduler.canExact(this)) return@setOnClickListener toast("Autorise d'abord les alarmes")
            AlarmScheduler.test(this)
            toast("Sonnerie dans 10 secondes, verrouille ton téléphone pour tester")
        }

        Engine.ensureSchedules(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        inBackground { Engine.recompute(it) } // reprend les permissions accordées entre-temps
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        StatusNotif.show(this)
        refresh()
    }

    /** Lance [work] hors du thread UI puis rafraîchit l'écran. */
    private fun inBackground(work: (android.content.Context) -> Unit) {
        val app = applicationContext
        Thread {
            work(app)
            runOnUiThread { refresh() }
        }.start()
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> onChange(checked); refresh() }
        }
    }

    private fun addOffset() {
        val minutes = Offsets.parse(newOffset.text.toString())
            ?: return toast("Format : 1h30, 1h ou 40 (max 10h)")
        val current = Prefs(this).offsets
        if (minutes in current) return toast("Ce réveil existe déjà")
        if (current.size >= AlarmScheduler.MAX_ALARMS) return toast("${AlarmScheduler.MAX_ALARMS} réveils maximum")
        newOffset.setText("")
        inBackground { Engine.setOffsets(it, current + minutes) }
    }

    private fun removeOffset(minutes: Int) {
        val remaining = Prefs(this).offsets - minutes
        inBackground { Engine.setOffsets(it, remaining) }
    }

    private fun saveAndSync() {
        val link = url.text.toString().trim()
        if (!Regex("^(https?|webcal)://.+", RegexOption.IGNORE_CASE).matches(link)) return toast("Lien iCal invalide")
        val p = Prefs(this)
        p.url = link
        save.isEnabled = false
        save.text = "Synchronisation…"
        val app = applicationContext
        Thread {
            val ok = Engine.sync(app)
            runOnUiThread {
                save.isEnabled = true
                save.text = "Enregistrer le lien et synchroniser"
                toast(if (ok) "Emploi du temps synchronisé" else "Erreur : ${p.lastError}")
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        val s = StatusNotif.state(this)
        findViewById<LinearLayout>(R.id.statusCard).background = rounded(s.level.color, 18)
        findViewById<TextView>(R.id.statusTitle).text = s.title
        findViewById<TextView>(R.id.statusText).text = s.text
        renderChecks()
        renderAlarms()
        renderWeek()
    }

    // ---------- Mes réveils ----------

    private fun renderAlarms() {
        val box = findViewById<LinearLayout>(R.id.alarms)
        box.removeAllViews()
        val offsets = Prefs(this).offsets
        if (offsets.isEmpty()) {
            box.addView(text("Aucun réveil, ajoute-en au moins un", 14, 0xFFFF8A8A.toInt()).apply {
                setPadding(0, 8.dp, 0, 0)
            })
        }
        for (m in offsets) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(0xFF171A30.toInt(), 12)
                setPadding(16.dp, 4.dp, 4.dp, 4.dp)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp }
            }
            row.addView(text("⏰  ${Offsets.format(m)} avant le 1er cours", 16, 0xFFE8E9F5.toInt()).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply {
                text = "✕"
                textSize = 16f
                setTextColor(0xFFFF8A8A.toInt())
                background = null
                setOnClickListener { removeOffset(m) }
            })
            box.addView(row)
        }
    }

    // ---------- Cette semaine ----------

    private fun renderWeek() {
        val box = findViewById<LinearLayout>(R.id.week)
        box.removeAllViews()
        val days = Engine.week(this)
        if (days.isEmpty()) {
            box.addView(text("Synchronise ton emploi du temps pour voir la semaine", 14, MUTED).apply {
                setPadding(0, 10.dp, 0, 0)
            })
            return
        }
        val today = LocalDate.now()
        for (d in days) box.addView(dayCard(d, d.date == today))
    }

    private fun dayCard(d: DayView, isToday: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFF171A30.toInt(), 16).apply {
                if (isToday) setStroke(2.dp, ACCENT)
            }
            setPadding(16.dp, 12.dp, 12.dp, 14.dp)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 10.dp }
            if (d.skipped) alpha = 0.6f
        }

        // en-tête : jour + bouton sonne / coupé
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val prefix = when (d.date) {
            LocalDate.now() -> "Aujourd'hui · "
            LocalDate.now().plusDays(1) -> "Demain · "
            else -> ""
        }
        header.addView(text(prefix + d.date.format(dayFmt).replaceFirstChar { it.uppercase() }, 16, 0xFFE8E9F5.toInt()).apply {
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        if (d.events.isNotEmpty()) {
            header.addView(text(if (d.skipped) "🔕 Coupé" else "🔔 Sonne", 13, 0xFFFFFFFF.toInt()).apply {
                background = rounded(if (d.skipped) 0xFFD93A3A.toInt() else 0xFF2E9E5B.toInt(), 20)
                setPadding(12.dp, 6.dp, 12.dp, 6.dp)
                setOnClickListener {
                    isEnabled = false
                    inBackground { ctx -> Engine.toggleSkip(ctx, d.date) }
                }
            })
        }
        card.addView(header)

        if (d.events.isEmpty()) {
            card.addView(text("Pas cours", 14, MUTED).apply { setPadding(0, 6.dp, 0, 0) })
            return card
        }

        // heures de réveil
        val alarms = if (d.skipped) "Aucun réveil ce jour" else "⏰ " + d.alarms.joinToString("  ·  ") { StatusNotif.hm(it) }
        card.addView(text(alarms, 16, ACCENT).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 8.dp, 0, 6.dp)
            if (d.skipped) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        })

        // tous les cours du jour
        for (e in d.events) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6.dp, 0, 0)
            }
            val time = StatusNotif.hm(e.start) + (e.end?.let { "\n" + StatusNotif.hm(it) } ?: "")
            row.addView(text(time, 13, 0xFFC9CCE6.toInt()).apply {
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(60.dp, WRAP_CONTENT)
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            info.addView(text(e.summary.ifBlank { "(sans titre)" }, 15, 0xFFE8E9F5.toInt()))
            if (e.location.isNotBlank()) info.addView(text(e.location, 12, MUTED))
            row.addView(info)
            card.addView(row)
        }
        return card
    }

    // ---------- Vérifications ----------

    /** Tout ce qui peut empêcher le réveil de sonner, avec un bouton pour corriger. */
    private fun renderChecks() {
        val box = findViewById<LinearLayout>(R.id.checks)
        box.removeAllViews()
        val pkg = Uri.parse("package:$packageName")
        val issues = mutableListOf<Pair<String, () -> Unit>>()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            issues += "Autoriser les notifications" to {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        if (Build.VERSION.SDK_INT >= 31 && !AlarmScheduler.canExact(this)) {
            issues += "Autoriser les alarmes et rappels" to {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            }
        }
        if (Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            issues += "Autoriser l'écran de réveil plein écran" to {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            }
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            issues += "Désactiver l'optimisation de batterie" to {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }
        }
        if (getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            issues += "Volume des alarmes à 0, le monter" to { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
        }

        for ((label, fix) in issues) {
            box.addView(Button(this).apply {
                text = "⚠  $label"
                isAllCaps = false
                setTextColor(0xFF1A1205.toInt())
                background = rounded(ACCENT, 12)
                setOnClickListener { runCatching { fix() }.onFailure { toast("Ouvre les réglages de l'app manuellement") } }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 8.dp }
            })
        }
    }

    // ---------- helpers ----------

    private fun text(s: String, sp: Int, color: Int) = TextView(this).apply {
        text = s
        textSize = sp.toFloat()
        setTextColor(color)
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = radiusDp * resources.displayMetrics.density
        setColor(color)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private val ACCENT = 0xFFF5B14C.toInt()
        private val MUTED = 0xFF8A8FB3.toInt()
    }
}
