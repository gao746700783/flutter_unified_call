import 'flutter_unified_call_platform_interface.dart';
import 'src/models.dart';
export 'src/models.dart';
export 'src/call_banner_host.dart';

/// UI-independent entry point, also usable by registered background engines.
/// Push delivery and media/signaling are owned by the host application.
class FlutterUnifiedCall {
  FlutterUnifiedCall();
  static final instance = FlutterUnifiedCall();
  FlutterUnifiedCallPlatform get _platform => FlutterUnifiedCallPlatform.instance;
  late final Stream<CallEvent> onEvent = _platform.events.map(CallEvent.fromMap);

  Future<void> initialize({String acceptText = 'Accept', String declineText = 'Decline',
      bool enableTelecom = true, bool iosTimeSensitive = false, bool iosEnableRingtone = true,
      String? iosRingtoneFileName, String? androidRingtoneName, bool webUseServiceWorker = false,
      String? webLaunchUrl, String? webRingtoneUrl, bool webEnableRingtone = true}) async {
    await _platform.invoke('initialize', {
      'acceptText': acceptText, 'declineText': declineText, 'enableTelecom': enableTelecom,
      'iosTimeSensitive': iosTimeSensitive, 'iosEnableRingtone': iosEnableRingtone,
      'iosRingtoneFileName': iosRingtoneFileName, 'androidRingtoneName': androidRingtoneName,
      'webRingtoneUrl': webRingtoneUrl, 'webEnableRingtone': webEnableRingtone,
      'webUseServiceWorker': webUseServiceWorker, 'webLaunchUrl': webLaunchUrl,
    });
  }
  /// Call from a Web user gesture after initialize; unsupported on native platforms.
  Future<void> unlockWebAudio() async { await _platform.invoke('unlockWebAudio'); }
  Future<void> showIncomingCall(IncomingCallConfig config) async {
    await _platform.invoke('showIncomingCall', config.toMap());
  }
  Future<List<CallAudioEndpoint>> getAudioEndpoints(String callId) async {
    if (callId.trim().isEmpty) throw ArgumentError.value(callId, 'callId');
    final result = await _platform.invoke('getAudioEndpoints', {'callId': callId}) as List? ?? const [];
    return result.map((e) => CallAudioEndpoint.fromMap(Map<String, Object?>.from(e as Map))).toList(growable: false);
  }
  Future<void> requestAudioEndpoint(String callId, String endpointId) {
    if (endpointId.trim().isEmpty) throw ArgumentError.value(endpointId, 'endpointId');
    return _command('requestAudioEndpoint', callId, {'endpointId': endpointId});
  }
  Future<void> acceptCall(String callId) => _command('acceptCall', callId);
  Future<void> declineCall(String callId) => _command('declineCall', callId);
  Future<void> setCallConnected(String callId) => _command('setCallConnected', callId);
  Future<void> endCall(String callId, {CallEndReason reason = CallEndReason.localEnded}) =>
      _command('endCall', callId, {'reason': reason.name});
  Future<void> endAllCalls() async { await _platform.invoke('endAllCalls'); }
  Future<void> _command(String method, String callId, [Map<String, Object?> extra = const {}]) async {
    if (callId.trim().isEmpty) throw ArgumentError.value(callId, 'callId');
    await _platform.invoke(method, {'callId': callId, ...extra});
  }
  /// Durable unacknowledged events. Replay is at least once; dedupe by eventId.
  /// Subscribe first, then drain this inbox; acknowledge only after handling.
  Future<List<CallEvent>> getPendingEvents() async {
    final result = await _platform.invoke('getPendingEvents') as List? ?? const [];
    return result.map((e) => CallEvent.fromMap(Map<String, Object?>.from(e as Map))).toList(growable: false);
  }
  /// Active state for UI restoration; reading does not acknowledge any events.
  Future<List<CallSnapshot>> getActiveCalls() async {
    final result = await _platform.invoke('getActiveCalls') as List? ?? const [];
    return result.map((e) => CallSnapshot.fromMap(Map<String, Object?>.from(e as Map)))
        .toList(growable: false);
  }
  Future<void> acknowledgeEvent(String eventId) async {
    await _platform.invoke('acknowledgeEvent', {'eventId': eventId});
  }
  Future<CallPermissionStatus> checkPermissions() async => CallPermissionStatus.fromMap(
    Map<String, Object?>.from(await _platform.invoke('checkPermissions') as Map));
  Future<CallPermissionStatus> requestPermissions() async => CallPermissionStatus.fromMap(
    Map<String, Object?>.from(await _platform.invoke('requestPermissions') as Map));
  Future<void> openFullScreenIntentSettings() async {
    await _platform.invoke('openFullScreenIntentSettings');
  }
}
