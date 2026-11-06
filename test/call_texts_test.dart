import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_unified_call/flutter_unified_call.dart';
import 'package:flutter_unified_call/flutter_unified_call_platform_interface.dart';

class TextPlatform extends FlutterUnifiedCallPlatform {
  final controller = StreamController<Map<String, Object?>>.broadcast();
  Map<String, Object?>? options;
  bool fail = false;
  @override
  Stream<Map<String, Object?>> get events => controller.stream;
  @override
  Future<Object?> invoke(String method,
      [Map<String, Object?>? arguments]) async {
    if (fail) throw StateError('Storage unavailable');
    if (method == 'initialize') options = arguments;
    if (method == 'getActiveCalls') {
      return [
        {
          'state': 'ringing',
          'config': IncomingCallConfig(
            callId: 'texts',
            callerName: 'Alice',
            expiresAt: DateTime.now().add(const Duration(minutes: 1)),
          ).toMap()
        }
      ];
    }
    return null;
  }
}

void main() {
  test('Chinese locales, English fallback and partial overrides', () {
    expect(const CallTexts().resolve('zh-Hant')['audioSubtitle'], '语音来电');
    expect(const CallTexts().resolve('fr')['acceptText'], 'Accept');
    final labels =
        const CallTexts(languageCode: 'en', acceptText: 'Join').resolve('zh');
    expect(labels['acceptText'], 'Join');
    expect(labels['videoSubtitle'], 'Incoming video call');
    expect(labels['retryText'], 'Retry');
    expect(labels['meetingNamePrefix'], 'Meeting name: ');
    expect(const CallTexts().resolve('zh')['meetingNamePrefix'], '会议名称：');
  });

  testWidgets(
      'initialize updates mounted banner and failed changes preserve labels',
      (tester) async {
    final platform = TextPlatform();
    FlutterUnifiedCallPlatform.instance = platform;
    final api = FlutterUnifiedCall();
    await tester.pumpWidget(MaterialApp(
        home: CallBannerHost(
      controller: api,
      enabled: true,
      child: const SizedBox(),
    )));
    await tester.pump();
    expect(find.text('Accept'), findsOneWidget);
    await api.initialize(
        texts: const CallTexts(languageCode: 'zh', acceptText: '加入', meetingNamePrefix: '主题：'));
    await tester.pump();
    expect(platform.options!['audioSubtitle'], '语音来电');
    expect(platform.options!['meetingNamePrefix'], '主题：');
    expect(platform.options!['customCallActions'], true);
    expect(find.text('加入'), findsOneWidget);
    expect(find.text('拒绝'), findsOneWidget);
    expect(find.text('语音来电'), findsOneWidget);
    platform.fail = true;
    await expectLater(
        api.initialize(texts: const CallTexts(languageCode: 'en')),
        throwsStateError);
    await tester.pump();
    expect(find.text('加入'), findsOneWidget);
    platform.fail = false;
    await api.initialize(
        texts: const CallTexts(languageCode: 'en'), acceptText: 'Answer');
    await tester.pump();
    expect(find.text('Answer'), findsOneWidget);
    expect(find.text('Decline'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await platform.controller.close();
  });

  testWidgets('banner translates error and retry labels', (tester) async {
    final platform = TextPlatform();
    FlutterUnifiedCallPlatform.instance = platform;
    final api = FlutterUnifiedCall();
    await api.initialize(
        texts: const CallTexts(languageCode: 'zh', retryText: '重新加载'));
    platform.fail = true;
    await tester.pumpWidget(MaterialApp(
        home: CallBannerHost(
      controller: api,
      enabled: true,
      onError: (error, stack) {},
      child: const SizedBox(),
    )));
    await tester.pump();
    expect(find.text('无法更新通话'), findsOneWidget);
    expect(find.text('重新加载'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    await platform.controller.close();
  });
}
