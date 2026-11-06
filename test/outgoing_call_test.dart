import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_unified_call/flutter_unified_call.dart';
import 'package:flutter_unified_call/flutter_unified_call_platform_interface.dart';

class OutgoingPlatform extends FlutterUnifiedCallPlatform {
  final controller = StreamController<Map<String, Object?>>.broadcast();
  final methods = <String>[];
  Map<String, Object?>? arguments;
  Object? response;
  @override
  Stream<Map<String, Object?>> get events => controller.stream;
  @override
  Future<Object?> invoke(String method,
      [Map<String, Object?>? arguments]) async {
    methods.add(method);
    this.arguments = arguments;
    return response;
  }
}

void main() {
  test(
      'outgoing direction and meeting kind survive wire snapshots and legacy payloads',
      () {
    final call = OutgoingCallConfig(
        callId: 'local',
        displayName: 'Weekly',
        kind: CallKind.meeting,
        expiresAt: DateTime.utc(2030),
        extra: {'meetingNumber': '001234'});
    final decoded = IncomingCallConfig.fromMap(call.toMap());
    expect(decoded.direction, CallDirection.outgoing);
    expect(decoded.kind, CallKind.meeting);
    expect(decoded.extra['meetingNumber'], '001234');
    expect(decoded.toMap(), call.toMap());
    final legacy = Map<String, Object?>.of(call.toMap())
      ..remove('direction')
      ..remove('kind');
    legacy['extra'] = {'meeting': true};
    expect(
        IncomingCallConfig.fromMap(legacy).direction, CallDirection.incoming);
    expect(IncomingCallConfig.fromMap(legacy).kind, CallKind.meeting);
    expect(
        () => OutgoingCallConfig(
            callId: 'x', displayName: '', expiresAt: DateTime.utc(2030)),
        throwsArgumentError);
  });

  testWidgets(
      'outgoing API is separate and incoming banner never exposes outgoing controls',
      (tester) async {
    final platform = OutgoingPlatform();
    FlutterUnifiedCallPlatform.instance = platform;
    final api = FlutterUnifiedCall();
    final call = OutgoingCallConfig(
        callId: 'local',
        displayName: 'Peer',
        expiresAt: DateTime.now().add(const Duration(minutes: 1)));
    await api.startOutgoingCall(call);
    expect(platform.methods.last, 'startOutgoingCall');
    expect(platform.arguments!['direction'], 'outgoing');
    await api.setCallConnecting('local');
    expect(platform.methods.last, 'setCallConnecting');
    await expectLater(api.showIncomingCall(call), throwsArgumentError);
    // Include a legacy ringing state to verify the direction check itself.
    platform.response = [
      {'state': 'ringing', 'config': call.toMap()}
    ];
    await tester.pumpWidget(MaterialApp(
        home: CallBannerHost(
      controller: api,
      enabled: true,
      child: const Text('Application'),
    )));
    await tester.pump();
    expect(find.text('Peer'), findsNothing);
    expect(find.text('Accept'), findsNothing);
    expect(find.text('Application'), findsOneWidget);
    expect(platform.methods.contains('getPendingEvents'), isFalse);
    await tester.pumpWidget(const SizedBox());
    await platform.controller.close();
  });
  testWidgets(
      'meeting invitation uses a join action while point-to-point keeps accept',
      (tester) async {
    final platform = OutgoingPlatform();
    FlutterUnifiedCallPlatform.instance = platform;
    final api = FlutterUnifiedCall();
    final invite = IncomingCallConfig(
        callId: 'meeting-invite',
        callerName: 'Weekly',
        kind: CallKind.meeting,
        expiresAt: DateTime.now().add(const Duration(minutes: 1)));
    platform.response = [
      {'state': 'ringing', 'config': invite.toMap()}
    ];
    await tester.pumpWidget(MaterialApp(
        home: CallBannerHost(
            controller: api, enabled: true, child: const SizedBox())));
    await tester.pump();
    expect(find.text('Join meeting'), findsOneWidget);
    expect(find.text('Invitation to join a meeting'), findsOneWidget);
    expect(find.text('Accept'), findsNothing);
    await tester.tap(find.text('Join meeting'));
    await tester.pump();
    expect(platform.methods.where((m) => m == 'acceptCall').length, 1);
    await tester.pumpWidget(const SizedBox());
    await platform.controller.close();
  });
}
