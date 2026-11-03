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
    fun incoming(config: JSONObject): Boolean {
        val id = config.getString("callId")
        require(id.isNotBlank() && config.getString("callerName").isNotBlank()) { "Empty call identity" }
        require(config.has("extra") && config.get("extra") is JSONObject) { "extra must be an object" }
        require(config.optString("mediaType") in listOf("audio", "video")) { "Invalid media type" }
        require(config.get("expiresAt") is Number) { "expiresAt must be epoch milliseconds" }
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
        val call = JSONObject().put("config", config).put("state", "ringing")
        calls.put(id, call); emit(call, "incoming"); return true
    }
    fun transition(id: String, action: String, reason: String? = null): Boolean {
        val call = get(id) ?: return false
        val state = call.getString("state")
        if (state == "ended") return false
        if ((action == "accepted" || action == "declined" || action == "missed") && state != "ringing") return false
        if (action == "connected") {
            if (state == "connected") return false
            check(state == "accepted") { "Accept the call before connecting" }
        }
        call.put("state", if (action == "accepted" || action == "connected") action else "ended")
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
    private fun pruneEndedCalls() {
        val endedKeys = calls.keys().asSequence().filter { calls.getJSONObject(it).optString("state") == "ended" }.toList()
        if (endedKeys.size > 50) {
            for (key in endedKeys.take(endedKeys.size - 50)) {
                calls.remove(key)
            }
        }
    }
    private fun save() {
        pruneEndedCalls()
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
