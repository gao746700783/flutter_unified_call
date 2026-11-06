package dev.unifiedcall.flutter_unified_call

import android.content.Context
import org.json.JSONObject

/** Resolves persisted overrides and native defaults without a Flutter engine. */
internal fun callText(context: Context, options: JSONObject, key: String): String {
    val override = options.opt(key)
    if (override is String) return override
    if (key == "joinMeetingText" && options.opt("acceptText") is String) return options.getString("acceptText")
    val resource = when (key) {
        "acceptText" -> R.string.unified_call_acceptText
        "declineText" -> R.string.unified_call_declineText
        "hangupText" -> R.string.unified_call_hangupText
        "audioSubtitle" -> R.string.unified_call_audioSubtitle
        "videoSubtitle" -> R.string.unified_call_videoSubtitle
        "incomingTitle" -> R.string.unified_call_incomingTitle
        "ongoingText" -> R.string.unified_call_ongoingText
        "connectingText" -> R.string.unified_call_connectingText
        "connectedText" -> R.string.unified_call_connectedText
        "cancelCallText" -> R.string.unified_call_cancelCallText
        "joinMeetingText" -> R.string.unified_call_joinMeetingText
        "joiningMeetingText" -> R.string.unified_call_joiningMeetingText
        "outgoingText" -> R.string.unified_call_outgoingText
        "meetingText" -> R.string.unified_call_meetingText
        "meetingNamePrefix" -> R.string.unified_call_meetingNamePrefix
        "meetingInvitationText" -> R.string.unified_call_meetingInvitationText
        "unknownCallerText" -> R.string.unified_call_unknownCallerText
        "callerAvatarText" -> R.string.unified_call_callerAvatarText
        "incomingChannelName" -> R.string.unified_call_incomingChannelName
        "ongoingChannelName" -> R.string.unified_call_ongoingChannelName
        "incomingChannelDescription" -> R.string.unified_call_incomingChannelDescription
        "updateErrorText" -> R.string.unified_call_updateErrorText
        "retryText" -> R.string.unified_call_retryText
        else -> error("Unknown call text: $key")
    }
    return context.getString(resource)
}
