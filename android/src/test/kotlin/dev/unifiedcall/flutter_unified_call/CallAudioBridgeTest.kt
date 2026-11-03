package dev.unifiedcall.flutter_unified_call

import android.content.Context
import android.os.ParcelUuid
import android.telecom.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallAudioBridgeTest {
    private lateinit var runtime: CallRuntime
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "unified_call_v1.json").delete()
        CallRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        runtime = CallRuntime.get(context)
        runtime.store.incoming(JSONObject().put("callId", "audio").put("callerName", "Alice").put("mediaType", "audio")
            .put("expiresAt", System.currentTimeMillis() + 30000).put("extra", JSONObject()))
        runtime.connections["audio"] = object : Connection() {}
    }
    @Test @Config(sdk = [24]) fun unsupportedSdkRejectsRoutingBeforeCallingNewerApi() {
        assertThrows(UnsupportedOperationException::class.java) { runtime.audio.get("audio") }
        assertThrows(UnsupportedOperationException::class.java) { runtime.audio.request("audio", "speaker") {} }
    }
    @Test fun duplicateMuteCallbacksEmitOnlyOneStateEvent() {
        runtime.audio.muteChanged("audio", false)
        runtime.audio.muteChanged("audio", false)
        runtime.audio.muteChanged("audio", true)
        assertEquals(2, runtime.store.pending().filter { (it as Map<*, *>)["type"] == "muteToggled" }.size)
    }
    @Test fun modernEndpointsPreserveSystemIdentityAndRejectInventedIds() {
        val id = ParcelUuid(UUID.randomUUID())
        val endpoint = CallEndpoint("Speaker", CallEndpoint.TYPE_SPEAKER, id)
        runtime.audio.availableChanged("audio", listOf(endpoint))
        val values = runtime.audio.get("audio")
        assertEquals(id.toString(), values.single()["id"])
        assertEquals("speaker", values.single()["route"])
        runtime.store.transition("audio", "accepted")
        assertThrows(IllegalArgumentException::class.java) { runtime.audio.request("audio", "invented") {} }
        runtime.audio.endpointChanged("audio", endpoint)
        assertEquals("audioRouteChanged", (runtime.store.pending().last() as Map<*, *>)["type"])
        runtime.act("audio", "ended", "localEnded")
        assertThrows(IllegalStateException::class.java) { runtime.audio.get("audio") }
    }
    @Test @Config(sdk = [27]) fun legacyAvailableRoutesAndMuteChangesAreReported() {
        runtime.audio.legacyChanged("audio", CallAudioState(false, CallAudioState.ROUTE_EARPIECE,
            CallAudioState.ROUTE_EARPIECE or CallAudioState.ROUTE_SPEAKER))
        assertEquals(setOf("earpiece", "speaker"), runtime.audio.get("audio").map { it["route"] }.toSet())
        assertThrows(IllegalStateException::class.java) { runtime.audio.request("audio", "speaker") {} }
        runtime.store.transition("audio", "accepted")
        var submitted = false
        runtime.audio.request("audio", "speaker") { error -> assertNull(error); submitted = true }
        assertTrue(submitted)
        runtime.audio.legacyChanged("audio", CallAudioState(true, CallAudioState.ROUTE_SPEAKER, CallAudioState.ROUTE_SPEAKER))
        val event = runtime.store.pending().last() as Map<*, *>
        assertEquals("muteToggled", event["type"])
        assertEquals(true, (event["metadata"] as Map<*, *>)["isMuted"])
    }
}
