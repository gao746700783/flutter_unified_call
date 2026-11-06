import 'dart:ui' show PlatformDispatcher;
import 'package:flutter/foundation.dart' show ValueListenable, ValueNotifier;
import 'flutter_unified_call_platform_interface.dart';
import 'src/call_texts.dart';
import 'src/models.dart';
export 'src/call_texts.dart';
export 'src/models.dart';
export 'src/call_banner_host.dart';

/// Coordinates incoming-call state and platform integrations for the host app.
///
/// Use [instance] as the shared controller in UI and registered background
/// engines. Push delivery, signaling, business event handling, and media
/// connections remain the host application's responsibility.
class FlutterUnifiedCall {
  /// Creates a controller backed by the registered platform implementation.
  ///
  /// Prefer [instance] when sharing call events and commands across the app.
  FlutterUnifiedCall();

  /// The default controller used by the package example and banner UI.
  static final instance = FlutterUnifiedCall();
  CallTexts _texts = const CallTexts();
  final _textChanges = ValueNotifier<int>(0);
  String? _acceptText;
  String? _declineText;

  /// Notifies banners after text configuration is successfully applied.
  ValueListenable<int> get textChanges => _textChanges;

  /// Resolves shared labels, including legacy initialize action overrides.
  Map<String, String> resolveTexts(String locale) => {
    ..._texts.resolve(locale),
    if (_acceptText != null) 'acceptText': _acceptText!,
    if (_declineText != null) 'declineText': _declineText!,
  };

  FlutterUnifiedCallPlatform get _platform => FlutterUnifiedCallPlatform.instance;

  /// Events delivered while this controller is listening.
  ///
  /// Live delivery is best effort. Use [getPendingEvents] after startup to
  /// recover durable events, and deduplicate events by [CallEvent.eventId].
  late final Stream<CallEvent> onEvent = _platform.events.map(CallEvent.fromMap);

  /// Initializes call handling and applies platform-specific UI options.
  ///
  /// Call this before showing or receiving calls. Request permissions separately
  /// in response to a deliberate user action. Configure [webUseServiceWorker]
  /// only after installing the package's host-owned Worker adapter.
  Future<void> initialize({
    /// Shared UI labels. Legacy action parameters take precedence.
    CallTexts texts = const CallTexts(),

    /// Label used for the accept action in platform UI and the default banner.
    String? acceptText,

    /// Label used for the decline action in platform UI and the default banner.
    String? declineText,

    /// Enables Android self-managed Telecom. Set to false to use notification
    /// fallback UI instead.
    bool enableTelecom = true,

    /// Requests time-sensitive notification delivery on supported iOS versions.
    /// User settings and entitlements still control actual delivery.
    bool iosTimeSensitive = false,

    /// Enables foreground ringtone playback and sounds for plugin-created local
    /// notifications on iOS. Remote push sound remains controlled by the server.
    bool iosEnableRingtone = true,

    /// Optional CAF, WAV, or AIFF ringtone resource under 30 seconds in the iOS
    /// main bundle.
    String? iosRingtoneFileName,

    /// Optional lowercase Android `res/raw` resource name, without its
    /// extension.
    String? androidRingtoneName,

    /// Routes Web calls through an already-installed host-owned Worker adapter.
    bool webUseServiceWorker = false,

    /// Same-origin URL opened by a Web Worker notification action.
    String? webLaunchUrl,

    /// Optional HTTP(S) or relative URL for Web foreground ringtone playback.
    String? webRingtoneUrl,

    /// Enables foreground Web ringtone playback. Audio may require a user
    /// gesture.
    bool webEnableRingtone = true,

    /// Uses `CallBannerHost` instead of Android's incoming notification while
    /// foregrounded. Also mount the banner with `enabled: true`.
    bool androidForegroundBanner = false,
  }) async {
    await _platform.invoke('initialize', {
      ...texts.resolve(PlatformDispatcher.instance.locale.languageCode),
      if (acceptText != null) 'acceptText': acceptText,
      if (declineText != null) 'declineText': declineText,
      // Android CallStyle owns its button labels; custom labels use actions.
      'customCallActions': texts.languageCode != null || acceptText != null ||
          declineText != null || texts.acceptText != null ||
          texts.declineText != null || texts.hangupText != null,
      'enableTelecom': enableTelecom,
      'iosTimeSensitive': iosTimeSensitive, 'iosEnableRingtone': iosEnableRingtone,
      'iosRingtoneFileName': iosRingtoneFileName, 'androidRingtoneName': androidRingtoneName,
      'webRingtoneUrl': webRingtoneUrl, 'webEnableRingtone': webEnableRingtone,
      'webUseServiceWorker': webUseServiceWorker, 'webLaunchUrl': webLaunchUrl,
      'androidForegroundBanner': androidForegroundBanner,
    });
    _acceptText = acceptText;
    _declineText = declineText;
    _texts = texts;
    _textChanges.value++;
  }

  /// Unlocks Web Audio playback from a user gesture after [initialize].
  ///
  /// This operation is unsupported on native platforms.
  Future<void> unlockWebAudio() async { await _platform.invoke('unlockWebAudio'); }

