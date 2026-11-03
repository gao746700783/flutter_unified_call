import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show kIsWeb, defaultTargetPlatform, TargetPlatform;
import 'package:flutter_unified_call/flutter_unified_call.dart';

void main() => runApp(const MaterialApp(home: CallDemo()));

/// Replace the "Mark media connected" command with your media engine callback.
class CallDemo extends StatefulWidget {
  const CallDemo({super.key});
  @override
  State<CallDemo> createState() => _CallDemoState();
}

class _CallDemoState extends State<CallDemo> {
  final api = FlutterUnifiedCall.instance;
  final _handled = <String>{};
  StreamSubscription<CallEvent>? _subscription;
  Future<void> _queue = Future.value();
  List<CallSnapshot> _active = [];
  String status = 'Initializing';
  bool _ready = false;
  bool _busy = false;
  int _snapshotGeneration = 0;
  @override
  void initState() {
    super.initState();
    _subscription = api.onEvent.listen(_enqueue, onError: (Object error, StackTrace stack) => _error(error));
    unawaited(_initialize());
  }
  void _error(Object error) {
    if (mounted) setState(() => status = 'Error: $error');
  }
  void _enqueue(CallEvent event) {
    // Serialize replay and live delivery. Host applications should persist their
    // business idempotency records if handling starts external network actions.
    _queue = _queue.then((_) async {
      if (!mounted) return;
      if (_handled.add(event.eventId)) {
        setState(() => status = '${event.type.name}: ${event.call.callerName}');
      }
      await api.acknowledgeEvent(event.eventId);
      await _refresh();
    }).catchError((Object error, StackTrace stack) { _error(error); });
  }
  Future<void> _initialize() async {
    try {
      await api.initialize(androidRingtoneName: 'demo_ringtone');
      final pending = await api.getPendingEvents();
      for (final event in pending) { _enqueue(event); }
      await _queue;
      await _refresh();
      if (mounted) setState(() { _ready = true; if (pending.isEmpty) status = 'Ready'; });
    } catch (error) { _error(error); }
  }
  Future<void> _refresh() async {
    final generation = ++_snapshotGeneration;
    final calls = await api.getActiveCalls();
    if (mounted && generation == _snapshotGeneration) setState(() => _active = calls);
  }
  Future<void> _run(Future<void> Function() command) async {
    if (_busy) return;
    setState(() => _busy = true);
    try { await command(); await _refresh(); }
    catch (error) { _error(error); }
    finally { if (mounted) setState(() => _busy = false); }
  }
  Future<void> _selectAudio(String callId) => _run(() async {
    final endpoints = await api.getAudioEndpoints(callId);
    if (!mounted) return;
    if (endpoints.isEmpty) { setState(() => status = 'No audio outputs available'); return; }
    final selected = await showModalBottomSheet<String>(context: context, builder: (sheetContext) =>
      SafeArea(child: ListView(shrinkWrap: true, children: [
        for (final endpoint in endpoints) ListTile(title: Text(endpoint.label),
          subtitle: Text(endpoint.route.name), onTap: () => Navigator.of(sheetContext).pop(endpoint.id)),
      ])));
    if (selected != null) await api.requestAudioEndpoint(callId, selected);
  });
  Future<void> showCall() => _run(() => api.showIncomingCall(IncomingCallConfig(
    callId: DateTime.now().microsecondsSinceEpoch.toString(), callerName: 'Alice',
    callerHandle: 'Demo caller', expiresAt: DateTime.now().add(const Duration(seconds: 30)),
    extra: {'roomId': 'demo-room'},
  )));
  @override
  void dispose() { unawaited(_subscription?.cancel()); super.dispose(); }
  @override
  Widget build(BuildContext context) => CallBannerHost(controller: api,
    onError: (error, _) => _error(error), child: Scaffold(
      appBar: AppBar(title: const Text('Unified Call')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Text(status),
        if (!_ready) ...[
          if (status.startsWith('Error:')) TextButton(onPressed: _initialize, child: const Text('Retry initialization'))
          else const LinearProgressIndicator(),
        ],
        Wrap(spacing: 12, runSpacing: 8, children: [
          ElevatedButton(onPressed: !_ready || _busy ? null : showCall, child: const Text('Simulate incoming call')),
          OutlinedButton(onPressed: !_ready || _busy ? null : () => _run(() async {
            final permissions = await api.requestPermissions();
            if (mounted) setState(() => status = 'Notifications: ${permissions.notifications.name}');
          }), child: const Text('Request notification permission')),
          if (kIsWeb) TextButton(onPressed: !_ready || _busy ? null : () => _run(api.unlockWebAudio),
            child: const Text('Enable incoming audio')),
          TextButton(onPressed: !_ready || _busy ? null : () => _run(() async {
            for (final event in await api.getPendingEvents()) { _enqueue(event); }
            await _queue;
          }), child: const Text('Retry pending events')),
        ]),
        const SizedBox(height: 16),
        if (_ready && _active.isEmpty) const Text('No active calls'),
        for (final call in _active) Card(child: Padding(padding: const EdgeInsets.all(16),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${call.config.callerName} — ${call.state.name}'),
            Wrap(spacing: 8, runSpacing: 8, children: [
              if (call.state == CallState.ringing) ...[
                TextButton(onPressed: _busy ? null : () => _run(() => api.acceptCall(call.config.callId)), child: const Text('Accept call')),
                TextButton(onPressed: _busy ? null : () => _run(() => api.declineCall(call.config.callId)), child: const Text('Decline call')),
              ],
              if (call.state == CallState.accepted) TextButton(
                onPressed: _busy ? null : () => _run(() => api.setCallConnected(call.config.callId)), child: const Text('Mark media connected')),
              if (!kIsWeb && defaultTargetPlatform == TargetPlatform.android && call.state != CallState.ringing)
                TextButton(onPressed: _busy ? null : () => _selectAudio(call.config.callId), child: const Text('Audio output')),
              TextButton(onPressed: _busy ? null : () => _run(() => api.endCall(call.config.callId)), child: const Text('End call')),
            ]),
          ]))),
      ]),
    ));
}
