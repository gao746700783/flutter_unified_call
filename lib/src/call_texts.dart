/// Shared notification, full-screen UI and banner text overrides.
///
/// Unset labels use Chinese for `zh` locales and English for other locales.
/// Set [languageCode] to `zh` or `en` to force a language. Otherwise initialize
/// uses the device locale and the banner uses the current Flutter locale.
class CallTexts {
  const CallTexts({
    this.languageCode,
    this.acceptText,
    this.declineText,
    this.hangupText,
    this.audioSubtitle,
    this.videoSubtitle,
    this.incomingTitle,
    this.ongoingText,
    this.connectingText,
    this.connectedText,
    this.outgoingText,
    this.meetingText,
    this.meetingNamePrefix,
    this.meetingInvitationText,
    this.unknownCallerText,
    this.callerAvatarText,
    this.incomingChannelName,
    this.ongoingChannelName,
    this.incomingChannelDescription,
    this.updateErrorText,
    this.retryText,
  }) : assert(languageCode == null ||
            languageCode == 'zh' ||
            languageCode == 'en');

  /// Optional language override; null follows the device or Flutter locale.
  final String? languageCode;

  /// Override for Accept.
  final String? acceptText;

  /// Override for Decline.
  final String? declineText;

  /// Override for End call.
  final String? hangupText;

  /// Override for Incoming audio call.
  final String? audioSubtitle;

  /// Override for Incoming video call.
  final String? videoSubtitle;

  /// Override for Incoming call.
  final String? incomingTitle;

  /// Override for Call in progress.
  final String? ongoingText;

  /// Override for Connecting.
  final String? connectingText;

  /// Override for Connected.
  final String? connectedText;

  /// Override for Calling.
  final String? outgoingText;

  /// Override for Meeting in progress.
  final String? meetingText;

  /// Prefix before an ongoing meeting name, including punctuation and spacing.
  final String? meetingNamePrefix;

  /// Override for Invitation to join a meeting.
  final String? meetingInvitationText;

  /// Override for Unknown caller.
  final String? unknownCallerText;

  /// Override for Caller avatar.
  final String? callerAvatarText;

  /// Override for Incoming calls.
  final String? incomingChannelName;

  /// Override for Ongoing calls.
  final String? ongoingChannelName;

  /// Override for Incoming audio and video calls.
  final String? incomingChannelDescription;

  /// Override for Unable to update call.
  final String? updateErrorText;

  /// Override for Retry.
  final String? retryText;

  /// Resolves overrides into the flat options persisted by every platform.
  Map<String, String> resolve(String locale) {
    if (languageCode != null && languageCode != 'zh' && languageCode != 'en') {
      throw ArgumentError.value(
          languageCode, 'languageCode', 'Expected zh or en');
    }
    final chinese =
        (languageCode ?? locale).toLowerCase().split(RegExp('[-_]')).first ==
            'zh';
    return {
      'acceptText': acceptText ?? (chinese ? '接听' : 'Accept'),
      'declineText': declineText ?? (chinese ? '拒绝' : 'Decline'),
      'hangupText': hangupText ?? (chinese ? '结束通话' : 'End call'),
      'audioSubtitle':
          audioSubtitle ?? (chinese ? '语音来电' : 'Incoming audio call'),
      'videoSubtitle':
          videoSubtitle ?? (chinese ? '视频来电' : 'Incoming video call'),
      'incomingTitle': incomingTitle ?? (chinese ? '来电' : 'Incoming call'),
      'ongoingText': ongoingText ?? (chinese ? '通话中' : 'Call in progress'),
      'connectingText': connectingText ?? (chinese ? '正在接通' : 'Connecting'),
      'connectedText': connectedText ?? (chinese ? '已接通' : 'Connected'),
      'outgoingText': outgoingText ?? (chinese ? '正在呼叫' : 'Calling'),
      'meetingText': meetingText ?? (chinese ? '会议中' : 'Meeting in progress'),
      'meetingNamePrefix': meetingNamePrefix ?? (chinese ? '会议名称：' : 'Meeting name: '),
      'meetingInvitationText': meetingInvitationText ??
          (chinese ? '邀请你加入会议' : 'Invitation to join a meeting'),
      'unknownCallerText':
          unknownCallerText ?? (chinese ? '未知来电者' : 'Unknown caller'),
      'callerAvatarText':
          callerAvatarText ?? (chinese ? '来电者头像' : 'Caller avatar'),
      'incomingChannelName':
          incomingChannelName ?? (chinese ? '来电通知' : 'Incoming calls'),
      'ongoingChannelName':
          ongoingChannelName ?? (chinese ? '通话中' : 'Ongoing calls'),
      'incomingChannelDescription': incomingChannelDescription ??
          (chinese ? '语音和视频来电通知' : 'Incoming audio and video calls'),
      'updateErrorText':
          updateErrorText ?? (chinese ? '无法更新通话' : 'Unable to update call'),
      'retryText': retryText ?? (chinese ? '重试' : 'Retry'),
    };
  }
}
