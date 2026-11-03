import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'flutter_unified_call_method_channel.dart';

abstract class FlutterUnifiedCallPlatform extends PlatformInterface {
  FlutterUnifiedCallPlatform() : super(token: _token);
  static final Object _token = Object();
  static FlutterUnifiedCallPlatform _instance = MethodChannelFlutterUnifiedCall();
  static FlutterUnifiedCallPlatform get instance => _instance;
  static set instance(FlutterUnifiedCallPlatform value) {
    PlatformInterface.verifyToken(value, _token);
    _instance = value;
  }
  Stream<Map<String, Object?>> get events;
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]);
}
