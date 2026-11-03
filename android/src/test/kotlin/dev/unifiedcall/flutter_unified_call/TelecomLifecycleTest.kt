package dev.unifiedcall.flutter_unified_call

import android.content.Context
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TelecomLifecycleTest {
    private lateinit var runtime: CallRuntime
    private lateinit var service: UnifiedConnectionService
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "unified_call_v1.json").delete()
        CallRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        runtime = CallRuntime.get(context)
        service = Robolectric.buildService(UnifiedConnectionService::class.java).create().get()
        runtime.store.incoming(JSONObject().put("callId", "test").put("callerName", "Alice")
            .put("mediaType", "audio").put("expiresAt", System.currentTimeMillis() + 30000).put("extra", JSONObject()))
    }
    private fun request() = ConnectionRequest(runtime.account, null, Bundle().apply { putString("callId", "test") })
    @Test fun systemAnswerAndDisconnectDriveDurableState() {
        val connection = service.onCreateIncomingConnection(runtime.account, request())
        assertEquals(Connection.STATE_RINGING, connection.state)
        connection.onShowIncomingCallUi()
        connection.onAnswer()
        assertEquals("accepted", runtime.store.get("test")!!.getString("state"))
        assertEquals(Connection.STATE_ACTIVE, connection.state)
        connection.onDisconnect()
        assertEquals("ended", runtime.store.get("test")!!.getString("state"))
        assertTrue(runtime.connections.isEmpty())
    }
    @Test fun duplicateRequestCannotReplaceExistingConnection() {
        val connection = service.onCreateIncomingConnection(runtime.account, request())
        val duplicate = service.onCreateIncomingConnection(runtime.account, request())
        assertEquals(Connection.STATE_DISCONNECTED, duplicate.state)
        assertSame(connection, runtime.connections["test"])
    }
    @Test fun rejectedCreationEndsCallInsteadOfLeavingRingingState() {
        service.onCreateIncomingConnectionFailed(runtime.account, request())
        assertEquals("ended", runtime.store.get("test")!!.getString("state"))
        val event = runtime.store.pending().last() as Map<*, *>
        assertEquals("ended", event["type"])
        assertEquals("failed", event["reason"])
    }
}
