import 'dart:async';
import 'dart:js_interop';
import 'dart:js_interop_unsafe';
import 'package:flutter/services.dart';
import 'package:flutter_web_plugins/flutter_web_plugins.dart';
import 'package:web/web.dart' as web;
import 'flutter_unified_call_platform_interface.dart';

@JS('UnifiedCallPage.invoke')
external JSPromise<JSAny?> _invokePage(JSString method, JSAny? arguments);

/// Web implementation of the plugin platform contract.
///
/// Page state and the optional host-owned Worker adapter share IndexedDB
/// transactions. Applications should use `FlutterUnifiedCall` rather than
/// constructing this implementation directly.
class FlutterUnifiedCallWeb extends FlutterUnifiedCallPlatform {
  /// Registers this implementation with Flutter's Web plugin registry.
  static void registerWith(Registrar registrar) {
    FlutterUnifiedCallPlatform.instance = FlutterUnifiedCallWeb();
  }
  static Future<void>? _scripts;
  final _events = StreamController<Map<String, Object?>>.broadcast();
  final Set<String> _seen = {};
  web.BroadcastChannel? _channel;
  Timer? _expiry;
  int _generation = 0;
  bool _attached = false;
  @override
  Stream<Map<String, Object?>> get events => _events.stream;

  static Future<void> _load(String name, String symbol) async {
    if (globalContext.has(symbol)) return;
    final completer = Completer<void>();
    final script = web.HTMLScriptElement()
      ..src = Uri.parse(web.document.baseURI).resolve('assets/packages/flutter_unified_call/web/$name').toString();
    script.onload = ((web.Event _) { completer.complete(); }).toJS;
    script.onerror = ((web.Event _) { completer.completeError(StateError('Failed to load $name')); }).toJS;
    final head = web.document.head;
    if (head == null) throw StateError('Document has no head for plugin scripts');
    head.appendChild(script);
    try { await completer.future.timeout(const Duration(seconds: 10)); }
    finally { script.onload = null; script.onerror = null; }
  }
  Future<void> _ensureReady() async {
    if (!web.window.isSecureContext || !globalContext.has('indexedDB') || !globalContext.has('BroadcastChannel')) {
      throw PlatformException(code: 'unsupported', message: 'Web calls require a secure context, IndexedDB and BroadcastChannel');
    }
    try {
      await (_scripts ??= () async {
        await _load('unified_call_core.js', 'UnifiedCallCore');
        await _load('unified_call_page.js', 'UnifiedCallPage');
      }());
    } catch (_) { _scripts = null; rethrow; }
    if (_attached) return;
    _attached = true;
    _channel = web.BroadcastChannel('flutter_unified_call_v2');
    _channel!.onmessage = ((web.MessageEvent event) {
      final payload = event.data.dartify();
      if (payload is Map && payload['type'] == 'unified-call-events') {
        _deliver(payload['events']);
        unawaited(_reschedule().catchError((Object error, StackTrace stack) => _events.addError(error, stack)));
      }
    }).toJS;
    web.document.addEventListener('visibilitychange', ((web.Event _) {
      if (web.document.visibilityState == 'visible') {
        unawaited(_reschedule().catchError((Object error, StackTrace stack) => _events.addError(error, stack)));
      }
    }).toJS);
  }
  void _deliver(Object? values) {
    for (final item in values as List? ?? const []) {
      final event = Map<String, Object?>.from(item as Map);
      if (_seen.add(event['eventId'] as String)) _events.add(event);
    }
    // Live delivery is best effort; the explicit event inbox is authoritative.
    while (_seen.length > 2048) { _seen.remove(_seen.first); }
  }
  Future<Map<String, Object?>> _raw(String method, Map<String, Object?> args) async {
    try {
      final value = (await _invokePage(method.toJS, args.jsify()).toDart).dartify();
      final result = Map<String, Object?>.from(value as Map);
      _deliver(result['events']);
      return result;
    } catch (error) { throw PlatformException(code: 'web_call_error', message: error.toString()); }
  }
  Future<void> _reschedule() async {
    final generation = ++_generation;
    final result = await _raw('getActiveCalls', {});
    if (generation != _generation) return;
    _expiry?.cancel();
    final calls = result['value'] as List? ?? const [];
    final expiries = calls.where((value) {
      final call = value as Map;
      final config = call['config'] as Map;
      return call['state'] == 'ringing' || (config['direction'] == 'outgoing' &&
          (call['state'] == 'dialing' || call['state'] == 'accepted'));
    })
        .map((c) => ((c as Map)['config'] as Map)['expiresAt'] as int).toList()..sort();
    if (expiries.isEmpty) return;
    final delay = DateTime.fromMillisecondsSinceEpoch(expiries.first).difference(DateTime.now());
    _expiry = Timer(delay.isNegative ? Duration.zero : delay, () {
      unawaited(_reschedule().catchError((Object error, StackTrace stack) => _events.addError(error, stack)));
    });
  }
  Map<String, Object?> _permissions() => {
    'notifications': !web.window.isSecureContext || !globalContext.has('Notification') ? 'unsupported' :
      web.Notification.permission == 'granted' ? 'granted' : web.Notification.permission == 'default' ? 'notDetermined' : 'denied',
    'fullScreenIntent': 'unsupported', 'telecom': 'unsupported',
  };
  @override
  Future<Object?> invoke(String method, [Map<String, Object?>? arguments]) async {
    if (method == 'getAudioEndpoints' || method == 'requestAudioEndpoint') {
      throw PlatformException(code: 'unsupported', message: 'Audio endpoints require Android Telecom');
    }
    if (method == 'unlockWebAudio') {
      if (!globalContext.has('UnifiedCallPage')) throw PlatformException(code: 'not_initialized', message: 'Initialize before unlocking audio');
      await _invokePage(method.toJS, <String, Object?>{}.jsify()).toDart;
      return null;
    }
    // Keep the permission request within the initiating user gesture.
    if (method == 'checkPermissions') return _permissions();
    if (method == 'requestPermissions') {
      if (web.window.isSecureContext && globalContext.has('Notification')) {
        try { await web.Notification.requestPermission().toDart; }
        catch (error) { throw PlatformException(code: 'web_permission_error', message: error.toString()); }
      }
      return _permissions();
    }
    if (method == 'openFullScreenIntentSettings') throw PlatformException(code: 'unsupported', message: 'Web has no full-screen call intent');
    await _ensureReady();
    final result = await _raw(method, arguments ?? {});
    await _reschedule();
    return result['value'];
  }
}
