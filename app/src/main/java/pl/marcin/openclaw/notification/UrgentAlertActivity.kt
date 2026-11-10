package pl.marcin.openclaw.notification

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import pl.marcin.openclaw.R

class UrgentAlertActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_urgent_alert)
        findViewById<TextView>(R.id.urgentTitle).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        findViewById<TextView>(R.id.urgentBody).text = intent.getStringExtra(EXTRA_BODY).orEmpty()
        findViewById<Button>(R.id.stopUrgentAlarm).setOnClickListener {
            AlertNotifier.stopAlarm(this)
            finishAndRemoveTask()
        }
    }

    companion object {
        const val EXTRA_TITLE = "urgent.title"
        const val EXTRA_BODY = "urgent.body"
    }
}
