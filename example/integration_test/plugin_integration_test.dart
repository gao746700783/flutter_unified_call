import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:integration_test/integration_test.dart';
import 'package:flutter_unified_call/flutter_unified_call.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  final api = FlutterUnifiedCall.instance;
  testWidgets('native lifecycle, dedupe, durable inbox and timeout', (tester) async {
    await api.initialize(enableTelecom: false, androidRingtoneName: 'demo_ringtone');
    if (!kIsWeb && defaultTargetPlatform == TargetPlatform.iOS) {
      await expectLater(api.initialize(iosRingtoneFileName: 'missing-ringtone.wav'),
        throwsA(isA<PlatformException>()));
    }
    if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android) {
      await expectLater(api.initialize(androidRingtoneName: 'missing_ringtone'),
        throwsA(isA<PlatformException>()));
    }
    await api.endAllCalls();
    for (final event in await api.getPendingEvents()) {
      await api.acknowledgeEvent(event.eventId);
    }
    final received = <CallEvent>[];
    final subscription = api.onEvent.listen(received.add);
    final id = 'integration-${DateTime.now().microsecondsSinceEpoch}';
    final config = IncomingCallConfig(callId: id, callerName: 'Integration Test',
      expiresAt: DateTime.now().add(const Duration(seconds: 30)), extra: {'roomId': 'test-room'});
    await api.showIncomingCall(config);
    await api.showIncomingCall(config);
    expect((await api.getActiveCalls()).single.state, CallState.ringing);
    await api.acceptCall(id);
    expect((await api.getActiveCalls()).single.state, CallState.accepted);
    await api.setCallConnected(id);
    expect((await api.getActiveCalls()).single.state, CallState.connected);
    await api.endCall(id);
    expect(await api.getActiveCalls(), isEmpty);
    await api.acceptCall(id);
    final events = (await api.getPendingEvents()).where((e) => e.callId == id).toList();
    expectNotificationFallbacks(events);
    expect(events.where((e) => e.type != CallEventType.platformError).map((e) => e.type), [CallEventType.incoming, CallEventType.accepted,
      CallEventType.connected, CallEventType.ended]);
    expect(events.every((e) => e.call.extra['roomId'] == 'test-room'), isTrue);
    expect((await api.getPendingEvents()).map((e) => e.eventId), events.map((e) => e.eventId));
    for (final event in events) { await api.acknowledgeEvent(event.eventId); }
    expect(await api.getPendingEvents(), isEmpty);
    final timeoutId = '$id-timeout';
    await api.showIncomingCall(IncomingCallConfig(callId: timeoutId, callerName: 'Timeout Test',
      expiresAt: DateTime.now().add(const Duration(milliseconds: 300))));
    await tester.runAsync(() => Future<void>.delayed(const Duration(seconds: 1)));
    final timeoutEvents = (await api.getPendingEvents()).where((e) => e.callId == timeoutId).toList();
    expectNotificationFallbacks(timeoutEvents);
    expect(timeoutEvents.where((e) => e.type != CallEventType.platformError).map((e) => e.type), [CallEventType.incoming, CallEventType.missed]);
    expect(timeoutEvents.last.reason, CallEndReason.missed);
    expect(received.where((e) => e.callId == id && e.type != CallEventType.platformError).length, 4);
    for (final event in timeoutEvents) { await api.acknowledgeEvent(event.eventId); }
    await subscription.cancel();
    expect((await api.checkPermissions()).notifications, isNot(CallPermissionState.unknown));
  });
}

void expectNotificationFallbacks(Iterable<CallEvent> events) {
  final errors = events.where((event) => event.type == CallEventType.platformError).toList();
  expect(errors.length, lessThanOrEqualTo(1));
  for (final event in errors) {
    expect(event.metadata, containsPair('operation', 'notification'));
    expect(event.metadata, containsPair('error', 'call_style_rejected'));
    expect(event.metadata, containsPair('fallback', 'generic'));
  }
}
