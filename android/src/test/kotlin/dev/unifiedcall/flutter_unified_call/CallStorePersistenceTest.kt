package dev.unifiedcall.flutter_unified_call

import android.content.Context
import java.io.File
import java.io.IOException
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
class CallStorePersistenceTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "unified_call_v1.json").delete()
    }
    private fun config() = JSONObject().put("callId", "one").put("callerName", "Alice")
        .put("mediaType", "audio").put("expiresAt", System.currentTimeMillis() + 30000).put("extra", JSONObject())
    @Test fun failedTransitionRollsBackStateAndDoesNotDispatch() {
        var fail = false
        val store = CallStore(context) { if (fail) throw IOException("Disk unavailable") }
        store.incoming(config())
        var dispatched = 0
        store.onEvent = { dispatched++ }
        fail = true
        assertThrows(IOException::class.java) { store.transition("one", "accepted") }
        assertEquals("ringing", store.get("one")!!.getString("state"))
        assertEquals(1, store.pending().size)
        assertEquals(0, dispatched)
        fail = false
        assertTrue(store.transition("one", "accepted"))
        assertEquals(1, dispatched)
    }
    @Test fun failedAcknowledgmentPreservesInboxForRetry() {
        var fail = false
        val store = CallStore(context) { if (fail) throw IOException("Disk unavailable") }
        store.incoming(config())
        val id = (store.pending().single() as Map<*, *>)["eventId"] as String
        fail = true
        assertThrows(IOException::class.java) { store.acknowledge(id) }
        assertEquals(1, store.pending().size)
    }
    @Test fun actualFileWriteFailureLeavesNoPhantomIncomingEvent() {
        val store = CallStore(context)
        val blocker = File(context.filesDir, "unified_call_v1.json.new")
        assertTrue(blocker.mkdir())
        assertThrows(IOException::class.java) { store.incoming(config()) }
        assertNull(store.get("one"))
        assertTrue(store.pending().isEmpty())
        assertTrue(blocker.delete())
        assertTrue(store.incoming(config()))
        assertEquals(1, CallStore(context).pending().size)
    }
    @Test fun atomicBackupIsRecoveredBeforeLegacyPreferences() {
        val store = CallStore(context)
        store.incoming(config())
        val file = File(context.filesDir, "unified_call_v1.json")
        assertTrue(file.renameTo(File(file.path + ".bak")))
        assertEquals("ringing", CallStore(context).get("one")!!.getString("state"))
    }
    @Test fun legacyPreferencesAreReadThenAtomicFileTakesPrecedence() {
        val root = JSONObject().put("calls", JSONObject().put("one", JSONObject().put("config", config()).put("state", "ringing")))
            .put("events", org.json.JSONArray())
        val preferences = context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE)
        preferences.edit().putString("state", root.toString()).commit()
        val store = CallStore(context)
        assertEquals("ringing", store.get("one")!!.getString("state"))
        store.transition("one", "accepted")
        preferences.edit().putString("state", "invalid legacy value").commit()
        assertEquals("accepted", CallStore(context).get("one")!!.getString("state"))
    }
    @Test fun duplicateMergesExtraWithoutChangingIdentityOrHistoricalEvents() {
        val store = CallStore(context)
        val original = config().put("extra", JSONObject().put("room", "old").put("keep", true))
        assertTrue(store.incoming(original))
        val duplicate = config().put("callerName", "Changed").put("expiresAt", System.currentTimeMillis() + 60000)
            .put("extra", JSONObject().put("room", "new").put("nullable", JSONObject.NULL))
        assertFalse(store.incoming(duplicate))
        val saved = CallStore(context).get("one")!!.getJSONObject("config")
        assertEquals("Alice", saved.getString("callerName"))
        assertEquals(original.getLong("expiresAt"), saved.getLong("expiresAt"))
        assertEquals("new", saved.getJSONObject("extra").getString("room"))
        assertTrue(saved.getJSONObject("extra").getBoolean("keep"))
        assertTrue(saved.getJSONObject("extra").isNull("nullable"))
        assertEquals(1, store.pending().size)
        val incoming = (store.pending().single() as Map<*, *>)["call"] as Map<*, *>
        assertEquals("old", (incoming["extra"] as Map<*, *>)["room"])
        store.transition("one", "accepted")
        val accepted = (store.pending().last() as Map<*, *>)["call"] as Map<*, *>
        assertEquals("new", (accepted["extra"] as Map<*, *>)["room"])
        store.transition("one", "ended")
        assertFalse(store.incoming(config().put("extra", JSONObject().put("room", "ignored"))))
        assertEquals("new", store.get("one")!!.getJSONObject("config").getJSONObject("extra").getString("room"))
    }
    @Test fun duplicateWriteFailureRollsBackExtraWithoutDispatch() {
        var fail = false
        val store = CallStore(context) { if (fail) throw IOException("Disk unavailable") }
        store.incoming(config())
        var dispatched = 0
        store.onEvent = { dispatched++ }
        fail = true
        assertThrows(IOException::class.java) {
            store.incoming(config().put("extra", JSONObject().put("room", "new")))
        }
        assertEquals(0, store.get("one")!!.getJSONObject("config").getJSONObject("extra").length())
        assertEquals(1, store.pending().size)
        assertEquals(0, dispatched)
    }
    @Test fun expiredDuplicateDoesNotMergeOrExtendRinging() {
        val store = CallStore(context)
        store.incoming(config())
        val duplicate = config().put("expiresAt", System.currentTimeMillis() - 1)
            .put("extra", JSONObject().put("room", "ignored"))
        assertFalse(store.incoming(duplicate))
        assertEquals(0, store.get("one")!!.getJSONObject("config").getJSONObject("extra").length())
    }

}
