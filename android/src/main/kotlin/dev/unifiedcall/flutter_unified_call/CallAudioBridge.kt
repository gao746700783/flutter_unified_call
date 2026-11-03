package dev.unifiedcall.flutter_unified_call

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.*

internal class CallAudioBridge(private val runtime: CallRuntime) {
    private val muted = mutableMapOf<String, Boolean>()
    private val legacy = mutableMapOf<String, CallAudioState>()
    private var modern: EndpointAudio? = null
    private fun endpoints(): EndpointAudio = modern ?: EndpointAudio().also { modern = it }
    private fun live(id: String): Boolean = runtime.connections.containsKey(id) &&
        runtime.store.get(id)?.optString("state")?.let { it != "ended" } == true
    @Suppress("DEPRECATION")
    fun legacyChanged(id: String, state: CallAudioState) {
        if (!live(id)) return
        val previous = legacy.put(id, state)
        if (Build.VERSION.SDK_INT < 34) runtime.store.platformEvent(id, "audioRouteChanged", mapOf(
            "route" to route(state.route), "endpointId" to route(state.route), "isMuted" to state.isMuted))
        if (Build.VERSION.SDK_INT < 34 && (previous == null || previous.supportedRouteMask != state.supportedRouteMask)) {
            runtime.store.platformEvent(id, "audioEndpointsChanged", mapOf("endpoints" to get(id)))
        }
        muteChanged(id, state.isMuted)
    }
    fun availableChanged(id: String, values: List<CallEndpoint>) {
        if (!live(id)) return
        endpoints().available[id] = values.toList()
        runtime.store.platformEvent(id, "audioEndpointsChanged", mapOf("endpoints" to values.map(::endpointMap)))
    }
    fun endpointChanged(id: String, endpoint: CallEndpoint) {
        runtime.store.platformEvent(id, "audioRouteChanged", endpointMap(endpoint))
    }
    fun muteChanged(id: String, muted: Boolean) {
        if (!live(id)) return
        val previous = this.muted.put(id, muted)
        if (previous == null || previous != muted) runtime.store.platformEvent(id, "muteToggled", mapOf("isMuted" to muted))
    }
    fun remove(id: String) { muted.remove(id); legacy.remove(id); modern?.available?.remove(id) }
    fun get(id: String): List<Map<String, Any>> {
        if (Build.VERSION.SDK_INT < 26) throw UnsupportedOperationException("Audio endpoints require Android 8 or newer")
        val connection = runtime.connections[id] ?: throw IllegalStateException("No live Telecom connection")
        if (Build.VERSION.SDK_INT >= 34) return endpoints().available[id].orEmpty().map(::endpointMap)
        val state = legacy[id] ?: connection.callAudioState ?: return emptyList()
        return listOf(CallAudioState.ROUTE_EARPIECE, CallAudioState.ROUTE_SPEAKER,
            CallAudioState.ROUTE_BLUETOOTH, CallAudioState.ROUTE_WIRED_HEADSET)
            .filter { state.supportedRouteMask and it != 0 }
            .map { mapOf("id" to route(it), "label" to route(it), "route" to route(it)) }
    }
    @Suppress("DEPRECATION")
    fun request(id: String, endpointId: String, completion: (Exception?) -> Unit) {
        if (Build.VERSION.SDK_INT < 26) throw UnsupportedOperationException("Audio endpoints require Android 8 or newer")
        val connection = runtime.connections[id] ?: throw IllegalStateException("No live Telecom connection")
        check(runtime.store.get(id)?.optString("state") in listOf("accepted", "connected")) { "Accept the call before changing audio route" }
        if (Build.VERSION.SDK_INT >= 34) {
            val endpoint = endpoints().available[id].orEmpty().firstOrNull { it.identifier.toString() == endpointId }
                ?: throw IllegalArgumentException("Audio endpoint unavailable")
            val handler = Handler(Looper.getMainLooper())
            var finished = false
            val timeout = Runnable { if (!finished) { finished = true; completion(IllegalStateException("Audio endpoint request timed out")) } }
            handler.postDelayed(timeout, 5000)
            fun finish(error: Exception?) { if (!finished) { finished = true; handler.removeCallbacks(timeout); completion(error ?: if (!live(id)) IllegalStateException("Call ended before audio request completed") else null) } }
            try {
                connection.requestCallEndpointChange(endpoint, runtime.context.mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
                    override fun onResult(result: Void?) { finish(null) }
                    override fun onError(error: CallEndpointException) { finish(error) }
                })
            } catch (error: Exception) { finish(error) }
        } else {
            val selected = get(id).firstOrNull { it["id"] == endpointId } ?: throw IllegalArgumentException("Audio endpoint unavailable")
            val mask = when (selected["route"]) {
                "earpiece" -> CallAudioState.ROUTE_EARPIECE; "speaker" -> CallAudioState.ROUTE_SPEAKER
                "bluetooth" -> CallAudioState.ROUTE_BLUETOOTH; else -> CallAudioState.ROUTE_WIRED_HEADSET
            }
            connection.setAudioRoute(mask)
            completion(null) // Submitted; the subsequent system callback confirms the actual route.
        }
    }
    private class EndpointAudio { val available = mutableMapOf<String, List<CallEndpoint>>() }
    companion object {
        private fun route(value: Int): String = when (value) {
            CallAudioState.ROUTE_EARPIECE -> "earpiece"; CallAudioState.ROUTE_SPEAKER -> "speaker"
            CallAudioState.ROUTE_BLUETOOTH -> "bluetooth"; CallAudioState.ROUTE_WIRED_HEADSET -> "wiredHeadset"
            else -> "unknown"
        }
        private fun endpointMap(endpoint: CallEndpoint): Map<String, Any> {
            if (Build.VERSION.SDK_INT < 34) throw UnsupportedOperationException("CallEndpoint requires Android 14")
            return mapOf(
            "id" to endpoint.identifier.toString(), "endpointId" to endpoint.identifier.toString(), "label" to endpoint.endpointName.toString(),
            "route" to when (endpoint.endpointType) {
                CallEndpoint.TYPE_EARPIECE -> "earpiece"; CallEndpoint.TYPE_SPEAKER -> "speaker"
                CallEndpoint.TYPE_BLUETOOTH -> "bluetooth"; CallEndpoint.TYPE_WIRED_HEADSET -> "wiredHeadset"
                CallEndpoint.TYPE_STREAMING -> "streaming"; else -> "unknown"
            })
        }
    }
}
