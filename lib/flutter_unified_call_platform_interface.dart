import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'flutter_unified_call_method_channel.dart';

/// Platform contract used by the public `FlutterUnifiedCall` controller.
///
/// Applications should normally use the package's registered implementation.
/// Platform extensions can replace [instance] with a class that extends this
/// interface and calls its constructor so platform verification can succeed.
abstract class FlutterUnifiedCallPlatform extends PlatformInterface {
  /// Creates an implementation with the package's platform verification token.
  FlutterUnifiedCallPlatform() : super(token: _token);
  static final Object _token = Object();
  static FlutterUnifiedCallPlatform _instance = MethodChannelFlutterUnifiedCall();
  /// The platform implementation currently used by the controller.
  static FlutterUnifiedCallPlatform get instance => _instance;

  /// Replaces the active implementation after validating its platform token.
  static set instance(FlutterUnifiedCallPlatform value) {
    PlatformInterface.verifyToken(value, _token);
    _instance = value;
  }

  /// Stream of platform event payloads before model conversion.
  Stream<Map<String, Object?>> get events;

  /// Invokes a platform operation with its optional argument map.
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]);
}
