package dev.unifiedcall.flutter_unified_call

import android.content.Context
import android.content.Intent
import android.telecom.Connection
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
@Config(sdk = [34])
class CallForegroundServiceTest {
    private lateinit var runtime: CallRuntime
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "unified_call_v1.json").delete()
        CallRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        runtime = CallRuntime.get(context)
        runtime.store.configure(mapOf("enableTelecom" to false))
    }
    private fun add(id: String) {
        runtime.incoming(mapOf("callId" to id, "callerName" to "Alice", "mediaType" to "audio",
            "expiresAt" to System.currentTimeMillis() + 30000, "extra" to emptyMap<String, Any>()))
        runtime.connections[id] = object : Connection() {}
        runtime.act(id, "accepted")
    }
    @Test fun acceptedTelecomCallPromotesAndLastEndStopsService() {
        add("one"); add("two")
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        assertEquals(197701, shadowOf(service).lastForegroundNotificationId)
        assertEquals(2, runtime.ongoingTelecomIds().size)
        runtime.act("one", "ended", "localEnded")
        assertEquals(listOf("two"), runtime.ongoingTelecomIds())
        runtime.act("two", "ended", "localEnded")
        service.onStartCommand(Intent(), 0, 2)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }
    @Test fun startDeliveryAfterCallEndedStopsWithoutRevivingCall() {
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(runtime.ongoingTelecomIds().isEmpty())
        controller.destroy()
    }
}
