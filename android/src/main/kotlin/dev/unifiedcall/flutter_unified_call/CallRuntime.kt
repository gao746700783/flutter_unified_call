package dev.unifiedcall.flutter_unified_call

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.telecom.*
import org.json.JSONObject

internal class CallRuntime private constructor(val context: Context) {
    val store = CallStore(context)
    val listeners = mutableSetOf<(Map<String, Any?>) -> Unit>()
    val connections = mutableMapOf<String, Connection>()
    val audio = CallAudioBridge(this)
    private var ringer: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restored = false
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val timeouts = mutableMapOf<String, Runnable>()
    val notifications = context.getSystemService(NotificationManager::class.java)
    val account = PhoneAccountHandle(ComponentName(context, UnifiedConnectionService::class.java), "unified_call")
    init { store.onEvent = { event -> listeners.toList().forEach { it(event) } } }
    private fun ringtoneUri(name: String?): Uri {
        if (name == null) return android.provider.Settings.System.DEFAULT_RINGTONE_URI
        require(name.matches(Regex("[a-z][a-z0-9_]*"))) { "Ringtone must be a res/raw resource name" }
        require(context.resources.getIdentifier(name, "raw", context.packageName) != 0) { "Ringtone resource not found" }
        return Uri.parse("android.resource://${context.packageName}/raw/$name")
    }
    private fun ringtoneName(): String? {
        val options = store.options()
        return if (options.isNull("androidRingtoneName")) null else options.getString("androidRingtoneName")
    }
    private fun incomingChannelId(): String = ringtoneName()
        ?.let { "unified_call_incoming_$it" } ?: "unified_call_incoming"
    fun configure(args: Map<*, *>) {
        val name = args["androidRingtoneName"]
        require(name == null || name is String) { "Ringtone name must be a string" }
        ringtoneUri(name as? String) // Validate before persisting any new options.
        store.configure(args)
        initialize()
    }
    fun initialize() {
        // A host upgrade may remove a previously configured bundled resource.
        val storedName = ringtoneName()
        if (storedName != null) {
            try { ringtoneUri(storedName) }
            catch (error: IllegalArgumentException) {
                val options = store.options().toMap().toMutableMap()
                options["androidRingtoneName"] = null
                store.configure(options)
                android.util.Log.w("UnifiedCall", "Stored ringtone unavailable; using system default", error)
                for (call in store.all()) {
                    store.platformEvent(call.getJSONObject("config").getString("callId"), "platformError",
                        mapOf("operation" to "ringtone", "error" to "resource_unavailable"))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(incomingChannelId(), "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming audio and video calls"
                setSound(ringtoneUri(ringtoneName()),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 300, 400)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                enableLights(true)
            })
            notifications.createNotificationChannel(NotificationChannel("unified_call_ongoing", "Ongoing calls", NotificationManager.IMPORTANCE_LOW))
        }
        if (restored) return
        restored = true
        for (call in store.all()) {
            val id = call.getJSONObject("config").getString("callId")
            if (call.getString("state") == "ringing") {
                if (call.getJSONObject("config").getLong("expiresAt") <= System.currentTimeMillis()) act(id, "missed", "missed")
                else { schedule(call); show(call) }
            } else if (call.getString("state") == "accepted" || call.getString("state") == "connected") {
                // A new process cannot recover the external media engine/session.
                act(id, "ended", "failed")
            }
        }
    }
    fun reconcileExpiry() {
        for (call in store.all()) {
            val config = call.getJSONObject("config")
            if (call.getString("state") == "ringing" && config.getLong("expiresAt") <= System.currentTimeMillis()) {
                act(config.getString("callId"), "missed", "missed")
            }
        }
    }
    fun incoming(args: Map<*, *>) {
        val config = JSONObject(args)
        if (!store.incoming(config)) return
        val call = store.get(config.getString("callId"))!!
        schedule(call)
        if (Build.VERSION.SDK_INT >= 26 && store.options().optBoolean("enableTelecom", true)) {
            try {
                val telecom = context.getSystemService(TelecomManager::class.java)
                telecom.registerPhoneAccount(PhoneAccount.builder(account, "App calls")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED).build())
                telecom.addNewIncomingCall(account, Bundle().apply { putString("callId", config.getString("callId")) })
            } catch (error: RuntimeException) {
                store.platformEvent(config.getString("callId"), "platformError", mapOf("available" to false, "error" to error.javaClass.simpleName))
                show(call)
            }
        } else show(call)
    }
    fun showIncomingUi(id: String) { store.get(id)?.let { if (it.getString("state") == "ringing") show(it) } }
    fun act(id: String, action: String, reason: String? = null) {
        // A delayed notification cannot accept an expired call.
        val call = store.get(id) ?: return
        if (action == "accepted" && call.getString("state") == "ringing" &&
            call.getJSONObject("config").getLong("expiresAt") <= System.currentTimeMillis()) {
            act(id, "missed", "missed"); return
        }
        if (!store.transition(id, action, reason)) return
        timeouts.remove(id)?.let(handler::removeCallbacks)
        alarms.cancel(timeoutIntent(id))
        ringer?.stop(); ringer = null
        val connection = connections[id]
        when (action) {
            "accepted" -> { connection?.setActive(); show(call) }
            "connected" -> { connection?.setActive(); show(call) }
            else -> {
                notifications.cancel(id, 1)
                audio.remove(id)
                connections.remove(id)?.apply {
                    setDisconnected(DisconnectCause(if (action == "declined") DisconnectCause.REJECTED else DisconnectCause.LOCAL))
                    destroy()
                }
            }
        }
        updateForegroundService()
    }
    fun ongoingTelecomIds(): List<String> = connections.keys.filter {
        store.get(it)?.optString("state") in listOf("accepted", "connected")
    }
    fun reportForegroundError(error: RuntimeException) {
        for (id in ongoingTelecomIds()) {
            store.platformEvent(id, "platformError", mapOf("operation" to "foregroundService", "error" to error.javaClass.simpleName))
        }
        android.util.Log.e("UnifiedCall", "Foreground call service failed", error)
    }
    fun updateForegroundService() {
        val intent = Intent(context, CallForegroundService::class.java)
        if (ongoingTelecomIds().isEmpty()) { context.stopService(intent); return }
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        } catch (error: RuntimeException) { reportForegroundError(error) }
    }
    private fun timeoutIntent(id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, CallTimeoutReceiver::class.java)
            .setData(Uri.parse("unified-call://timeout/${Uri.encode(id)}")).putExtra("callId", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(call: JSONObject) {
        val config = call.getJSONObject("config")
        val id = config.getString("callId")
        timeouts.remove(id)?.let(handler::removeCallbacks)
        val task = Runnable { act(id, "missed", "missed") }
        timeouts[id] = task
        handler.postDelayed(task, (config.getLong("expiresAt") - System.currentTimeMillis()).coerceAtLeast(0))
        // Inexact by design: no exact-alarm special access is requested.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, config.getLong("expiresAt"), timeoutIntent(id))
    }
    private fun action(id: String, name: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, CallActionReceiver::class.java).setAction(name)
            .setData(Uri.parse("unified-call://action/${Uri.encode(id)}/$name")).putExtra("callId", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun launchIntent(id: String): Intent = Intent(context, IncomingCallActivity::class.java)
        .setData(Uri.parse("unified-call://incoming/${Uri.encode(id)}")).putExtra("callId", id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    private fun show(call: JSONObject) {
        val config = call.getJSONObject("config")
        val id = config.getString("callId")
        val ringing = call.getString("state") == "ringing"
        val open = PendingIntent.getActivity(context, 0, launchIntent(id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, if (ringing) incomingChannelId() else "unified_call_ongoing") else Notification.Builder(context)
        builder.setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle(config.getString("callerName"))
            .setContentText(if (ringing) "Incoming ${config.optString("mediaType", "audio")} call" else "Call in progress")
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(!ringing)
            .setContentIntent(open).setVisibility(if (ringing) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
        val decline = action(id, if (ringing) "declined" else "ended")
        val accept = PendingIntent.getActivity(context, 0, launchIntent(id).setAction("accepted"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val useCallStyle = Build.VERSION.SDK_INT >= 31 && ringing &&
            (Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent())
        if (useCallStyle) {
            val person = Person.Builder().setName(config.getString("callerName")).build()
            builder.setStyle(if (ringing) Notification.CallStyle.forIncomingCall(person, decline, accept)
                else Notification.CallStyle.forOngoingCall(person, decline))
        } else {
            builder.addAction(Notification.Action.Builder(null, store.options().optString("declineText", "Decline"), decline).build())
            if (ringing) builder.addAction(Notification.Action.Builder(null, store.options().optString("acceptText", "Accept"), accept).build())
            @Suppress("DEPRECATION")
            builder.setPriority(Notification.PRIORITY_MAX)
        }
        if (ringing) {
            @Suppress("DEPRECATION")
            builder.setPriority(Notification.PRIORITY_MAX)
            builder.setFullScreenIntent(open, true)
            if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter((config.getLong("expiresAt") - System.currentTimeMillis()).coerceAtLeast(1))
        }
        if (Build.VERSION.SDK_INT < 26 && ringing) {
            builder.setSound(ringtoneUri(ringtoneName()))
            builder.setVibrate(longArrayOf(0, 400, 300, 400))
        }
        val notification = builder.build()
        if (ringing) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        val degraded = if (useCallStyle) postCallStyleWithFallback(notification, {
            builder.setStyle(null)
            builder.setFullScreenIntent(null, false)
            val declineAction = Notification.Action.Builder(null, store.options().optString("declineText", "Decline"), decline).build()
            val acceptAction = Notification.Action.Builder(null, store.options().optString("acceptText", "Accept"), accept).build()
            builder.setActions(declineAction, acceptAction)
            builder.build().apply { flags = flags or Notification.FLAG_INSISTENT }
        }) { notifications.notify(id, 1, it) }
        else { notifications.notify(id, 1, notification); false }
        if (degraded) store.platformEvent(id, "platformError", mapOf(
            "operation" to "notification", "error" to "call_style_rejected", "fallback" to "generic"))
        if (ringing) {
            try {
                if (ringer == null) {
                    val r = RingtoneManager.getRingtone(context, ringtoneUri(ringtoneName()))
                    if (Build.VERSION.SDK_INT >= 28) {
                        r?.isLooping = true
                    }
                    r?.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    r?.play()
                    ringer = r
                }
            } catch (e: Exception) {
                android.util.Log.w("UnifiedCall", "In-app ringtone playback failed", e)
            }
        } else {
            ringer?.stop()
            ringer = null
        }
    }
    companion object {
        @Volatile private var instance: CallRuntime? = null
        fun get(context: Context): CallRuntime = instance ?: synchronized(this) {
            instance ?: CallRuntime(context.applicationContext).also { instance = it; it.initialize() }
        }
    }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("callId") ?: return
        val action = intent.action ?: return
        CallRuntime.get(context).act(id, action, if (action == "declined") "declined" else "localEnded")
    }
}

/** System delivery can start the process without a Flutter engine. */
class CallTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("callId") ?: return
        try { CallRuntime.get(context).reconcileExpiry() }
        catch (error: Exception) { android.util.Log.e("UnifiedCall", "Timeout recovery failed for call", error) }
    }
}

/** Restrict fallback to the platform's explicit CallStyle policy rejection. */
internal fun postCallStyleWithFallback(notification: Notification, fallback: () -> Notification,
                                       post: (Notification) -> Unit): Boolean {
    try { post(notification); return false }
    catch (error: RuntimeException) {
        if (error.message?.contains("CallStyle notifications must") != true) throw error
        post(fallback())
        return true
    }
}
