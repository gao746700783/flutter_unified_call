import 'dart:convert';

/// Descriptive media type; the plugin never starts a media engine.
enum CallMediaType {
  /// Audio-only call.
  audio,

  /// Call that includes video.
  video,
}

/// Direction of the local call presentation.
enum CallDirection { incoming, outgoing }

/// Business scene; a meeting never exposes point-to-point hangup controls.
enum CallKind { pointToPoint, meeting }

/// Lifecycle state maintained by the plugin for a call.
enum CallState {
  /// A locally initiated point-to-point call is awaiting the peer.
  dialing,
  /// The call is awaiting an answer and has not expired.
  ringing,

  /// Incoming accepted, peer answered, or a locally initiated meeting is joining.
  accepted,

  /// The host reported that its media connection succeeded.
  connected,

  /// The call is terminal and cannot be revived by duplicate delivery.
  ended,
}

/// Types of events emitted by call state transitions and platform integrations.
enum CallEventType {
  /// A local outgoing call or meeting presentation was recorded.
  started,

  /// The peer answered an outgoing call; media is still connecting.
  connecting,
  /// A new incoming call was recorded.
  incoming,

  /// The user accepted a ringing call.
  accepted,

  /// The user declined a ringing call.
  declined,

  /// The call was marked connected by the host.
  connected,

  /// The call entered a terminal state.
  ended,

  /// A ringing call expired before it was answered.
  missed,

  /// The active Android Telecom audio route changed.
  audioRouteChanged,

  /// The available Android Telecom audio endpoints changed.
  audioEndpointsChanged,

  /// The system mute state changed during a Telecom call.
  muteToggled,

  /// A platform operation failed; inspect the event metadata for details.
  platformError,
}

/// Reasons a call can enter the ended state.
enum CallEndReason {
  /// An outgoing point-to-point call expired before connection.
  unanswered,
  /// The local user or host ended the call.
  localEnded,

  /// The remote peer ended the call.
  remoteEnded,

  /// The call was cancelled before it connected.
  cancelled,

  /// The user declined the incoming call.
  declined,

  /// The ringing call expired without an answer.
  missed,

  /// Call setup or an underlying platform operation failed.
  failed,
}

/// Immutable description of an incoming call.
///
/// [expiresAt] is an absolute time and is normalized to UTC. Values in [extra]
/// must be finite JSON-compatible data because the payload is persisted and may
/// cross a native or Web boundary.
class IncomingCallConfig {
  IncomingCallConfig({
    required this.callId,
    required this.callerName,
    this.callerHandle,
    this.mediaType = CallMediaType.audio,
    this.direction = CallDirection.incoming,
    CallKind? kind,
    required DateTime expiresAt,
    Map<String, Object?> extra = const {},
  }) : kind = kind ?? (extra['meeting'] == true ? CallKind.meeting : CallKind.pointToPoint),
       expiresAt = expiresAt.toUtc(),
       extra = _freezeMap(extra) {
    if (callId.trim().isEmpty || callerName.trim().isEmpty) {
      throw ArgumentError('callId and callerName must not be empty');
    }
  }

  /// Stable identifier unique to this logical call.
  final String callId;
  /// Display name presented by the platform UI.
  final String callerName;
  /// Optional handle shown instead of the default media subtitle.
  final String? callerHandle;
  /// Descriptive media type; it does not start a media engine.
  final CallMediaType mediaType;
  /// Explicit direction persisted with every event and snapshot.
  final CallDirection direction;
  /// Meeting versus point-to-point behavior; old extra.meeting is supported.
  final CallKind kind;
  /// Absolute expiry prevents a delayed push from starting a fresh timeout.
  final DateTime expiresAt;
  /// Immutable JSON-safe application data associated with this call.
  final Map<String, Object?> extra;

  /// Converts this configuration to the plugin's platform-neutral wire format.
  Map<String, Object?> toMap() => {
    'callId': callId, 'callerName': callerName, 'callerHandle': callerHandle,
    'mediaType': mediaType.name, 'expiresAt': expiresAt.millisecondsSinceEpoch,
    'extra': extra, 'direction': direction.name, 'kind': kind.name,
  };

