import 'package:flutter/services.dart';
import 'flutter_unified_call_platform_interface.dart';

class MethodChannelFlutterUnifiedCall extends FlutterUnifiedCallPlatform {
  final methodChannel = const MethodChannel('flutter_unified_call');
  final eventChannel = const EventChannel('flutter_unified_call/events');
  late final Stream<Map<String, Object?>> _events = eventChannel.receiveBroadcastStream()
      .map((event) => Map<String, Object?>.from(event as Map));
  @override
  Stream<Map<String, Object?>> get events => _events;
  @override
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]) =>
      methodChannel.invokeMethod<Object?>(method, arguments);
}
