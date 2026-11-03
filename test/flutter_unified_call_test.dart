import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_unified_call/flutter_unified_call.dart';
import 'package:flutter_unified_call/flutter_unified_call_platform_interface.dart';

class FakePlatform extends FlutterUnifiedCallPlatform {
  final controller = StreamController<Map<String, Object?>>.broadcast();
  final calls = <String>[];
  Object? response;
  Map<String, Object?>? lastArguments;
  @override
  Stream<Map<String, Object?>> get events => controller.stream;
  @override
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]) async {
    calls.add(method); lastArguments = arguments; return response;
  }
}
void main() {
  test('audio endpoint model preserves platform IDs and unknown future routes', () {
    final endpoint = CallAudioEndpoint.fromMap({'id': 'opaque-system-id', 'label': 'Car', 'route': 'futureRoute'});
    expect(endpoint.id, 'opaque-system-id');
    expect(endpoint.route, CallAudioRoute.unknown);
  });
  test('permission outcomes are typed and tolerate future values', () {
    final status = CallPermissionStatus.fromMap({
      'notifications': 'granted', 'fullScreenIntent': 'settingsRequired', 'telecom': 'futureStatus',
    });
    expect(status.notifications, CallPermissionState.granted);
    expect(status.fullScreenIntent, CallPermissionState.settingsRequired);
    expect(status.telecom, CallPermissionState.unknown);
    expect(() => CallPermissionStatus.fromMap({'notifications': null}), throwsFormatException);
  });
  test('config preserves absolute expiry and deeply freezes JSON extra', () {
    final source = <String, Object?>{'nested': <String, Object?>{'room': 'a'}};
    final config = IncomingCallConfig(callId: '1', callerName: 'Alice', expiresAt: DateTime.utc(2030), extra: source);
    (source['nested'] as Map)['room'] = 'b';
    expect((config.extra['nested'] as Map)['room'], 'a');
    expect(IncomingCallConfig.fromMap(config.toMap()).toMap(), config.toMap());
    expect(() => (config.extra['nested'] as Map)['room'] = 'c', throwsUnsupportedError);
  });
  test('rejects invalid identity and non JSON payloads', () {
    expect(() => IncomingCallConfig(callId: '', callerName: 'A', expiresAt: DateTime.utc(2030)), throwsArgumentError);
    expect(() => IncomingCallConfig(callId: '1', callerName: 'A', expiresAt: DateTime.utc(2030), extra: {'token': Object()}), throwsA(anything));
  });
  test('native codec nested maps survive event replay and remain immutable', () async {
    const codec = StandardMessageCodec();
    final original = CallEvent(eventId: 'native-event', type: CallEventType.audioEndpointsChanged,
      call: IncomingCallConfig(callId: 'native', callerName: 'Alice', expiresAt: DateTime.utc(2030),
        extra: {'nested': [{'nullable': null, 'room': 'one'}]}),
      occurredAt: DateTime.utc(2026), metadata: {'endpoints': [{'id': 'speaker', 'route': 'speaker'}]});
    final decoded = codec.decodeMessage(codec.encodeMessage(original.toMap())) as Map;
    final fake = FakePlatform();
    FlutterUnifiedCallPlatform.instance = fake;
    fake.response = [decoded];
    final event = (await FlutterUnifiedCall().getPendingEvents()).single;
    expect(event.toMap(), original.toMap());
    final endpoints = event.metadata['endpoints'] as List;
    expect(() => endpoints.add({}), throwsUnsupportedError);
    expect(() => (endpoints.single as Map)['id'] = 'changed', throwsUnsupportedError);
    expect(() => IncomingCallConfig(callId: 'bad', callerName: 'Alice', expiresAt: DateTime.utc(2030),
      extra: {'nested': <Object?, Object?>{1: 'invalid'}}), throwsA(anything));
    await fake.controller.close();
  });
  test('replayed event retains identity until explicit acknowledgment', () async {
    final fake = FakePlatform();
    FlutterUnifiedCallPlatform.instance = fake;
    final api = FlutterUnifiedCall();
    final config = IncomingCallConfig(callId: '1', callerName: 'Alice', expiresAt: DateTime.utc(2030));
    final event = CallEvent(eventId: 'stable-id', type: CallEventType.accepted, call: config, occurredAt: DateTime.utc(2026));
    fake.response = [event.toMap()];
    expect((await api.getPendingEvents()).single.eventId, 'stable-id');
    expect((await api.getPendingEvents()).single.eventId, 'stable-id');
    await api.acknowledgeEvent('stable-id');
    expect(fake.lastArguments, {'eventId': 'stable-id'});
    await fake.controller.close();
  });
}
