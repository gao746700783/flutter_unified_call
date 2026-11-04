# flutter_unified_call

An engine-agnostic incoming-call plugin for Android, iOS and Web. It coordinates
call state, user actions, platform notifications and event recovery. The host
application owns push transport, signaling and media connections.

Current version: `0.1.1`.

**Development version: not yet ready for production release.** See the
[documentation index](doc/README.md) for platform guides and the current
[implementation status](doc/IMPLEMENTATION_STATUS.md), including outstanding
work and verified platform coverage. Version `0.1.1` has been published to
pub.dev.

| Platform | Incoming UI | Background integration |
| --- | --- | --- |
| Android (API 24+) | Self-managed Telecom on API 26+, CallStyle on API 31+, dedicated lock-screen Activity and notification fallback | Native push entry, durable inbox, inexact expiry alarm; accepted Telecom calls use a phoneCall foreground service |
| iOS (13+) | Actionable UserNotifications and shared foreground Banner; no CallKit/PushKit | Host APNs/SDK integration; durable inbox and resume reconciliation |
| Web | Shared foreground Banner; optional host-owned Service Worker notifications | IndexedDB transactions, BroadcastChannel, notification-click persistence and page recovery |

Notification delivery, full-screen presentation and background execution remain
subject to OS/browser permissions and policies. iOS/Web do not guarantee an
incoming system call screen or automatic foreground launch.

## Installation

Add the package to your Flutter application's `pubspec.yaml`:

```yaml
dependencies:
  flutter_unified_call: ^0.1.1
```

Then run `flutter pub get`. The package does not provide push transport,
signaling, or a media engine; connect those responsibilities in the host app.

## Quick start

Initialize once after Flutter binding setup, subscribe to events and restore the
pending inbox before receiving calls. Request notification permissions from an
explicit user action. The [complete example](example/lib/main.dart) handles
initialization errors, replay/live-event ordering, acknowledgment and UI recovery.

```dart
import 'package:flutter/material.dart';
import 'package:flutter_unified_call/flutter_unified_call.dart';

Future<void> displayIncomingCall() async {
  final calls = FlutterUnifiedCall.instance;
  try {
    await calls.showIncomingCall(IncomingCallConfig(
      callId: 'server-generated-unique-call-id',
      callerName: 'Alice',
      expiresAt: DateTime.now().toUtc().add(const Duration(seconds: 30)),
      mediaType: CallMediaType.audio,
      extra: {'roomId': 'room-from-signaling'},
    ));
  } catch (error, stack) {
    FlutterError.reportError(FlutterErrorDetails(exception: error, stack: stack));
  }
}
```

The expiry should normally come from your server message. A delayed push cannot
restart the ringing window. `extra` accepts immutable JSON-safe values, rather
than media engine objects. All commands return Futures and propagate failures.

## Initialize and recover events

Initialize once after `WidgetsFlutterBinding.ensureInitialized()`. Subscribe to
live events before reading the durable inbox so an event arriving during startup
is not missed. The live stream and inbox may both contain the same `eventId`;
serialize handling, make business side effects idempotent, and acknowledge only
after handling succeeds. If handling fails, leave the event unacknowledged so it
can be retried.

```dart
import 'dart:async';
import 'package:flutter_unified_call/flutter_unified_call.dart';

Future<StreamSubscription<CallEvent>> initializeCallEvents() async {
  final calls = FlutterUnifiedCall.instance;
  Future<void> eventQueue = Future<void>.value();

  void enqueue(CallEvent event) {
    eventQueue = eventQueue.then((_) async {
      await handleCallEventIdempotently(event); // Use event.eventId as the durable key.
      await calls.acknowledgeEvent(event.eventId);
    }).catchError((Object error, StackTrace stack) {
      reportCallEventError(error, stack); // Failed events remain unacknowledged.
    });
  }

  final subscription = calls.onEvent.listen(enqueue,
    onError: (Object error, StackTrace stack) => reportCallEventError(error, stack));
  await calls.initialize();
  for (final event in await calls.getPendingEvents()) {
    enqueue(event);
  }
  await eventQueue;

  // The caller owns this subscription and should cancel it on disposal.
  return subscription;
}
```

Replace `handleCallEventIdempotently` and `reportCallEventError` with the host
application's business handler and error reporter. Do not acknowledge an event
when its business operation fails. Use
`getActiveCalls()` separately to restore visible call state; it does not consume
the event inbox.

## Initialization options

Pass platform-specific settings to `initialize()` as needed. See the matching
platform guide for host setup and runtime constraints.

