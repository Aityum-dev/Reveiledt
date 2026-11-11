package fr.aityum.reveiledt

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Écran plein écran affiché par-dessus l'écran de verrouillage pendant la sonnerie. */
class AlarmActivity : Activity() {
    private val done = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        findViewById<TextView>(R.id.ringTime).text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        findViewById<TextView>(R.id.ringInfo).text = intent.getStringExtra(EXTRA_INFO) ?: ""
        findViewById<Button>(R.id.btnStop).setOnClickListener { send(AlarmService.ACTION_STOP) }
        findViewById<Button>(R.id.btnSnooze).apply {
            text = "Snooze ${AlarmService.SNOOZE_MIN} min"
            setOnClickListener { send(AlarmService.ACTION_SNOOZE) }
        }
        ContextCompat.registerReceiver(
            this, done, IntentFilter(AlarmService.ACTION_DONE), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun send(action: String) {
        startService(Intent(this, AlarmService::class.java).setAction(action))
        finish()
    }

    override fun onDestroy() {
        unregisterReceiver(done)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INFO = "info"
    }
}
