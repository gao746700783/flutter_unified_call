package dev.unifiedcall.flutter_unified_call

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallRecoveryTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "unified_call_v1.json").delete()
        // Each test simulates a fresh process singleton.
        CallRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    private fun config(id: String, expiry: Long) = JSONObject().put("callId", id)
        .put("callerName", "Alice").put("mediaType", "audio").put("expiresAt", expiry).put("extra", JSONObject())
    @Test fun nativePushEntryWorksWithoutFlutterAndPropagatesErrors() {
        CallStore(context).configure(mapOf("enableTelecom" to false))
        val result = UnifiedCall.showIncomingCall(context, config("push", System.currentTimeMillis() + 30000).toMap())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        result.get()
        assertEquals("ringing", CallRuntime.get(context).store.get("push")!!.getString("state"))
        val cancelled = UnifiedCall.cancelCall(context, "push")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        cancelled.get()
        assertEquals("ended", CallRuntime.get(context).store.get("push")!!.getString("state"))
        val invalid = UnifiedCall.showIncomingCall(context, emptyMap())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(invalid.isCompletedExceptionally)
    }
    @Test fun restoresRingingSchedulesAlarmAndCancelsOnAccept() {
        val stored = CallStore(context)
        stored.incoming(config("ring", System.currentTimeMillis() + 30000))
        val runtime = CallRuntime.get(context)
        val alarm = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarm.scheduledAlarms.size)
        assertEquals("ringing", runtime.store.get("ring")!!.getString("state"))
        runtime.act("ring", "accepted")
        assertEquals(0, alarm.scheduledAlarms.size)
        runtime.initialize()
        assertEquals("accepted", runtime.store.get("ring")!!.getString("state"))
    }
    @Test fun acceptedSessionFromDeadProcessEndsAsFailed() {
        val stored = CallStore(context)
        stored.incoming(config("lost", System.currentTimeMillis() + 30000))
        stored.transition("lost", "accepted")
        val runtime = CallRuntime.get(context)
        assertEquals("ended", runtime.store.get("lost")!!.getString("state"))
        val event = runtime.store.pending().last() as Map<*, *>
        assertEquals("failed", event["reason"])
        assertEquals("ended", event["type"])
    }
    @Test fun timeoutReceiverReconcilesPersistedExpiredCallWithoutDart() {
        val stored = CallStore(context)
        stored.incoming(config("expired", System.currentTimeMillis() + 30000))
        // Simulate wall-clock expiry in the persisted snapshot.
        val file = java.io.File(context.filesDir, "unified_call_v1.json")
        val root = JSONObject(file.readText())
        root.getJSONObject("calls").getJSONObject("expired").getJSONObject("config").put("expiresAt", 1)
        file.writeText(root.toString())
        CallTimeoutReceiver().onReceive(context, Intent().putExtra("callId", "expired"))
        val runtime = CallRuntime.get(context)
        assertNull(runtime.store.get("expired"))
        assertEquals("missed", (runtime.store.pending().last() as Map<*, *>)["type"])
    }
}
