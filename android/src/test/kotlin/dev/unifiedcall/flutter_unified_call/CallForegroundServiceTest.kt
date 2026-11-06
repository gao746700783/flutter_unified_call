package dev.unifiedcall.flutter_unified_call

import android.content.ComponentName
import android.content.ContextWrapper
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
        assertEquals("unified_call_ongoing", runtime.foregroundNotification().channelId)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(runtime.ongoingTelecomIds().isEmpty())
        controller.destroy()
    }
    @Test fun outgoingNotificationSurvivesServiceStartFailure() {
        val app = RuntimeEnvironment.getApplication()
        val failingContext = object : ContextWrapper(app) {
            override fun startForegroundService(intent: Intent): ComponentName? {
                throw IllegalStateException("Simulated foreground service restriction")
            }
        }
        val failedRuntime = CallRuntime::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(failingContext)
        failedRuntime.initialize()
        failedRuntime.configure(mapOf("enableTelecom" to false))
        failedRuntime.outgoing(mapOf("callId" to "out", "callerName" to "Peer", "mediaType" to "audio",
            "expiresAt" to System.currentTimeMillis() + 60000, "extra" to emptyMap<String, Any>()))
        failedRuntime.connections["out"] = object : Connection() {}
        failedRuntime.showOutgoingUi("out")
        failedRuntime.updateForegroundService()
        assertNotNull(shadowOf(failedRuntime.notifications).getNotification("out", 1))
    }
    @Test fun notificationSuppressionRequiresSuccessfulPromotionAndRestoresOnFailure() {
        add("one")
        assertNotNull(shadowOf(runtime.notifications).getNotification("one", 1))
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        controller.get().onStartCommand(Intent(), 0, 1)
        assertNull(shadowOf(runtime.notifications).getNotification("one", 1))
        runtime.reportForegroundError(IllegalStateException("Simulated promotion failure"))
        assertNotNull(shadowOf(runtime.notifications).getNotification("one", 1))
        controller.destroy()
    }
    @Test fun serviceDestructionRestoresOngoingNotification() {
        add("one")
        val controller = Robolectric.buildService(CallForegroundService::class.java).create()
        controller.get().onStartCommand(Intent(), 0, 1)
        assertNull(shadowOf(runtime.notifications).getNotification("one", 1))
        controller.destroy()
        assertNotNull(shadowOf(runtime.notifications).getNotification("one", 1))
    }

}