| Option | Default | Purpose |
| --- | --- | --- |
| `acceptText`, `declineText` | `Accept`, `Decline` | Labels used by platform call actions and the default banner. |
| `enableTelecom` | `true` | Enables Android self-managed Telecom integration. |
| `androidForegroundBanner` | `false` | Uses the in-app banner instead of the Android incoming notification while foregrounded. Mount `CallBannerHost(enabled: true)` too. |
| `androidRingtoneName` | `null` | Android raw resource name without extension; see the [Android guide](doc/ANDROID_RECOVERY.md). |
| `iosEnableRingtone` | `true` | Enables plugin ringtone playback and local notification sounds on iOS. |
| `iosRingtoneFileName` | `null` | Optional iOS main-bundle CAF, WAV, or AIFF sound; see the [banner guide](doc/FOREGROUND_BANNER.md). |
| `iosTimeSensitive` | `false` | Requests time-sensitive notification delivery on supported iOS versions. User settings and entitlements still apply. |
| `webEnableRingtone` | `true` | Enables foreground Web ringtone playback. A user gesture may be required to unlock audio. |
| `webRingtoneUrl` | `null` | Optional Web audio URL; otherwise the built-in tone is used. |
| `webUseServiceWorker` | `false` | Routes Web operations through the host-owned Worker adapter. |
| `webLaunchUrl` | `null` | Same-origin URL used to open the app from a Worker notification action. |

See the [Web guide](doc/WEB_WORKER.md) for Worker installation, payload format,
and browser requirements. The plugin never installs or replaces a host Worker.

## Call lifecycle

The plugin tracks user and platform call state, but it does not join a media
session. `acceptCall` records user intent. Call `setCallConnected` only after the
host media engine confirms a successful connection. Use `endCall` with a typed
`CallEndReason`, or `endAllCalls` to clear active calls.

```text
ringing ── acceptCall ──> accepted ── media connected ──> connected
   │                         │                              │
   └── decline/expiry ────────┴──────── endCall ────────────> ended
```

An expired or ended call cannot be revived by a duplicate incoming message with
the same ID. Valid duplicate deliveries merge top-level `extra` keys while
preserving the original caller, expiry, and state; they do not emit another
incoming event or ring again. Keep call IDs unique for each logical call.

Place `CallBannerHost` inside MaterialApp (for example in its builder). It is
active by default on iOS/Web and supports custom card builders, localized action
labels, busy state, retry UI and foreground restoration. See the
[Banner guide](doc/FOREGROUND_BANNER.md).

## State and events

The main path is `ringing → accepted → connected → ended`. Only your media-engine
success callback should call `setCallConnected`. Acceptance represents user intent.
Decline, timeout and cancellation end ringing calls; ended calls cannot be revived
by a duplicate incoming message with the same call ID. Valid duplicate deliveries
merge top-level `extra` keys (new values overwrite, including null), while retaining
the original caller, expiry and state. They do not ring again or emit another
`incoming` event. Historical inbox events stay unchanged; subsequent actions carry
the merged payload.

- `onEvent` provides live events, including system notification/hardware actions.
- `getPendingEvents()` returns durable unacknowledged events.
- Subscribe first, then read the inbox. Both may deliver the same `eventId`.
- Make business side effects idempotent, then call `acknowledgeEvent(eventId)`.
- `getActiveCalls()` restores current state without acknowledging business events.
- Android Telecom audio endpoints can be queried and requested; see the
  [audio bridge guide](doc/ANDROID_AUDIO.md).
- `endCall` accepts a typed reason; `endAllCalls` clears active calls.
- `checkPermissions` reports notification, full-screen and Telecom access separately
  using `CallPermissionState` enums (compare with `CallPermissionState.granted`).

The plugin does not promise exactly-once signaling or media joins. Keep durable
business idempotency records in your application when processing external actions.

## Platform setup and detailed guides

- See [Platform setup and detailed guides](doc/README.md) for Android, iOS, Web,
  audio routing and foreground banner documentation.

Android merges notification, full-screen, own-call and phoneCall foreground-service
permissions. Review them in the host manifest. Android 14+ full-screen access uses
a separate settings flow; it is not granted by the notification permission dialog.
No microphone/camera permissions or media SDKs are added by this plugin.

Use `checkPermissions()` to inspect notification, full-screen intent, and Telecom
access independently. Call `requestPermissions()` from an explicit user action;
some settings, such as Android full-screen access, require a separate settings
screen and can return `settingsRequired`. Unsupported permissions are reported as
`unsupported`. On Web, notification permission requires a secure context and a
user gesture. See the platform guides for host manifest, delegate, APNs, and
Service Worker setup.

## API map

- `FlutterUnifiedCall`: initialization, incoming calls, state commands, recovery,
  permissions, and Android Telecom audio routing.
- `IncomingCallConfig`: immutable call details and JSON-safe `extra` data.
- `CallEvent` and `CallSnapshot`: durable event records and current platform state.
- `CallPermissionStatus`: independent permission results per capability.
- `CallBannerHost`: optional foreground UI for ringing calls; enabled by default
  on iOS and Web, and opt-in on Android.

The public declarations include DartDoc. Platform-specific options and limitations
are described in the [documentation index](doc/README.md).

## Verification

Automated coverage includes Dart model/Widget tests, Android Robolectric state,
recovery and Telecom/service tests, a Swift store regression executable, native
integration tests, and real Chrome tests against the compiled Flutter app in two
tabs with foreground and Worker routing. See the status document for actual tested
versions and remaining hardware/push/permission gaps.

Run `flutter analyze` and `flutter test` from the package root. Android unit tests
run from `example/android` with
`./gradlew :flutter_unified_call:testDebugUnitTest`. Browser test instructions are
in the Web guide. Tests require the corresponding local SDKs/devices.

## License

Apache-2.0. See [LICENSE](LICENSE).
