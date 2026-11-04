import 'package:flutter/services.dart';
import 'flutter_unified_call_platform_interface.dart';

/// Default platform implementation backed by Flutter method and event channels.
///
/// Applications should call `FlutterUnifiedCall` instead of invoking this class
/// directly.
class MethodChannelFlutterUnifiedCall extends FlutterUnifiedCallPlatform {
  /// Method channel used to invoke platform operations.
  final methodChannel = const MethodChannel('flutter_unified_call');
  /// Event channel used to receive platform event payloads.
  final eventChannel = const EventChannel('flutter_unified_call/events');
  late final Stream<Map<String, Object?>> _events = eventChannel.receiveBroadcastStream()
      .map((event) => Map<String, Object?>.from(event as Map));
  @override
  Stream<Map<String, Object?>> get events => _events;
  @override
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]) =>
      methodChannel.invokeMethod<Object?>(method, arguments);
}
