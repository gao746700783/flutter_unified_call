package dev.unifiedcall.flutter_unified_call

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.*
import io.flutter.plugin.common.*
import org.json.JSONObject

class FlutterUnifiedCallPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler,
    ActivityAware, PluginRegistry.RequestPermissionsResultListener, PluginRegistry.NewIntentListener {
    private lateinit var channel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var runtime: CallRuntime
    private var binding: ActivityPluginBinding? = null
    private var sink: EventChannel.EventSink? = null
    private var permissionResult: MethodChannel.Result? = null
    private val listener: (Map<String, Any?>) -> Unit = { sink?.success(it) }
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        runtime = CallRuntime.get(binding.applicationContext)
        channel = MethodChannel(binding.binaryMessenger, "flutter_unified_call")
        eventChannel = EventChannel(binding.binaryMessenger, "flutter_unified_call/events")
        channel.setMethodCallHandler(this); eventChannel.setStreamHandler(this)
    }
    override fun onListen(arguments: Any?, events: EventChannel.EventSink) { sink = events; runtime.listeners.add(listener) }
    override fun onCancel(arguments: Any?) { sink = null; runtime.listeners.remove(listener) }
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
            val id = args["callId"] as? String
            when (call.method) {
                "initialize" -> { runtime.configure(args); result.success(null) }
                "showIncomingCall" -> { runtime.incoming(args); result.success(null) }
                "startOutgoingCall" -> { runtime.outgoing(args); result.success(null) }
                "updateCall" -> {
                    require(!id.isNullOrBlank()) { "Missing callId" }
                    val callerName = args["callerName"] as? String
                    val callerHandle = args["callerHandle"] as? String
                    val extra = (args["extra"] as? Map<*, *>)?.let { JSONObject(it) }
                    runtime.updateCall(id, callerName, callerHandle, extra)
                    result.success(null)
                }
                "getPendingEvents" -> { runtime.reconcileExpiry(); result.success(runtime.store.pending()) }
                "getActiveCalls" -> {
                    runtime.reconcileExpiry()
                    result.success(runtime.store.all().filter { it.getString("state") != "ended" }.map { it.toMap() })
                }
                "acknowledgeEvent" -> { runtime.store.acknowledge(args["eventId"] as String); result.success(null) }
                "acceptCall", "declineCall", "setCallConnecting", "setCallConnected", "endCall" -> {
                    require(!id.isNullOrBlank()) { "Missing callId" }
                    val reason = args["reason"] as? String ?: "localEnded"
                    val action = when (call.method) {
                        "setCallConnecting" -> "connecting"; "acceptCall" -> "accepted"; "declineCall" -> "declined"
                        "setCallConnected" -> "connected"; else -> if (reason == "missed") "missed" else "ended"
                    }
                    runtime.act(id, action, if (action == "declined") "declined" else if (action == "ended" || action == "missed") reason else null)
                    result.success(null)
                }
                "endAllCalls" -> {
                    runtime.store.all().forEach { runtime.act(it.getJSONObject("config").getString("callId"), "ended", "localEnded") }
                    result.success(null)
                }
                "getAudioEndpoints" -> { require(!id.isNullOrBlank()); result.success(runtime.audio.get(id)) }
                "requestAudioEndpoint" -> {
                    require(!id.isNullOrBlank())
                    val endpointId = args["endpointId"] as? String ?: throw IllegalArgumentException("Missing endpointId")
                    runtime.audio.request(id, endpointId) { error ->
                        if (error == null) result.success(null) else result.error("audio_route_error", error.message, null)
                    }
                }
                "checkPermissions" -> result.success(permissions())
                "requestPermissions" -> {
                    if (Build.VERSION.SDK_INT < 33 || runtime.context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) result.success(permissions())
                    else {
                        val host = binding?.activity ?: throw IllegalStateException("Permission requires a foreground activity")
                        check(permissionResult == null) { "Permission request already in progress" }
                        permissionResult = result; host.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8721)
                    }
                }
                "openFullScreenIntentSettings" -> {
                    val host = binding?.activity ?: throw IllegalStateException("Settings require a foreground activity")
                    if (Build.VERSION.SDK_INT >= 34) host.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${host.packageName}")))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (error: Exception) { result.error(if (error is SecurityException) "permission_denied" else "call_error", error.message, null) }
    }
    private fun permissions(): Map<String, String> = mapOf(
        "notifications" to if (!runtime.notifications.areNotificationsEnabled()) "denied" else "granted",
        "fullScreenIntent" to if (Build.VERSION.SDK_INT >= 34 && !runtime.notifications.canUseFullScreenIntent()) "settingsRequired" else "granted",
        "telecom" to if (Build.VERSION.SDK_INT < 26) "unsupported" else if (runtime.context.checkSelfPermission(Manifest.permission.MANAGE_OWN_CALLS) == PackageManager.PERMISSION_GRANTED) "granted" else "denied")
    override fun onRequestPermissionsResult(requestCode: Int, names: Array<out String>, results: IntArray): Boolean {
        if (requestCode != 8721) return false
        permissionResult?.success(permissions()); permissionResult = null; return true
    }
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        binding.addRequestPermissionsResultListener(this)
        binding.addOnNewIntentListener(this)
        handleAcceptIntent(binding.activity.intent)
    }
    override fun onNewIntent(intent: Intent): Boolean = handleAcceptIntent(intent)
    private fun handleAcceptIntent(intent: Intent?): Boolean {
        if (intent?.getStringExtra("unifiedCallAction") != "accepted") return false
        val id = intent.getStringExtra("callId") ?: return false
        intent.removeExtra("unifiedCallAction")
        runtime.act(id, "accepted")
        return true
    }
    override fun onDetachedFromActivityForConfigChanges() { detach(false) }
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { onAttachedToActivity(binding) }
    override fun onDetachedFromActivity() { detach(true) }
    private fun detach(fail: Boolean) {
        binding?.removeRequestPermissionsResultListener(this)
        binding?.removeOnNewIntentListener(this)
        binding = null
        if (fail) { permissionResult?.error("activity_detached", "Activity closed during permission request", null); permissionResult = null }
    }
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onCancel(null); detach(true); channel.setMethodCallHandler(null); eventChannel.setStreamHandler(null)
    }
}
