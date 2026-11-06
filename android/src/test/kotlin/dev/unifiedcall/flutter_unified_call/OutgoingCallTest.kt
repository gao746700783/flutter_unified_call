package dev.unifiedcall.flutter_unified_call

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class OutgoingCallTest {
    private lateinit var context: Context
    private lateinit var runtime: CallRuntime
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "unified_call_v1.json").delete()
        resetRuntime()
        runtime = CallRuntime.get(context)
        runtime.configure(mapOf("enableTelecom" to false, "customCallActions" to true,
            "outgoingText" to "Calling", "connectingText" to "Connecting", "ongoingText" to "In call",
            "cancelCallText" to "Cancel call", "joiningMeetingText" to "Joining meeting", "meetingText" to "In meeting"))
    }
    private fun resetRuntime() {
        CallRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private fun config(id: String = "out", meeting: Boolean = false) = mapOf(
        "callId" to id, "callerName" to "Remote", "callerHandle" to null, "mediaType" to "audio", "direction" to "outgoing",
        "kind" to if (meeting) "meeting" else "pointToPoint", "expiresAt" to System.currentTimeMillis() + 60000,
        "extra" to if (meeting) mapOf("meetingName" to "Weekly", "meetingNumber" to "001234") else emptyMap<String, Any>())
    private fun note(id: String = "out") = shadowOf(runtime.notifications).getNotification(id, 1)

    @Test fun outgoingSetupIsSilentAndTransitionsWithoutIncomingEvents() {
        runtime.outgoing(config())
        assertEquals("dialing", runtime.store.get("out")!!.getString("state"))
        assertEquals("Calling", note().extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNull(note().fullScreenIntent)
        assertEquals(0, note().flags and Notification.FLAG_INSISTENT)
        assertEquals(listOf("Cancel call"), note().actions.map { it.title.toString() })
        runtime.act("out", "connecting")
        assertEquals("accepted", runtime.store.get("out")!!.getString("state"))
        assertEquals("Connecting", note().extras.getCharSequence(Notification.EXTRA_TEXT))
        runtime.act("out", "connected")
        assertEquals("In call", note().extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(listOf("started", "connecting", "connected"), runtime.store.pending().map { (it as Map<*, *>)["type"] })
        val persisted = CallStore(context).get("out")!!.getJSONObject("config")
        assertEquals("outgoing", persisted.getString("direction"))
        assertEquals("pointToPoint", persisted.getString("kind"))
        runtime.outgoing(config())
        assertEquals(3, runtime.store.pending().size)
    }

    @Test fun meetingsHaveJoiningStateAndNoHangupActionBeforeOrAfterConnected() {
        runtime.outgoing(config(meeting = true))
        assertEquals("accepted", runtime.store.get("out")!!.getString("state"))
        assertEquals("Joining meeting · 001234", note().extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(0, note().actions?.size ?: 0)
        assertTrue(runtime.connections.isEmpty())
        runtime.act("out", "connected")
        assertEquals("In meeting · 001234", note().extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(0, note().actions?.size ?: 0)
        assertNotNull(note().contentIntent)
        val incoming = config("invitation", true).toMutableMap().apply { put("direction", "incoming") }
        runtime.incoming(incoming)
        assertEquals(2, note("invitation").actions.size)
        assertEquals("Join meeting", note("invitation").actions[1].title.toString())
        assertEquals("Invitation to join a meeting", note("invitation").extras.getCharSequence(Notification.EXTRA_TEXT))
        runtime.act("invitation", "accepted")
        assertEquals(0, note("invitation").actions?.size ?: 0)
    }

    @Test fun expiredOutgoingAndMeetingSetupNeverBecomeMissedIncomingCalls() {
        runtime.outgoing(config())
        runtime.outgoing(config("join", true))
        runtime.outgoing(config("media-setup"))
        runtime.act("media-setup", "connecting")
        listOf("out", "join", "media-setup").forEach { id -> runtime.store.get(id)!!.getJSONObject("config").put("expiresAt", System.currentTimeMillis() - 1) }
        runtime.reconcileExpiry()
        val events = runtime.store.pending().map { it as Map<*, *> }
        assertFalse(events.any { it["type"] == "incoming" || it["type"] == "missed" })
        val endings = events.filter { it["type"] == "ended" }.associate { (it["call"] as Map<*, *>)["callId"] to it["reason"] }
        assertEquals(mapOf("out" to "unanswered", "join" to "failed", "media-setup" to "failed"), endings)
    }

    @Test fun nativeCancellationReportsCancelledInsteadOfLocalHangup() {
        runtime.outgoing(config())
        CallActionReceiver().onReceive(context, Intent().setAction("ended").putExtra("callId", "out"))
        assertEquals("cancelled", (runtime.store.pending().last() as Map<*, *>)["reason"])
    }

    @Test fun telecomOutgoingConnectionDialsThenActivatesAndRejectsDuplicates() {
        runtime.outgoing(config())
        val service = Robolectric.buildService(UnifiedConnectionService::class.java).create().get()
        val request = ConnectionRequest(runtime.account, null, Bundle().apply {
            putBundle(android.telecom.TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString("callId", "out") })
        })
        val connection = service.onCreateOutgoingConnection(runtime.account, request)
        assertEquals(Connection.STATE_DIALING, connection.state)
        val duplicate = service.onCreateOutgoingConnection(runtime.account, request)
        assertEquals(Connection.STATE_DISCONNECTED, duplicate.state)
        runtime.act("out", "connecting")
        assertEquals(Connection.STATE_DIALING, connection.state)
        runtime.act("out", "connected")
        assertEquals(Connection.STATE_ACTIVE, connection.state)
        connection.onDisconnect()
        assertEquals("ended", runtime.store.get("out")!!.getString("state"))
        assertTrue(runtime.connections.isEmpty())
    }

    @Test fun freshRuntimeFailsStaleOutgoingSessionRatherThanRingingAgain() {
        runtime.outgoing(config())
        resetRuntime()
        val recovered = CallRuntime.get(context)
        assertEquals("ended", recovered.store.get("out")!!.getString("state"))
        assertEquals("failed", (recovered.store.pending().last() as Map<*, *>)["reason"])
    }
}
