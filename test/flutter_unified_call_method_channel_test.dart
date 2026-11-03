import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_unified_call/flutter_unified_call_method_channel.dart';
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('native failures propagate to host application', () async {
    final platform = MethodChannelFlutterUnifiedCall();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(platform.methodChannel,
      (_) async => throw PlatformException(code: 'permission_denied'));
    await expectLater(platform.invoke('showIncomingCall'), throwsA(isA<PlatformException>()));
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(platform.methodChannel, null);
  });
}
