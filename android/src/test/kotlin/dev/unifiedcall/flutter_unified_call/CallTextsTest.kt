package dev.unifiedcall.flutter_unified_call

import android.app.Notification
import android.content.Context
import android.telecom.Connection
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
class CallTextsTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("unified_call_v1", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "unified_call_v1.json").delete()
        CallRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @Test @Config(qualifiers = "zh") fun nativeDefaultsWorkWithoutFlutter() {
        assertEquals("接听", callText(context, JSONObject(), "acceptText"))
        assertEquals("会议名称：", callText(context, JSONObject(), "meetingNamePrefix"))
        assertEquals("语音来电", callText(context, JSONObject(), "audioSubtitle"))
        assertEquals("Answer", callText(context, JSONObject().put("acceptText", "Answer"), "acceptText"))
    }

    @Test @Config(qualifiers = "en") fun nativeEnglishDefaults() {
        assertEquals("Decline", callText(context, JSONObject(), "declineText"))
        assertEquals("Meeting name: ", callText(context, JSONObject(), "meetingNamePrefix"))
        assertEquals("Call in progress", callText(context, JSONObject(), "ongoingText"))
    }

    @Test fun customActionsAndSubtitlesSurviveProcessRestart() {
        var runtime = CallRuntime.get(context)
        runtime.configure(mapOf("enableTelecom" to false, "customCallActions" to true,
            "acceptText" to "Join", "declineText" to "Dismiss", "audioSubtitle" to "Voice invitation"))
        runtime.incoming(mapOf("callId" to "text-test", "callerName" to "Alice", "mediaType" to "audio",
            "expiresAt" to System.currentTimeMillis() + 60000, "extra" to emptyMap<String, Any>()))
        CallRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        runtime = CallRuntime.get(context)
        assertEquals("Join", runtime.text("acceptText"))
        val note = shadowOf(runtime.notifications).getNotification("text-test", 1)
        assertEquals("Voice invitation", note.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(listOf("Dismiss", "Join"), note.actions.map { it.title.toString() })
    }

    @Test @Config(qualifiers = "zh") fun meetingNotificationUsesStateInsteadOfInvitationHandle() {
        val runtime = CallRuntime.get(context)
        runtime.configure(mapOf("enableTelecom" to false, "customCallActions" to true))
        runtime.incoming(mapOf("callId" to "meeting", "callerName" to "Inviter",
            "callerHandle" to "邀请你加入会议", "mediaType" to "audio",
            "expiresAt" to System.currentTimeMillis() + 60000, "extra" to mapOf("meeting" to true)))
        fun notification() = shadowOf(runtime.notifications).getNotification("meeting", 1)
        assertEquals("邀请你加入会议", notification().extras.getCharSequence(Notification.EXTRA_TEXT))
        runtime.act("meeting", "accepted")
        assertEquals("正在接通", notification().extras.getCharSequence(Notification.EXTRA_TEXT))
        runtime.act("meeting", "connected")
        assertEquals("会议中", notification().extras.getCharSequence(Notification.EXTRA_TEXT))
        runtime.updateCall("meeting", null, "Custom invitation", JSONObject()
            .put("meetingName", "Weekly sync").put("meetingNumber", "001234"))
        assertEquals("会议中 · 001234", notification().extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals("会议名称：Weekly sync", notification().extras.getCharSequence(Notification.EXTRA_TITLE))
        runtime.updateCall("meeting", null, null, JSONObject()
            .put("meetingName", JSONObject.NULL).put("meetingNumber", JSONObject.NULL))
        assertEquals("Inviter", notification().extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("会议中", notification().extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun foregroundMeetingNotificationUsesLocalizedStateAndDetails() {
        val runtime = CallRuntime.get(context)
        runtime.configure(mapOf("enableTelecom" to false, "customCallActions" to true,
            "connectingText" to "Joining meeting", "meetingText" to "In meeting",
            "meetingNamePrefix" to "Room: "))
        runtime.incoming(mapOf("callId" to "meeting-service", "callerName" to "Inviter",
            "callerHandle" to "Invitation to join a meeting", "mediaType" to "audio",
            "expiresAt" to System.currentTimeMillis() + 60000,
            "extra" to mapOf("meeting" to true, "meetingName" to "Weekly sync", "meetingNumber" to "001234")))
        runtime.connections["meeting-service"] = object : Connection() {}
        runtime.act("meeting-service", "accepted")
        assertEquals("Joining meeting · 001234", runtime.foregroundNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
        runtime.act("meeting-service", "connected")
        val note = runtime.foregroundNotification()
        assertEquals("In meeting · 001234", note.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals("Room: Weekly sync", note.extras.getCharSequence(Notification.EXTRA_TITLE))
        runtime.updateCall("meeting-service", null, null, JSONObject().put("meetingNumber", "00876543"))
        assertEquals("In meeting · 00876543", runtime.foregroundNotification().extras.getCharSequence(Notification.EXTRA_TEXT))
        runtime.act("meeting-service", "ended", "localEnded")
    }

}
