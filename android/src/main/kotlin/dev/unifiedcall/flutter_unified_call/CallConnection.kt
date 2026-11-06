package dev.unifiedcall.flutter_unified_call

import android.telecom.*

internal open class CallConnection(protected val runtime: CallRuntime, protected val id: String) : Connection() {
    override fun onShowIncomingCallUi() { runtime.showIncomingUi(id) }
    override fun onAnswer() { setAudioModeIsVoip(true); runtime.act(id, "accepted") }
    override fun onAnswer(videoState: Int) { onAnswer() }
    override fun onReject() { runtime.act(id, "declined", "declined") }
    override fun onDisconnect() {
        val call = runtime.store.get(id)
        val cancelling = call?.getJSONObject("config")?.isOutgoingCall() == true && call.optString("state") != "connected"
        runtime.act(id, "ended", if (cancelling) "cancelled" else "localEnded")
    }
    override fun onAbort() { runtime.act(id, "ended", "failed") }
    override fun onSilence() {
        super.onSilence()
        runtime.silenceRinger()
    }
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(state: CallAudioState) { runtime.audio.legacyChanged(id, state) }
}

@android.annotation.TargetApi(34)
internal class EndpointCallConnection(runtime: CallRuntime, id: String) : CallConnection(runtime, id) {
    override fun onAvailableCallEndpointsChanged(endpoints: List<CallEndpoint>) { runtime.audio.availableChanged(id, endpoints) }
    override fun onCallEndpointChanged(endpoint: CallEndpoint) { runtime.audio.endpointChanged(id, endpoint) }
    override fun onMuteStateChanged(isMuted: Boolean) { runtime.audio.muteChanged(id, isMuted) }
}
