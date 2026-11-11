package fr.aityum.reveiledt

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var offset: EditText
    private lateinit var enabled: Switch
    private lateinit var save: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        url = findViewById(R.id.url)
        offset = findViewById(R.id.offset)
        enabled = findViewById(R.id.enabled)
        save = findViewById(R.id.save)

        val p = Prefs(this)
        url.setText(p.url.orEmpty())
        offset.setText(p.offsetMin.toString())
        enabled.isChecked = p.enabled

        save.setOnClickListener { saveAndSync() }
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
        Thread {
            Engine.recompute(applicationContext) // reprend les permissions accordées entre-temps
            runOnUiThread { refresh() }
        }.start()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        StatusNotif.show(this)
        refresh()
    }

    private fun saveAndSync() {
        val link = url.text.toString().trim()
        val minutes = offset.text.toString().toIntOrNull()
        if (!Regex("^(https?|webcal)://.+", RegexOption.IGNORE_CASE).matches(link)) return toast("Lien iCal invalide")
        if (minutes == null || minutes !in 0..600) return toast("Minutes entre 0 et 600")

        val p = Prefs(this)
        p.url = link
        p.offsetMin = minutes
        p.enabled = enabled.isChecked
        save.isEnabled = false
        save.text = "Synchronisation…"
        Thread {
            val ok = Engine.sync(applicationContext)
            runOnUiThread {
                save.isEnabled = true
                save.text = "Enregistrer et synchroniser"
                toast(if (ok) "Emploi du temps synchronisé" else "Erreur : ${p.lastError}")
                refresh()
            }
        }.start()
    }

    private fun refresh() {
        val s = StatusNotif.state(this)
        findViewById<LinearLayout>(R.id.statusCard).background = GradientDrawable().apply {
            cornerRadius = 18 * resources.displayMetrics.density
            setColor(s.level.color)
        }
        findViewById<TextView>(R.id.statusTitle).text = s.title
        findViewById<TextView>(R.id.statusText).text = s.text

        renderChecks()

        val plans = Engine.preview(this).take(7)
        findViewById<TextView>(R.id.preview).text =
            if (plans.isEmpty()) "Aucun cours à venir dans le calendrier."
            else plans.joinToString("\n") {
                val ms = it.alarm.toInstant().toEpochMilli()
                "${StatusNotif.day(ms).replaceFirstChar { c -> c.uppercase() }} · réveil ${StatusNotif.hm(ms)} · " +
                    "cours ${StatusNotif.hm(it.firstClass.toInstant().toEpochMilli())} ${it.summary}"
            }
    }

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
                background = GradientDrawable().apply {
                    cornerRadius = 12 * resources.displayMetrics.density
                    setColor(0xFFF5B14C.toInt())
                }
                setOnClickListener { runCatching { fix() }.onFailure { toast("Ouvre les réglages de l'app manuellement") } }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
            })
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
