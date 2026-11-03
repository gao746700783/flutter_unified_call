package dev.unifiedcall.flutter_unified_call

import android.os.Build
import android.telecom.*

class UnifiedConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(handle: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val runtime = CallRuntime.get(this)
        val id = request.extras?.getString("callId")
            ?: request.extras?.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS)?.getString("callId")
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        runtime.reconcileExpiry()
        if (runtime.connections.containsKey(id)) return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        val call = runtime.store.get(id)
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        if (call.getString("state") == "ended") return Connection.createFailedConnection(DisconnectCause(DisconnectCause.CANCELED))
        return (if (Build.VERSION.SDK_INT >= 34) EndpointCallConnection(runtime, id) else CallConnection(runtime, id)).apply {
            if (Build.VERSION.SDK_INT >= 26) connectionProperties = Connection.PROPERTY_SELF_MANAGED
            setCallerDisplayName(call.getJSONObject("config").getString("callerName"), TelecomManager.PRESENTATION_ALLOWED)
            setAudioModeIsVoip(true)
            runtime.connections[id] = this
            setRinging()
            android.os.Handler(mainLooper).post {
                val state = runtime.store.get(id)?.getString("state")
                if (state == "accepted" || state == "connected") { setActive(); runtime.updateForegroundService() }
                else if (state == "ended") {
                    runtime.connections.remove(id)
                    setDisconnected(DisconnectCause(DisconnectCause.CANCELED)); destroy()
                }
            }
        }
    }
    override fun onCreateIncomingConnectionFailed(handle: PhoneAccountHandle?, request: ConnectionRequest) {
        val id = request.extras?.getString("callId") ?: return
        val runtime = CallRuntime.get(this)
        runtime.store.platformEvent(id, "platformError", mapOf("operation" to "telecom", "error" to "telecom_rejected"))
        runtime.act(id, "ended", "failed")
    }
}
