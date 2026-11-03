package dev.unifiedcall.flutter_unified_call

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlutterUnifiedCallPluginTest {
    private lateinit var context: Context
    private lateinit var store: CallStore
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "unified_call_v1.json").delete()
        store = CallStore(context)
    }
    private fun config(expiry: Long = System.currentTimeMillis() + 30000) = JSONObject()
        .put("callId", "call-1").put("callerName", "Alice").put("mediaType", "audio")
        .put("expiresAt", expiry).put("extra", JSONObject().put("roomId", "room-1"))
    @Test fun duplicateAndExpiredIncomingDoNotEmitAgain() {
        assertFalse(store.incoming(config(1)))
        assertTrue(store.incoming(config()))
        assertFalse(store.incoming(config()))
        assertEquals(1, store.pending().size)
    }
    @Test fun eventPersistsUntilAcknowledgedAcrossStoreRecreation() {
        store.incoming(config()); store.transition("call-1", "accepted")
        val recovered = CallStore(context)
        val events = recovered.pending()
        assertEquals(2, events.size)
        val accepted = events.last() as Map<*, *>
        assertEquals("accepted", accepted["type"])
        recovered.acknowledge(accepted["eventId"] as String)
        assertEquals(1, CallStore(context).pending().size)
    }
    @Test fun terminalStateRejectsLateAcceptAndConnectedRequiresAcceptance() {
        store.incoming(config())
        assertThrows(IllegalStateException::class.java) { store.transition("call-1", "connected") }
        assertTrue(store.transition("call-1", "declined", "declined"))
        assertFalse(store.transition("call-1", "accepted"))
        assertFalse(store.incoming(config()))
        assertEquals("ended", store.get("call-1")!!.getString("state"))
    }
    @Test fun acceptedCallCannotBecomeMissed() {
        store.incoming(config()); store.transition("call-1", "accepted")
        assertFalse(store.transition("call-1", "missed", "missed"))
        assertTrue(store.transition("call-1", "connected"))
        assertFalse(store.transition("call-1", "connected"))
    }
}