  /// Creates a configuration from the plugin's platform-neutral wire format.
  factory IncomingCallConfig.fromMap(Map<String, Object?> map) => IncomingCallConfig(
    callId: map['callId'] as String,
    callerName: map['callerName'] as String,
    callerHandle: map['callerHandle'] as String?,
    mediaType: CallMediaType.values.byName(map['mediaType'] as String),
    direction: map['direction'] == null
        ? (map['outgoing'] == true || (map['extra'] as Map?)?['outgoing'] == true ? CallDirection.outgoing : CallDirection.incoming)
        : CallDirection.values.byName(map['direction'] as String),
    kind: map['kind'] == null ? (map['meeting'] == true ? CallKind.meeting : null) : CallKind.values.byName(map['kind'] as String),
    expiresAt: DateTime.fromMillisecondsSinceEpoch(map['expiresAt'] as int, isUtc: true),
    extra: map['extra'] == null ? const {} : Map<String, Object?>.from(map['extra'] as Map),
  );
}

/// A locally initiated point-to-point call or meeting presentation.
///
/// [expiresAt] is the setup deadline. It stops applying once connected.
/// A meeting uses joining UI and has no notification hangup action.
class OutgoingCallConfig extends IncomingCallConfig {
  OutgoingCallConfig({
    required super.callId,
    required String displayName,
    String? handle,
    super.mediaType = CallMediaType.audio,
    CallKind kind = CallKind.pointToPoint,
    required super.expiresAt,
    super.extra = const {},
  }) : super(
         callerName: displayName,
         callerHandle: handle,
         direction: CallDirection.outgoing,
         kind: kind,
       );
}

Map<String, Object?> _freezeMap(Map<String, Object?> value) {
  Object? freeze(Object? value) {
    if (value == null || value is String || value is bool || value is int) return value;
    if (value is double && value.isFinite) return value;
    if (value is List) return List<Object?>.unmodifiable(value.map(freeze));
    if (value is Map) {
      final converted = <String, Object?>{};
      for (final entry in value.entries) {
        final key = entry.key;
        if (key is! String) throw ArgumentError('JSON object keys must be strings');
        converted[key] = freeze(entry.value);
      }
      return Map<String, Object?>.unmodifiable(converted);
    }
    throw ArgumentError('extra must contain only finite JSON values');
  }
  // Validate the same wire format used by native stores.
  jsonEncode(value);
  return freeze(value) as Map<String, Object?>;
}

/// A durable or live event produced by a call transition or platform action.
///
/// Events use stable [eventId] values for replay deduplication. The host should
/// acknowledge an event only after its business operation succeeds.
class CallEvent {
  CallEvent({required this.eventId, required this.type, required this.call,
    required this.occurredAt, this.reason, Map<String, Object?> metadata = const {}})
      : metadata = _freezeMap(metadata);
  /// Stable identifier used to deduplicate replay and live delivery.
  final String eventId;
  /// Kind of event that occurred.
  final CallEventType type;
  /// Call configuration captured with this event.
  final IncomingCallConfig call;
  /// UTC time when this event was recorded.
  final DateTime occurredAt;
  /// End reason when applicable.
  final CallEndReason? reason;
  /// Event-specific platform or transition details.
  final Map<String, Object?> metadata;
  /// The identifier of [call].
  String get callId => call.callId;

