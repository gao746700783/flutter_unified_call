import 'dart:convert';

/// Media is descriptive; this plugin never starts a media engine.
enum CallMediaType { audio, video }
enum CallState { ringing, accepted, connected, ended }
enum CallEventType { incoming, accepted, declined, connected, ended, missed, audioRouteChanged, audioEndpointsChanged, muteToggled, platformError }
enum CallEndReason { localEnded, remoteEnded, cancelled, declined, missed, failed }

/// Immutable, JSON-safe payload suitable for native persistence and Web Push.
class IncomingCallConfig {
  IncomingCallConfig({
    required this.callId,
    required this.callerName,
    this.callerHandle,
    this.mediaType = CallMediaType.audio,
    required DateTime expiresAt,
    Map<String, Object?> extra = const {},
  }) : expiresAt = expiresAt.toUtc(),
       extra = _freezeMap(extra) {
    if (callId.trim().isEmpty || callerName.trim().isEmpty) {
      throw ArgumentError('callId and callerName must not be empty');
    }
  }

  final String callId;
  final String callerName;
  final String? callerHandle;
  final CallMediaType mediaType;
  /// Absolute expiry prevents a delayed push from starting a fresh timeout.
  final DateTime expiresAt;
  final Map<String, Object?> extra;

  Map<String, Object?> toMap() => {
    'callId': callId, 'callerName': callerName, 'callerHandle': callerHandle,
    'mediaType': mediaType.name, 'expiresAt': expiresAt.millisecondsSinceEpoch,
    'extra': extra,
  };

  factory IncomingCallConfig.fromMap(Map<String, Object?> map) => IncomingCallConfig(
    callId: map['callId'] as String,
    callerName: map['callerName'] as String,
    callerHandle: map['callerHandle'] as String?,
    mediaType: CallMediaType.values.byName(map['mediaType'] as String),
    expiresAt: DateTime.fromMillisecondsSinceEpoch(map['expiresAt'] as int, isUtc: true),
    extra: map['extra'] == null ? const {} : Map<String, Object?>.from(map['extra'] as Map),
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

class CallEvent {
  CallEvent({required this.eventId, required this.type, required this.call,
    required this.occurredAt, this.reason, Map<String, Object?> metadata = const {}})
      : metadata = _freezeMap(metadata);
  final String eventId;
  final CallEventType type;
  final IncomingCallConfig call;
  final DateTime occurredAt;
  final CallEndReason? reason;
  final Map<String, Object?> metadata;
  String get callId => call.callId;
  Map<String, Object?> toMap() => {
    'eventId': eventId, 'type': type.name, 'call': call.toMap(),
    'occurredAt': occurredAt.millisecondsSinceEpoch, 'reason': reason?.name,
    'metadata': metadata,
  };
  factory CallEvent.fromMap(Map<String, Object?> map) => CallEvent(
    eventId: map['eventId'] as String,
    type: CallEventType.values.byName(map['type'] as String),
    call: IncomingCallConfig.fromMap(Map<String, Object?>.from(map['call'] as Map)),
    occurredAt: DateTime.fromMillisecondsSinceEpoch(map['occurredAt'] as int, isUtc: true),
    reason: map['reason'] == null ? null : CallEndReason.values.byName(map['reason'] as String),
    metadata: Map<String, Object?>.from(map['metadata'] as Map? ?? const {}),
  );
}

enum CallPermissionState { granted, denied, notDetermined, unsupported, settingsRequired, unknown }

/// Independent permission outcomes, including unknown future platform values.
/// Kept separate because notification and full-screen access are independent.
class CallPermissionStatus {
  const CallPermissionStatus({required this.notifications, required this.fullScreenIntent, required this.telecom});
  final CallPermissionState notifications;
  final CallPermissionState fullScreenIntent;
  final CallPermissionState telecom;
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
  final IncomingCallConfig config;
  final CallState state;
  factory CallSnapshot.fromMap(Map<String, Object?> map) => CallSnapshot(
    config: IncomingCallConfig.fromMap(Map<String, Object?>.from(map['config'] as Map)),
    state: CallState.values.byName(map['state'] as String),
  );
}


/// Audio routing is exposed only for live Android Telecom connections.
enum CallAudioRoute { earpiece, speaker, bluetooth, wiredHeadset, streaming, unknown }
class CallAudioEndpoint {
  const CallAudioEndpoint({required this.id, required this.label, required this.route});
  final String id;
  final String label;
  final CallAudioRoute route;
  factory CallAudioEndpoint.fromMap(Map<String, Object?> map) => CallAudioEndpoint(
    id: map['id'] as String,
    label: map['label'] as String,
    route: CallAudioRoute.values.firstWhere((r) => r.name == map['route'], orElse: () => CallAudioRoute.unknown),
  );
}
