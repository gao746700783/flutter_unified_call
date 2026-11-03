import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_unified_call/flutter_unified_call.dart';
import 'package:flutter_unified_call/flutter_unified_call_platform_interface.dart';

class BannerPlatform extends FlutterUnifiedCallPlatform {
  final controller = StreamController<Map<String, Object?>>.broadcast();
  final commands = <String>[];
  List<Map<String, Object?>> active = [];
  Future<Object?> Function()? snapshot;
  Completer<void>? action;
  Object? error;
  @override
  Stream<Map<String, Object?>> get events => controller.stream;
  @override
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]) async {
    commands.add(method);
    if (method == 'getActiveCalls') {
      if (error case final failure?) throw failure;
      return snapshot == null ? active.toList() : snapshot!();
    }
    if (method == 'acceptCall' || method == 'declineCall') {
      if (action != null) await action!.future;
      active.removeWhere((c) => (c['config'] as Map)['callId'] == arguments!['callId']);
    }
    return null;
  }
}

void main() {
  late BannerPlatform platform;
  late FlutterUnifiedCall api;
  late IncomingCallConfig config;
  setUp(() {
    platform = BannerPlatform();
    FlutterUnifiedCallPlatform.instance = platform;
    api = FlutterUnifiedCall();
    config = IncomingCallConfig(callId: 'one', callerName: 'Alice',
      expiresAt: DateTime.now().add(const Duration(minutes: 5)));
    platform.active = [{'config': config.toMap(), 'state': 'ringing'}];
  });
  tearDown(() async { await platform.controller.close(); });
  Widget host({CallBannerBuilder? builder, void Function(Object, StackTrace)? onError}) => MaterialApp(
    home: CallBannerHost(controller: api, enabled: true, builder: builder,
      onError: onError, child: const Scaffold(body: Text('Application'))));
  void emit(CallEventType type) => platform.controller.add(CallEvent(eventId: 'event', type: type,
    call: config, occurredAt: DateTime.now()).toMap());

  testWidgets('restores ringing call without consuming inbox and accepts once while busy', (tester) async {
    await tester.pumpWidget(host()); await tester.pump();
    expect(find.text('Alice'), findsOneWidget);
    expect(platform.commands, ['getActiveCalls']);
    platform.action = Completer<void>();
    await tester.tap(find.text('Accept')); await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    await tester.tap(find.text('Accept')); await tester.pump();
    expect(platform.commands.where((c) => c == 'acceptCall').length, 1);
    platform.action!.complete(); await tester.pump(); await tester.pump();
    expect(find.text('Alice'), findsNothing);
    expect(platform.commands.contains('acknowledgeEvent'), isFalse);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('late initial snapshot cannot revive a call after an event', (tester) async {
    final stale = Completer<Object?>();
    platform.snapshot = () => stale.future;
    await tester.pumpWidget(host());
    platform.snapshot = () async => [];
    emit(CallEventType.ended); await tester.pump();
    stale.complete([{'config': config.toMap(), 'state': 'ringing'}]);
    await tester.pump();
    expect(find.text('Alice'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('reports read error, retries and supports custom decline builder', (tester) async {
    final errors = <Object>[];
    platform.error = StateError('Storage unavailable');
    await tester.pumpWidget(host(onError: (error, _) => errors.add(error),
      builder: (context, call, busy, accept, decline) => Material(child:
        TextButton(onPressed: busy ? null : decline, child: Text('Reject ${call.callerName}')))));
    await tester.pump();
    expect(find.text('Unable to update call'), findsOneWidget);
    expect(errors.single, isA<StateError>());
    platform.error = null;
    await tester.tap(find.text('Retry')); await tester.pump();
    expect(find.text('Unable to update call'), findsNothing);
    await tester.tap(find.text('Reject Alice')); await tester.pump(); await tester.pump();
    expect(platform.commands, contains('declineCall'));
    expect(find.text('Reject Alice'), findsNothing);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('expired and accepted calls are hidden, resume reloads platform state', (tester) async {
    platform.active = [
      {'config': IncomingCallConfig(callId: 'expired', callerName: 'Expired',
        expiresAt: DateTime.now().subtract(const Duration(seconds: 1))).toMap(), 'state': 'ringing'},
      {'config': config.toMap(), 'state': 'accepted'},
    ];
    await tester.pumpWidget(host()); await tester.pump();
    expect(find.text('Alice'), findsNothing);
    expect(find.text('Expired'), findsNothing);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.hidden);
    platform.active = [{'config': config.toMap(), 'state': 'ringing'}];
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pump();
    expect(find.text('Alice'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
}
