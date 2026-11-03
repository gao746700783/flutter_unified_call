import 'package:flutter/material.dart';
import 'package:flutter_unified_call/flutter_unified_call.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_unified_call_example/main.dart';
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('initializes and shows empty call state', (tester) async {
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(const MethodChannel('flutter_unified_call'), (call) async {
      if (call.method == 'getActiveCalls' || call.method == 'getPendingEvents') return [];
      return null;
    });
    messenger.setMockMethodCallHandler(const MethodChannel('flutter_unified_call/events'), (_) async => null);
    await tester.pumpWidget(const MaterialApp(home: CallDemo()));
    await tester.pumpAndSettle();
    expect(find.text('Simulate incoming call'), findsOneWidget);
    expect(find.text('Ready'), findsOneWidget);
    expect(find.text('No active calls'), findsOneWidget);
    await tester.pumpWidget(const SizedBox()); await tester.pump();
    messenger.setMockMethodCallHandler(const MethodChannel('flutter_unified_call'), null);
    messenger.setMockMethodCallHandler(const MethodChannel('flutter_unified_call/events'), null);
  });
  testWidgets('audio output handles empty list and submits opaque endpoint ID', (tester) async {
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    final config = IncomingCallConfig(callId: 'audio', callerName: 'Alice', expiresAt: DateTime.now().add(const Duration(minutes: 1)));
    var endpoints = <Map<String, Object?>>[];
    Object? requested;
    messenger.setMockMethodCallHandler(const MethodChannel('flutter_unified_call'), (call) async {
      if (call.method == 'getPendingEvents') return [];
      if (call.method == 'getActiveCalls') return [{'config': config.toMap(), 'state': 'accepted'}];
      if (call.method == 'getAudioEndpoints') return endpoints;
      if (call.method == 'requestAudioEndpoint') requested = call.arguments;
      return null;
    });
    messenger.setMockMethodCallHandler(const MethodChannel('flutter_unified_call/events'), (_) async => null);
    await tester.pumpWidget(const MaterialApp(home: CallDemo())); await tester.pumpAndSettle();
    await tester.tap(find.text('Audio output')); await tester.pumpAndSettle();
    expect(find.text('No audio outputs available'), findsOneWidget);
    endpoints = [{'id': 'system-opaque-id', 'label': 'Speaker device', 'route': 'speaker'}];
    await tester.tap(find.text('Audio output')); await tester.pumpAndSettle();
    await tester.tap(find.text('Speaker device')); await tester.pumpAndSettle();
    expect(requested, {'callId': 'audio', 'endpointId': 'system-opaque-id'});
    await tester.pumpWidget(const SizedBox()); await tester.pump();
    messenger.setMockMethodCallHandler(const MethodChannel('flutter_unified_call'), null);
    messenger.setMockMethodCallHandler(const MethodChannel('flutter_unified_call/events'), null);
  }, variant: TargetPlatformVariant.only(TargetPlatform.android));
}
