package dev.unifiedcall.flutter_unified_call

import android.app.Activity
import android.content.Intent
import android.os.*
import android.view.*
import android.widget.*

class IncomingCallActivity : Activity() {
    private lateinit var runtime: CallRuntime
    private var callId: String? = null
    private val update: (Map<String, Any?>) -> Unit = { render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        runtime = CallRuntime.get(this); runtime.listeners.add(update); handle(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }
    private fun handle(intent: Intent) {
        callId = intent.getStringExtra("callId")
        if (intent.action == "accepted") {
            callId?.let { accept(it) }
            return
        }
        render()
    }
    private fun accept(id: String) {
        runtime.act(id, "accepted")
        if (runtime.store.get(id)?.getString("state") != "accepted") return
        if (!runtime.isAppForeground) {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                startActivity(it.putExtra("unifiedCallId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        finish()
    }
    private fun render() {
        val id = callId ?: return finish()
        val call = runtime.store.get(id) ?: return finish()
        if (call.getString("state") == "ended") { finish(); return }
        val ringing = call.getString("state") == "ringing"
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(32, 32, 32, 32) }
        layout.addView(TextView(this).apply { text = call.getJSONObject("config").getString("callerName"); textSize = 28f; gravity = Gravity.CENTER })
        layout.addView(TextView(this).apply { text = call.getString("state"); gravity = Gravity.CENTER })
        if (ringing) layout.addView(Button(this).apply {
            text = runtime.store.options().optString("acceptText", "Accept"); setOnClickListener { accept(id) }
        })
        layout.addView(Button(this).apply {
            text = if (ringing) runtime.store.options().optString("declineText", "Decline") else "End call"
            setOnClickListener { runtime.act(id, if (ringing) "declined" else "ended", if (ringing) "declined" else "localEnded"); finish() }
        })
        setContentView(layout)
    }
    override fun onDestroy() { if (::runtime.isInitialized) runtime.listeners.remove(update); super.onDestroy() }
}
