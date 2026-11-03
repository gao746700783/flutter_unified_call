import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:flutter_unified_call/flutter_unified_call.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('real Android Telecom and foreground call lifecycle', (tester) async {
    if (kIsWeb || defaultTargetPlatform != TargetPlatform.android) return;
    final api = FlutterUnifiedCall.instance;
    await api.initialize(enableTelecom: true);
    await api.endAllCalls();
    final id = 'telecom-${DateTime.now().microsecondsSinceEpoch}';
    final received = <CallEvent>[];
    final ready = Completer<void>();
    final subscription = api.onEvent.listen((event) {
      if (event.callId != id) return;
      received.add(event);
      if (event.type == CallEventType.audioRouteChanged && event.metadata['route'] != null && !ready.isCompleted) ready.complete();
    }, onError: (Object error, StackTrace stack) {
      if (!ready.isCompleted) ready.completeError(error, stack);
    });
    try {
      await api.showIncomingCall(IncomingCallConfig(callId: id, callerName: 'Telecom Integration',
        expiresAt: DateTime.now().add(const Duration(seconds: 30))));
      await tester.runAsync(() => ready.future.timeout(const Duration(seconds: 10)));
      await api.acceptCall(id);
      await api.setCallConnected(id);
      final endpoints = await api.getAudioEndpoints(id);
      final speaker = endpoints.firstWhere((e) => e.route == CallAudioRoute.speaker);
      final routed = api.onEvent.firstWhere((event) => event.callId == id &&
        event.type == CallEventType.audioRouteChanged && event.metadata['route'] == 'speaker');
      await api.requestAudioEndpoint(id, speaker.id);
      await tester.runAsync(() => routed.timeout(const Duration(seconds: 10)));
      debugPrint('TELECOM_SPEAKER_ROUTE_CONFIRMED');
      // Allows external dumpsys verification of the OS foreground-service record.
      debugPrint('TELECOM_SERVICE_TEST_CONNECTED');
      await tester.runAsync(() => Future<void>.delayed(const Duration(seconds: 10)));
      final active = await api.getActiveCalls();
      expect(active.map((call) => call.state), [CallState.connected],
        reason: received.map((e) => '${e.type.name}:${e.reason?.name}').join(', '));
      expect(received.where((e) => e.type == CallEventType.platformError), isEmpty);
      await api.endCall(id);
      expect(await api.getActiveCalls(), isEmpty);
      expect(received.where((e) => [CallEventType.incoming, CallEventType.accepted,
        CallEventType.connected, CallEventType.ended].contains(e.type)).map((e) => e.type),
        [CallEventType.incoming, CallEventType.accepted, CallEventType.connected, CallEventType.ended]);
    } finally {
      await api.endAllCalls();
      await subscription.cancel();
    }
  });
}
