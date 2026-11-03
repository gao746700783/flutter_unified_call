package dev.unifiedcall.flutter_unified_call

import android.app.Notification
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallRingtoneTest {
    private lateinit var runtime: CallRuntime
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "unified_call_v1.json").delete()
        CallRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        runtime = CallRuntime.get(context)
        runtime.configure(mapOf("enableTelecom" to false))
    }
    @Test fun invalidResourceDoesNotPersistNewOptions() {
        assertThrows(IllegalArgumentException::class.java) { runtime.configure(mapOf("androidRingtoneName" to "missing_ringtone")) }
        assertThrows(IllegalArgumentException::class.java) { runtime.configure(mapOf("androidRingtoneName" to "../invalid")) }
        assertFalse(runtime.store.options().has("androidRingtoneName"))
        assertFalse(runtime.store.options().getBoolean("enableTelecom"))
    }
    @Test fun removedResourceFallsBackDuringRecovery() {
        runtime.store.configure(mapOf("androidRingtoneName" to "removed_by_upgrade"))
        runtime.initialize()
        assertTrue(runtime.store.options().isNull("androidRingtoneName"))
        assertNotNull(runtime.notifications.getNotificationChannel("unified_call_incoming"))
    }
    @Test fun ringingRepeatsSoundAndAcceptanceRemovesRepeatFlag() {
        runtime.incoming(mapOf("callId" to "ring", "callerName" to "Alice", "mediaType" to "audio",
            "expiresAt" to System.currentTimeMillis() + 30000, "extra" to emptyMap<String, Any>()))
        val incoming = runtime.notifications.activeNotifications.single().notification
        assertEquals("unified_call_incoming", incoming.channelId)
        assertTrue(incoming.flags and Notification.FLAG_INSISTENT != 0)
        runtime.act("ring", "accepted")
        val accepted = runtime.notifications.activeNotifications.single().notification
        assertEquals("unified_call_ongoing", accepted.channelId)
        assertEquals(0, accepted.flags and Notification.FLAG_INSISTENT)
    }
}
