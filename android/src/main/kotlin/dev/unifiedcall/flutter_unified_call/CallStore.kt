package dev.unifiedcall.flutter_unified_call

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** All operations run on the Android main thread, shared across Flutter engines. */
internal class CallStore(context: Context, private val writeOverride: ((String) -> Unit)? = null) {
    private val file = AtomicFile(File(context.filesDir, "unified_call_v1.json"))
    private val preferences = context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE)
    private var committed = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        else preferences.getString("state", null) ?: "{\"calls\":{},\"events\":[]}"
    private var data = JSONObject(committed)
    private val calls get() = data.getJSONObject("calls")
    private val events get() = data.getJSONArray("events")
    var onEvent: ((Map<String, Any?>) -> Unit)? = null

    fun options(): JSONObject = data.optJSONObject("options") ?: JSONObject()
    fun configure(args: Map<*, *>) { data.put("options", JSONObject(args)); save() }
    fun get(id: String): JSONObject? = calls.optJSONObject(id)
    fun all(): List<JSONObject> = calls.keys().asSequence().map { calls.getJSONObject(it) }.toList()
    fun pending(): List<Any?> = events.toList()
    fun acknowledge(id: String) {
        val remaining = JSONArray()
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            if (event.getString("eventId") != id) remaining.put(event)
        }
        data.put("events", remaining); save()
    }
    fun incoming(config: JSONObject, outgoing: Boolean = false): Boolean {
        val id = config.getString("callId")
        require(id.isNotBlank() && config.getString("callerName").isNotBlank()) { "Empty call identity" }
        require(config.has("extra") && config.get("extra") is JSONObject) { "extra must be an object" }
        require(config.optString("mediaType") in listOf("audio", "video")) { "Invalid media type" }
        require(config.get("expiresAt") is Number) { "expiresAt must be epoch milliseconds" }
        require(!config.has("direction") || config.optString("direction") in listOf("incoming", "outgoing")) { "Invalid direction" }
        require(!config.has("kind") || config.optString("kind") in listOf("pointToPoint", "meeting")) { "Invalid call kind" }
        require(config.isOutgoingCall() == outgoing) { "Wrong call entry point" }
        val expiry = config.getLong("expiresAt")
        val now = System.currentTimeMillis()
        if (expiry <= now) return false
        val existing = get(id)
        if (existing != null) {
            val original = existing.getJSONObject("config")
            if (existing.getString("state") == "ended" ||
                (existing.getString("state") == "ringing" && original.getLong("expiresAt") <= now)) return false
            val extra = original.getJSONObject("extra")
            val updates = config.getJSONObject("extra")
            updates.keys().forEach { key -> extra.put(key, updates.get(key)) }
            save()
            return false
        }
        val state = if (!outgoing) "ringing" else if (config.isMeetingCall()) "accepted" else "dialing"
        val call = JSONObject().put("config", config).put("state", state)
        calls.put(id, call); emit(call, if (outgoing) "started" else "incoming"); return true
    }
    fun update(id: String, callerName: String?, callerHandle: String?, extraUpdates: JSONObject?): JSONObject? {
        val call = get(id) ?: return null
        if (call.getString("state") == "ended") return null
        val config = call.getJSONObject("config")
        if (!callerName.isNullOrBlank()) {
            config.put("callerName", callerName)
        }
        if (callerHandle != null) {
            config.put("callerHandle", callerHandle)
        }
        if (extraUpdates != null) {
            val extra = config.optJSONObject("extra") ?: JSONObject().also { config.put("extra", it) }
            extraUpdates.keys().forEach { key -> extra.put(key, extraUpdates.get(key)) }
        }
        save()
        return call
    }
    fun transition(id: String, action: String, reason: String? = null): Boolean {
        val call = get(id) ?: return false
        val state = call.getString("state")
        if (state == "ended") return false
        if ((action == "accepted" || action == "declined" || action == "missed") && state != "ringing") return false
        if (action == "connecting") {
            if (!call.getJSONObject("config").isOutgoingCall() || state != "dialing") return false
        }
        if (action == "connected") {
            if (state == "connected") return false
            val isOutgoing = call.getJSONObject("config").isOutgoingCall()
            check(state == "accepted" || (state == "dialing" && isOutgoing)) { "Accept the call before connecting" }
        }
        call.put("state", if (action == "connecting") "accepted" else if (action == "accepted" || action == "connected") action else "ended")
        emit(call, action, reason); return true
    }
    fun platformEvent(id: String, type: String, metadata: Map<String, Any?>) {
        val call = get(id) ?: return
        if (call.getString("state") != "ended") emit(call, type, metadata = metadata)
    }
    private fun emit(call: JSONObject, type: String, reason: String? = null, metadata: Map<String, Any?> = emptyMap()) {
        val event = JSONObject().put("eventId", UUID.randomUUID().toString())
            .put("type", type).put("call", JSONObject(call.getJSONObject("config").toString()))
            .put("occurredAt", System.currentTimeMillis()).put("reason", reason ?: JSONObject.NULL)
            .put("metadata", JSONObject(metadata))
        events.put(event); save(); onEvent?.invoke(event.toMap())
    }
    private fun save() {
        val now = System.currentTimeMillis()
        for (key in calls.keys().asSequence().toList()) {
            val call = calls.getJSONObject(key)
            if (call.optString("state") == "ended" &&
                call.getJSONObject("config").getLong("expiresAt") <= now) {
                calls.remove(key)
            }
        }
        val candidate = data.toString()
        try {
            val override = writeOverride
            if (override != null) override(candidate)
            else {
                val stream = file.startWrite()
                try {
                    stream.write(candidate.toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (error: Exception) { file.failWrite(stream); throw error }
            }
            committed = candidate
        } catch (error: Exception) {
            data = JSONObject(committed)
            throw error
        }
    }
}
internal fun JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { unwrap(get(it)) }
internal fun JSONArray.toList(): List<Any?> = (0 until length()).map { unwrap(get(it)) }
private fun unwrap(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> value.toList()
    else -> value
}

internal fun JSONObject.isOutgoingCall(): Boolean = if (has("direction")) optString("direction") == "outgoing"
    else optBoolean("outgoing", false) || optJSONObject("extra")?.optBoolean("outgoing") == true
internal fun JSONObject.isMeetingCall(): Boolean = if (has("kind")) optString("kind") == "meeting"
    else optBoolean("meeting", false) || optJSONObject("extra")?.optBoolean("meeting") == true