  /// Displays and persists an incoming call described by [config].
  ///
  /// The absolute expiry in [config] is preserved; a delayed delivery does not
  /// restart its ringing duration. Platform and persistence failures are thrown.
  Future<void> showIncomingCall(
    /// Incoming call details, including its unique ID and absolute expiry.
    IncomingCallConfig config,
  ) async {
    await _platform.invoke('showIncomingCall', config.toMap());
  }

  /// Updates ongoing call details in place (e.g. caller name, handle, extra).
  Future<void> updateCall({
    required String callId,
    String? callerName,
    String? callerHandle,
    Map<String, Object?>? extra,
  }) async {
    if (callId.trim().isEmpty) throw ArgumentError.value(callId, 'callId');
    await _platform.invoke('updateCall', {
      'callId': callId,
      if (callerName != null) 'callerName': callerName,
      if (callerHandle != null) 'callerHandle': callerHandle,
      if (extra != null) 'extra': extra,
    });
  }

  /// Returns available Android Telecom audio endpoints for [callId].
  ///
  /// Endpoint IDs are opaque and should be passed unchanged to
  /// [requestAudioEndpoint]. This is unsupported without a live Android Telecom
  /// connection.
  Future<List<CallAudioEndpoint>> getAudioEndpoints(
    /// ID of a call with an active Android Telecom connection.
    String callId,
  ) async {
    if (callId.trim().isEmpty) throw ArgumentError.value(callId, 'callId');
    final result = await _platform.invoke('getAudioEndpoints', {'callId': callId}) as List? ?? const [];
    return result.map((e) => CallAudioEndpoint.fromMap(Map<String, Object?>.from(e as Map))).toList(growable: false);
  }

  /// Requests an audio route using an ID from [getAudioEndpoints].
  ///
  /// On Android API levels where the system reports the completed route change
  /// asynchronously, observe [onEvent] for the resulting route event.
  Future<void> requestAudioEndpoint(
    /// ID of the call whose audio route should change.
    String callId,
    /// Opaque endpoint ID returned by [getAudioEndpoints].
    String endpointId,
  ) {
    if (endpointId.trim().isEmpty) throw ArgumentError.value(endpointId, 'endpointId');
    return _command('requestAudioEndpoint', callId, {'endpointId': endpointId});
  }

  /// Records acceptance of a ringing call.
  ///
  /// This records user intent; it does not connect the host media engine.
  Future<void> acceptCall(
    /// ID of the ringing call to accept.
    String callId,
  ) => _command('acceptCall', callId);

  /// Declines a ringing call and records the resulting event.
  Future<void> declineCall(
    /// ID of the ringing call to decline.
    String callId,
  ) => _command('declineCall', callId);

  /// Marks a call connected after the host media engine confirms connection.
  Future<void> setCallConnected(
    /// ID of the call whose media connection succeeded.
    String callId,
  ) => _command('setCallConnected', callId);

  /// Ends a call with a typed [reason].
  Future<void> endCall(
    /// ID of the call to end.
    String callId, {
    /// Reason recorded with the terminal event.
    CallEndReason reason = CallEndReason.localEnded,
  }) =>
      _command('endCall', callId, {'reason': reason.name});

  /// Ends all active calls managed by this plugin.
  Future<void> endAllCalls() async { await _platform.invoke('endAllCalls'); }
  Future<void> _command(String method, String callId, [Map<String, Object?> extra = const {}]) async {
    if (callId.trim().isEmpty) throw ArgumentError.value(callId, 'callId');
    await _platform.invoke(method, {'callId': callId, ...extra});
  }
  /// Returns durable events that the host has not acknowledged.
  ///
  /// Replay is at least once and can overlap with [onEvent]. Subscribe first,
  /// deduplicate by [CallEvent.eventId], and acknowledge only after handling.
  Future<List<CallEvent>> getPendingEvents() async {
    final result = await _platform.invoke('getPendingEvents') as List? ?? const [];
    return result.map((e) => CallEvent.fromMap(Map<String, Object?>.from(e as Map))).toList(growable: false);
  }
  /// Returns current calls for UI restoration without acknowledging events.
  Future<List<CallSnapshot>> getActiveCalls() async {
    final result = await _platform.invoke('getActiveCalls') as List? ?? const [];
    return result.map((e) => CallSnapshot.fromMap(Map<String, Object?>.from(e as Map)))
        .toList(growable: false);
  }

  /// Acknowledges [eventId] after the host has completed handling that event.
  Future<void> acknowledgeEvent(
    /// Stable ID of a successfully handled event.
    String eventId,
  ) async {
    await _platform.invoke('acknowledgeEvent', {'eventId': eventId});
  }

  /// Reports current notification, full-screen, and Telecom permission states.
  Future<CallPermissionStatus> checkPermissions() async => CallPermissionStatus.fromMap(
    Map<String, Object?>.from(await _platform.invoke('checkPermissions') as Map));

  /// Requests supported permissions; call from an explicit user action.
  Future<CallPermissionStatus> requestPermissions() async => CallPermissionStatus.fromMap(
    Map<String, Object?>.from(await _platform.invoke('requestPermissions') as Map));

  /// Opens Android's full-screen intent settings when the platform supports it.
  Future<void> openFullScreenIntentSettings() async {
    await _platform.invoke('openFullScreenIntentSettings');
  }
}