  /// Converts this event to the plugin's platform-neutral wire format.
  Map<String, Object?> toMap() => {
    'eventId': eventId, 'type': type.name, 'call': call.toMap(),
    'occurredAt': occurredAt.millisecondsSinceEpoch, 'reason': reason?.name,
    'metadata': metadata,
  };
  /// Creates an event from the plugin's platform-neutral wire format.
  factory CallEvent.fromMap(Map<String, Object?> map) => CallEvent(
    eventId: map['eventId'] as String,
    type: CallEventType.values.byName(map['type'] as String),
    call: IncomingCallConfig.fromMap(Map<String, Object?>.from(map['call'] as Map)),
    occurredAt: DateTime.fromMillisecondsSinceEpoch(map['occurredAt'] as int, isUtc: true),
    reason: map['reason'] == null ? null : CallEndReason.values.byName(map['reason'] as String),
    metadata: Map<String, Object?>.from(map['metadata'] as Map? ?? const {}),
  );
}

enum CallPermissionState {
  /// The capability is available to the application.
  granted,

  /// The user denied access.
  denied,

  /// The user has not yet made a permission choice.
  notDetermined,

  /// The current platform does not provide this capability.
  unsupported,

  /// The user must change a system setting to grant access.
  settingsRequired,

  /// The platform returned a value this SDK version does not recognize.
  unknown,
}

/// Permission results for capabilities managed independently by each platform.
///
/// A state may be [CallPermissionState.unsupported] on a platform that does not
/// provide the capability, or [CallPermissionState.settingsRequired] when the
/// user must change a system setting.
class CallPermissionStatus {
  const CallPermissionStatus({required this.notifications, required this.fullScreenIntent, required this.telecom});
  /// Notification display permission state.
  final CallPermissionState notifications;
  /// Full-screen incoming call presentation permission state.
  final CallPermissionState fullScreenIntent;
  /// Self-managed Telecom permission or availability state.
  final CallPermissionState telecom;
  /// Creates permission results from the platform-neutral wire format.
  factory CallPermissionStatus.fromMap(Map<String, Object?> map) => CallPermissionStatus(
    notifications: _permission(map['notifications']),
    fullScreenIntent: _permission(map['fullScreenIntent']),
    telecom: _permission(map['telecom']),
  );
}

CallPermissionState _permission(Object? value) {
  if (value is! String) throw const FormatException('Permission state must be a string');
  return CallPermissionState.values.firstWhere((state) => state.name == value,
      orElse: () => CallPermissionState.unknown);
}

/// Current platform-owned state, separate from the durable event inbox.
class CallSnapshot {
  const CallSnapshot({required this.config, required this.state});
  /// Call configuration for the current state.
  final IncomingCallConfig config;
  /// Current lifecycle state maintained by the plugin.
  final CallState state;
  /// Creates a snapshot from the platform-neutral wire format.
  factory CallSnapshot.fromMap(Map<String, Object?> map) => CallSnapshot(
    config: IncomingCallConfig.fromMap(Map<String, Object?>.from(map['config'] as Map)),
    state: CallState.values.byName(map['state'] as String),
  );
}


/// System audio routes that may be available on a live Android Telecom call.
enum CallAudioRoute {
  /// The device earpiece.
  earpiece,

  /// The device speaker.
  speaker,

  /// A Bluetooth audio device.
  bluetooth,

  /// A wired headset.
  wiredHeadset,

  /// A system-reported streaming route.
  streaming,

  /// A route not classified by the current SDK.
  unknown,
}

/// An available audio output reported by Android Telecom.
///
/// The [id] is opaque. Pass it unchanged to `FlutterUnifiedCall.requestAudioEndpoint`.
/// This API is unsupported on iOS,
/// Web, and Android calls without a live Telecom connection.
class CallAudioEndpoint {
  const CallAudioEndpoint({required this.id, required this.label, required this.route});
  /// Opaque platform identifier for requesting this endpoint.
  final String id;
  /// Human-readable label for display.
  final String label;
  /// Best-effort route classification reported by the platform.
  final CallAudioRoute route;
  /// Creates an endpoint from the platform-neutral wire format.
  factory CallAudioEndpoint.fromMap(Map<String, Object?> map) => CallAudioEndpoint(
    id: map['id'] as String,
    label: map['label'] as String,
    route: CallAudioRoute.values.firstWhere((r) => r.name == map['route'], orElse: () => CallAudioRoute.unknown),
  );
}
