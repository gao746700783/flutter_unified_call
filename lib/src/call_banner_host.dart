import 'dart:async';
import 'package:flutter/foundation.dart' show kIsWeb, defaultTargetPlatform;
import 'package:flutter/material.dart';
import '../flutter_unified_call.dart';

/// Builder for a custom incoming-call card in [CallBannerHost].
///
/// The [busy] flag is true while an accept or decline command is pending.
/// The callback receives the current [BuildContext], [IncomingCallConfig], and
/// accept/decline actions. Disable custom controls while busy to avoid duplicate
/// actions.
typedef CallBannerBuilder = Widget Function(BuildContext context,
    IncomingCallConfig call, bool busy, VoidCallback accept, VoidCallback decline);

/// Displays active ringing calls as an in-app banner.
///
/// Place this widget inside `MaterialApp.builder`. It is enabled by default on
/// iOS and Web; pass `enabled: true` to opt in on Android. It restores state from
/// [FlutterUnifiedCall.getActiveCalls] and never reads or acknowledges the host
/// application's durable event inbox. Subscribe to events and handle
/// acknowledgment separately.
class CallBannerHost extends StatefulWidget {
  const CallBannerHost({super.key, required this.child, this.controller,
    this.enabled, this.builder, this.acceptText = 'Accept', this.declineText = 'Decline',
    this.audioSubtitle = 'Incoming audio call', this.videoSubtitle = 'Incoming video call',
    this.onError});
  /// Application content displayed beneath the banner.
  final Widget child;
  /// Controller to use, or the shared [FlutterUnifiedCall.instance] by default.
  final FlutterUnifiedCall? controller;
  /// Whether the banner is enabled; defaults to iOS and Web only.
  final bool? enabled;
  /// Optional builder for each ringing call card.
  final CallBannerBuilder? builder;
  /// Localized label for the accept action.
  final String acceptText;
  /// Localized label for the decline action.
  final String declineText;
  /// Subtitle used for audio calls when no caller handle is provided.
  final String audioSubtitle;
  /// Subtitle used for video calls when no caller handle is provided.
  final String videoSubtitle;
  /// Receives state restoration and action errors for the banner.
  final void Function(Object, StackTrace)? onError;
  @override
  State<CallBannerHost> createState() => _CallBannerHostState();
}

class _CallBannerHostState extends State<CallBannerHost> with WidgetsBindingObserver {
  FlutterUnifiedCall get _api => widget.controller ?? FlutterUnifiedCall.instance;
  bool get _enabled => widget.enabled ?? (kIsWeb || defaultTargetPlatform == TargetPlatform.iOS);
  StreamSubscription<CallEvent>? _subscription;
  Timer? _expiry;
  List<CallSnapshot> _calls = const [];
  final Set<String> _busy = {};
  int _generation = 0;
  Object? _error;
  bool _foreground = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _foreground = WidgetsBinding.instance.lifecycleState == null ||
        WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed;
    _attach();
  }
  @override
  void didUpdateWidget(CallBannerHost oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller || oldWidget.enabled != widget.enabled) {
      unawaited(_subscription?.cancel());
      _subscription = null; _expiry?.cancel(); _calls = const []; _error = null; _busy.clear();
      _generation++;
      _attach();
    }
  }
  void _attach() {
    if (!_enabled) return;
    _subscription = _api.onEvent.listen((_) => unawaited(_refresh()),
      onError: (Object error, StackTrace stack) => _report(error, stack));
    unawaited(_refresh());
  }
  Future<void> _refresh() async {
    final generation = ++_generation;
    try {
      final calls = await _api.getActiveCalls();
      if (!mounted || generation != _generation) return;
      final now = DateTime.now();
      final ringing = calls.where((c) => c.state == CallState.ringing && c.config.expiresAt.isAfter(now)).toList()
        ..sort((a, b) => a.config.expiresAt.compareTo(b.config.expiresAt));
      _expiry?.cancel();
      setState(() { _calls = ringing; _error = null; });
      if (ringing.isNotEmpty) {
        _expiry = Timer(ringing.first.config.expiresAt.difference(now), () {
          if (!mounted) return;
          setState(() => _calls = _calls.where((c) => c.config.expiresAt.isAfter(DateTime.now())).toList());
          unawaited(_refresh());
        });
      }
    } catch (error, stack) {
      if (mounted && generation == _generation) _report(error, stack);
    }
  }
  void _report(Object error, StackTrace stack) {
    if (!mounted) return;
    setState(() => _error = error);
    if (widget.onError case final onError?) { onError(error, stack); }
    else { FlutterError.reportError(FlutterErrorDetails(exception: error, stack: stack, library: 'flutter_unified_call')); }
  }
  Future<void> _act(String id, bool accept) async {
    if (!_busy.add(id)) return;
    final controller = _api;
    setState(() {});
    try {
      if (accept) { await controller.acceptCall(id); }
      else { await controller.declineCall(id); }
      if (mounted && identical(controller, _api)) await _refresh();
    } catch (error, stack) { if (mounted && identical(controller, _api)) _report(error, stack); }
    finally { if (mounted && identical(controller, _api)) setState(() => _busy.remove(id)); }
  }
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    setState(() => _foreground = state == AppLifecycleState.resumed);
    if (_foreground && _enabled) unawaited(_refresh());
  }
  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _generation++; _expiry?.cancel(); unawaited(_subscription?.cancel());
    super.dispose();
  }
  @override
  Widget build(BuildContext context) {
    if (!_enabled || !_foreground) return widget.child;
    return Stack(children: [widget.child,
      if (_calls.isNotEmpty || _error != null) Positioned(top: 0, left: 0, right: 0,
        child: SafeArea(child: Align(alignment: Alignment.topCenter,
          child: ConstrainedBox(constraints: BoxConstraints(maxWidth: 560, maxHeight: MediaQuery.sizeOf(context).height * .5),
            child: ListView(shrinkWrap: true, padding: const EdgeInsets.all(12), children: [
              if (_error != null) Material(elevation: 8, borderRadius: BorderRadius.circular(12),
                child: ListTile(title: const Text('Unable to update call'),
                  trailing: TextButton(onPressed: () => unawaited(_refresh()), child: const Text('Retry')))),
              for (final snapshot in _calls) _banner(context, snapshot.config),
            ])),
        ))),
    ]);
  }
  Widget _banner(BuildContext context, IncomingCallConfig config) {
    final busy = _busy.contains(config.callId);
    void accept() => unawaited(_act(config.callId, true));
    void decline() => unawaited(_act(config.callId, false));
    if (widget.builder case final builder?) return builder(context, config, busy, accept, decline);
    return Padding(padding: const EdgeInsets.only(bottom: 8), child: Material(
      elevation: 8, borderRadius: BorderRadius.circular(16), clipBehavior: Clip.antiAlias,
      child: Padding(padding: const EdgeInsets.all(16), child: Column(mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(config.callerName, style: Theme.of(context).textTheme.titleLarge),
          Text(config.callerHandle ?? (config.mediaType == CallMediaType.video ? widget.videoSubtitle : widget.audioSubtitle)),
          const SizedBox(height: 12),
          Wrap(spacing: 12, runSpacing: 8, children: [
            OutlinedButton(onPressed: busy ? null : decline, child: Text(widget.declineText)),
            FilledButton(onPressed: busy ? null : accept, child: Text(widget.acceptText)),
            if (busy) const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)),
          ]),
        ])),
    ));
  }
}
