package dev.unifiedcall.flutter_unified_call

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioManager
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
    private var foregroundCallId: String? = null
    private var ringer: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restored = false
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val timeouts = mutableMapOf<String, Runnable>()
    val notifications = context.getSystemService(NotificationManager::class.java)
    val account = PhoneAccountHandle(ComponentName(context, UnifiedConnectionService::class.java), "unified_call")
    var isAppForeground = false
        private set
    private var foregroundActivityCount = 0
    init {
        store.onEvent = { event -> listeners.toList().forEach { it(event) } }
        val app = context.applicationContext as? Application
        app?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity !is IncomingCallActivity) {
                    foregroundActivityCount++
                    isAppForeground = foregroundActivityCount > 0
                    refreshForegroundIncomingUi()
                }
            }
            override fun onActivityStopped(activity: Activity) {
                if (activity !is IncomingCallActivity) {
                    foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                    isAppForeground = foregroundActivityCount > 0
                    refreshForegroundIncomingUi()
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
    private fun ringtoneUri(name: String?): Uri {
        if (name == null) {
            return RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: android.provider.Settings.System.DEFAULT_RINGTONE_URI
        }
        require(name.matches(Regex("[a-z][a-z0-9_]*"))) { "Ringtone must be a res/raw resource name" }
        require(context.resources.getIdentifier(name, "raw", context.packageName) != 0) { "Ringtone resource not found" }
        return Uri.parse("android.resource://${context.packageName}/raw/$name")
    }
    private fun ringtoneName(): String? {
        val options = store.options()
        return if (options.isNull("androidRingtoneName")) null else options.getString("androidRingtoneName")
    }
    private fun incomingChannelId(): String = ringtoneName()
        ?.let { "unified_call_incoming_${it}_v2" } ?: "unified_call_incoming_v2"
    fun text(key: String): String = callText(context, store.options(), key)

    fun configure(args: Map<*, *>) {
        val name = args["androidRingtoneName"]
        require(name == null || name is String) { "Ringtone name must be a string" }
        ringtoneUri(name as? String) // Validate before persisting any new options.
        store.configure(args)
        initialize()
        refreshForegroundIncomingUi()
    }
    private fun refreshForegroundIncomingUi() {
        if (!store.options().optBoolean("androidForegroundBanner", false)) return
        for (call in store.all()) {
            if (call.optString("state") == "ringing") {
                val id = call.getJSONObject("config").getString("callId")
                if (isAppForeground) notifications.cancel(id, 1) else show(call)
            }
        }
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
            try {
                notifications.deleteNotificationChannel("unified_call_incoming")
                ringtoneName()?.let { notifications.deleteNotificationChannel("unified_call_incoming_$it") }
            } catch (_: Exception) {}
            notifications.createNotificationChannel(NotificationChannel(incomingChannelId(), text("incomingChannelName"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = text("incomingChannelDescription")
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 300, 400)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                enableLights(true)
            })
            notifications.createNotificationChannel(NotificationChannel("unified_call_ongoing", text("ongoingChannelName"), NotificationManager.IMPORTANCE_LOW))
        }
        if (restored) return
        restored = true
        for (call in store.all()) {
            val id = call.getJSONObject("config").getString("callId")
            if (call.getString("state") == "ringing") {
                if (call.getJSONObject("config").getLong("expiresAt") <= System.currentTimeMillis()) act(id, "missed", "missed")
                else { schedule(call); show(call) }
            } else if (call.getString("state") in listOf("dialing", "accepted", "connected")) {
                // A new process cannot recover the external media engine/session.
                act(id, "ended", "failed")
            }
        }
    }
    fun reconcileExpiry() {
        for (call in store.all()) {
            val config = call.getJSONObject("config")
            val state = call.getString("state")
            if ((state == "ringing" || (config.isOutgoingCall() && state in listOf("dialing", "accepted"))) &&
                config.getLong("expiresAt") <= System.currentTimeMillis()) expire(call)
        }
    }
    fun incoming(args: Map<*, *>) {
        val config = JSONObject(args)
        if (!store.incoming(config)) {
            val existing = store.get(config.getString("callId"))
            if (existing != null && existing.getString("state") != "ended") {
                show(existing)
                updateForegroundService()
            }
            return
        }
        val call = store.get(config.getString("callId"))!!
        schedule(call)
        if (!config.isMeetingCall() && Build.VERSION.SDK_INT >= 26 && store.options().optBoolean("enableTelecom", true)) {
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
    fun outgoing(args: Map<*, *>) {
        val config = JSONObject(args).put("direction", "outgoing")
        if (!store.incoming(config, outgoing = true)) return
        val call = store.get(config.getString("callId"))!!
        schedule(call)
        show(call)
        if (!config.isMeetingCall() && Build.VERSION.SDK_INT >= 26 && store.options().optBoolean("enableTelecom", true)) {
            try {
                val telecom = context.getSystemService(TelecomManager::class.java)
                telecom.registerPhoneAccount(PhoneAccount.builder(account, "App calls")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED).build())
                telecom.placeCall(Uri.fromParts("tel", config.getString("callId"), null), Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account)
                    putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString("callId", config.getString("callId")) })
                })
            } catch (error: RuntimeException) {
                store.platformEvent(config.getString("callId"), "platformError",
                    mapOf("operation" to "telecom", "error" to error.javaClass.simpleName, "fallback" to "notification"))
            }
        }
    }
    private fun expire(call: JSONObject) {
        val config = call.getJSONObject("config")
        val id = config.getString("callId")
        if (call.getString("state") !in listOf("ringing", "dialing", "accepted")) return
        if (config.isOutgoingCall()) act(id, "ended", if (config.isMeetingCall() || call.getString("state") == "accepted") "failed" else "unanswered")
        else act(id, "missed", "missed")
    }

    fun updateCall(id: String, callerName: String?, callerHandle: String?, extra: JSONObject?) {
        val call = store.update(id, callerName, callerHandle, extra) ?: return
        show(call)
        updateForegroundService()
    }
    fun showOutgoingUi(id: String) {
        store.get(id)?.let { if (it.getJSONObject("config").isOutgoingCall() && it.getString("state") != "ended") show(it) }
    }
    fun showIncomingUi(id: String) { store.get(id)?.let { if (it.getString("state") == "ringing") show(it) } }
    fun act(id: String, action: String, reason: String? = null) {
        // A delayed notification cannot accept an expired call.
        val call = store.get(id) ?: return
        if (action == "accepted" && call.getString("state") == "ringing" &&
            call.getJSONObject("config").getLong("expiresAt") <= System.currentTimeMillis()) {
            act(id, "missed", "missed"); return
        }
        val config = call.getJSONObject("config")
        if (action in listOf("connecting", "connected") && config.isOutgoingCall() &&
            call.getString("state") in listOf("dialing", "accepted") &&
            config.getLong("expiresAt") <= System.currentTimeMillis()) {
            expire(call); return
        }
        if (!store.transition(id, action, reason)) return
        if (action != "connecting") {
            timeouts.remove(id)?.let(handler::removeCallbacks)
            alarms.cancel(timeoutIntent(id))
        }
        if (store.all().none { it.optString("state") == "ringing" }) {
            ringer?.stop(); ringer = null
        }
        val connection = connections[id]
        when (action) {
            "accepted" -> { connection?.setAudioModeIsVoip(true); connection?.setActive(); show(call) }
            "connecting" -> show(call)
            "connected" -> { connection?.setAudioModeIsVoip(true); connection?.setActive(); show(call) }
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
        store.get(it)?.optString("state") in listOf("dialing", "accepted", "connected")
    }
    fun foregroundStarted(id: String?) {
        foregroundCallId = id
        for (callId in ongoingTelecomIds()) store.get(callId)?.let(::show)
    }
    fun foregroundStopped() {
        foregroundCallId = null
        for (id in ongoingTelecomIds()) store.get(id)?.let(::show)
    }
    fun reportForegroundError(error: RuntimeException) {
        foregroundStopped()
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
        val task = Runnable { store.get(id)?.let(::expire) }
        timeouts[id] = task
        handler.postDelayed(task, (config.getLong("expiresAt") - System.currentTimeMillis()).coerceAtLeast(0))
        // Inexact by design: no exact-alarm special access is requested.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, config.getLong("expiresAt"), timeoutIntent(id))
    }
    private fun action(id: String, name: String): PendingIntent {
        if (name == "accepted") {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launch != null) {
                launch.putExtra("unifiedCallAction", "accepted")
                    .putExtra("callId", id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                return PendingIntent.getActivity(context, id.hashCode(), launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            }
            val intent = Intent(context, IncomingCallActivity::class.java).setAction(name)
                .setData(Uri.parse("unified-call://action/${Uri.encode(id)}/$name")).putExtra("callId", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return PendingIntent.getBroadcast(context, 0,
            Intent(context, CallActionReceiver::class.java).setAction(name)
                .setData(Uri.parse("unified-call://action/${Uri.encode(id)}/$name")).putExtra("callId", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun launchIntent(id: String): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            ?: Intent(context, IncomingCallActivity::class.java)
                .setData(Uri.parse("unified-call://incoming/${Uri.encode(id)}"))
                .putExtra("callId", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun fullScreenIntent(id: String): PendingIntent {
        val intent = Intent(context, IncomingCallActivity::class.java)
            .setAction("incoming")
            .setData(Uri.parse("unified-call://fullscreen/${Uri.encode(id)}"))
            .putExtra("callId", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun isMeeting(config: JSONObject?): Boolean = config?.isMeetingCall() == true

    private fun notificationTitle(config: JSONObject?, state: String): String {
        val meetingName = (config?.optJSONObject("extra")?.opt("meetingName") as? String).orEmpty()
        if (state != "ringing" && isMeeting(config) && meetingName.isNotBlank()) {
            return text("meetingNamePrefix") + meetingName
        }
        return config?.optString("callerName").orEmpty().ifBlank {
            text(if (isMeeting(config)) "meetingText" else "ongoingText")
        }
    }

    private fun ongoingSubtitle(config: JSONObject?, state: String): String {
        if (isMeeting(config)) {
            val status = text(if (state == "accepted") "joiningMeetingText" else "meetingText")
            val number = (config?.optJSONObject("extra")?.opt("meetingNumber") as? String).orEmpty()
            return if (number.isNotBlank()) "$status · $number" else status
        }
        if (config?.isOutgoingCall() == true) {
            return text(when (state) { "dialing" -> "outgoingText"; "accepted" -> "connectingText"; else -> "ongoingText" })
        }
        return (config?.opt("callerHandle") as? String).orEmpty().ifBlank { text("ongoingText") }
    }
    private fun endActionText(config: JSONObject, state: String): String =
        text(if (config.isOutgoingCall() && state == "dialing") "cancelCallText" else "hangupText")

    fun foregroundNotification(): Notification {
        val id = ongoingTelecomIds().firstOrNull()
        if (id == null) {
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, "unified_call_ongoing") else Notification.Builder(context)
            return builder.setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(text("ongoingText"))
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).build()
        }
        val config = store.get(id)?.optJSONObject("config")
        val state = store.get(id)?.optString("state").orEmpty()
        val ongoingText = ongoingSubtitle(config, state)
        val callerName = notificationTitle(config, state)
        val end = action(id, "ended")
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, "unified_call_ongoing")
        } else {
            Notification.Builder(context)
        }
        builder.setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(callerName)
            .setContentText(ongoingText)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(PendingIntent.getActivity(
                context,
                0,
                launchIntent(id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
        val hangupText = endActionText(config ?: JSONObject(), state)
        if (!isMeeting(config) && state != "dialing" && Build.VERSION.SDK_INT >= 31 && !store.options().optBoolean("customCallActions")) {
            val person = Person.Builder().setName(callerName).build()
            builder.setStyle(Notification.CallStyle.forOngoingCall(person, end))
        } else if (!isMeeting(config)) {
            builder.addAction(Notification.Action.Builder(null, hangupText, end).build())
        }
        return builder.build()
    }
    private fun show(call: JSONObject) {
        val config = call.getJSONObject("config")
        val id = config.getString("callId")
        val ringing = call.getString("state") == "ringing"
        val isMeeting = isMeeting(config)
        val isOutgoing = config.isOutgoingCall()
        val handle = (config.opt("callerHandle") as? String).orEmpty()
        val defaultRinging = if (isOutgoing) text("outgoingText")
            else if (isMeeting) text("meetingInvitationText")
            else if (config.optString("mediaType") == "video") text("videoSubtitle")
            else text("audioSubtitle")
        val contentText = if (ringing) {
            if (handle.isNotBlank()) handle else defaultRinging
        } else {
            ongoingSubtitle(config, call.getString("state"))
        }
        val callerName = notificationTitle(config, call.getString("state"))
        val open = PendingIntent.getActivity(context, 0, launchIntent(id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, if (ringing && !isOutgoing) incomingChannelId() else "unified_call_ongoing") else Notification.Builder(context)
        builder.setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle(callerName)
            .setContentText(contentText)
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(!ringing)
            .setContentIntent(open).setVisibility(if (ringing && !isOutgoing) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
        val decline = action(id, if (ringing && !isOutgoing) "declined" else "ended")
        val accept = action(id, "accepted")
        val useCallStyle = !isMeeting && call.getString("state") != "dialing" &&
            Build.VERSION.SDK_INT >= 31 && !store.options().optBoolean("customCallActions") &&
            ((ringing && !isOutgoing && (Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent())) || (!ringing) || (ringing && isOutgoing))
        if (useCallStyle) {
            val person = Person.Builder().setName(callerName).build()
            builder.setStyle(if (ringing && !isOutgoing) Notification.CallStyle.forIncomingCall(person, decline, accept)
                else Notification.CallStyle.forOngoingCall(person, decline))
        } else {
            val declineText = if (ringing && !isOutgoing) text("declineText") else endActionText(config, call.getString("state"))
            val acceptText = text(if (isMeeting) "joinMeetingText" else "acceptText")
            if (ringing || !isMeeting) builder.addAction(Notification.Action.Builder(null, declineText, decline).build())
            if (ringing && !isOutgoing) builder.addAction(Notification.Action.Builder(null, acceptText, accept).build())
            @Suppress("DEPRECATION")
            builder.setPriority(if (ringing && !isOutgoing) Notification.PRIORITY_MAX else Notification.PRIORITY_LOW)
        }
        if (ringing && !isOutgoing) {
            @Suppress("DEPRECATION")
            builder.setPriority(Notification.PRIORITY_MAX)
            builder.setFullScreenIntent(fullScreenIntent(id), true)
            if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter((config.getLong("expiresAt") - System.currentTimeMillis()).coerceAtLeast(1))
        }
        if (Build.VERSION.SDK_INT < 26 && ringing && !isOutgoing) {
            builder.setSound(ringtoneUri(ringtoneName()))
            builder.setVibrate(longArrayOf(0, 400, 300, 400))
        }
        val notification = builder.build()
        if (ringing && !isOutgoing) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        val suppressed = (ringing && !isOutgoing && isAppForeground &&
            store.options().optBoolean("androidForegroundBanner", false)) ||
            (!ringing && foregroundCallId == id && ongoingTelecomIds() == listOf(id))
        val degraded = if (suppressed) {
            notifications.cancel(id, 1)
            false
        } else if (useCallStyle) postCallStyleWithFallback(notification, {
            builder.setStyle(null)
            val declineAction = Notification.Action.Builder(null, text(if (ringing && !isOutgoing) "declineText" else "hangupText"), decline).build()
            val acceptAction = Notification.Action.Builder(null, text("acceptText"), accept).build()
            if (ringing && !isOutgoing) builder.setActions(declineAction, acceptAction)
            else builder.setActions(declineAction)
            builder.build().apply { if (ringing && !isOutgoing) flags = flags or Notification.FLAG_INSISTENT }
        }) { notifications.notify(id, 1, it) }
        else { notifications.notify(id, 1, notification); false }
        if (degraded) store.platformEvent(id, "platformError", mapOf(
            "operation" to "notification", "error" to "call_style_rejected", "fallback" to "generic"))
        if (ringing && !isOutgoing) {
            try {
                if (ringer == null) {
                    val r = RingtoneManager.getRingtone(context, ringtoneUri(ringtoneName()))
                    if (Build.VERSION.SDK_INT >= 28) {
                        r?.isLooping = true
                    }
                    r?.audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setLegacyStreamType(AudioManager.STREAM_RING)
                        .build()
                    r?.play()
                    ringer = r
                }
            } catch (e: Exception) {
                android.util.Log.w("UnifiedCall", "In-app ringtone playback failed", e)
            }
        } else if (store.all().none { it.optString("state") == "ringing" }) {
            ringer?.stop()
            ringer = null
        }
    }
    fun silenceRinger() {
        ringer?.stop()
        ringer = null
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
        val runtime = CallRuntime.get(context)
        val call = runtime.store.get(id)
        val cancelling = action == "ended" && call?.getJSONObject("config")?.isOutgoingCall() == true && call.optString("state") != "connected"
        runtime.act(id, action, if (action == "declined") "declined" else if (cancelling) "cancelled" else "localEnded")
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
