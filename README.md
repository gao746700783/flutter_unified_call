# flutter_unified_call

An engine-agnostic incoming-call plugin for Android, iOS and Web. It coordinates
call state, user actions, platform notifications and event recovery. The host
application owns push transport, signaling and media connections.

**Development version: not yet ready for production release.** See
[implementation status](doc/IMPLEMENTATION_STATUS.md) for outstanding work and
verified platform coverage. No package publication has been performed.

| Platform | Incoming UI | Background integration |
| --- | --- | --- |
| Android (API 24+) | Self-managed Telecom on API 26+, CallStyle on API 31+, dedicated lock-screen Activity and notification fallback | Native push entry, durable inbox, inexact expiry alarm; accepted Telecom calls use a phoneCall foreground service |
| iOS (13+) | Actionable UserNotifications and shared foreground Banner; no CallKit/PushKit | Host APNs/SDK integration; durable inbox and resume reconciliation |
| Web | Shared foreground Banner; optional host-owned Service Worker notifications | IndexedDB transactions, BroadcastChannel, notification-click persistence and page recovery |

Notification delivery, full-screen presentation and background execution remain
subject to OS/browser permissions and policies. iOS/Web do not guarantee an
incoming system call screen or automatic foreground launch.

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

Place `CallBannerHost` inside MaterialApp (for example in its builder). It is
active by default on iOS/Web and supports custom card builders, localized action
labels, busy state, retry UI and foreground restoration. See the
[Banner guide](doc/FOREGROUND_BANNER.md).

## State and events

The main path is `ringing → accepted → connected → ended`. Only your media-engine
success callback should call `setCallConnected`. Acceptance represents user intent.
Decline, timeout and cancellation end ringing calls; ended calls cannot be revived
by a duplicate incoming message with the same call ID.

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

## Platform setup

- [Android recovery, native push entry and foreground service](doc/ANDROID_RECOVERY.md).
- [iOS delegate forwarding, APNs payload and native SDK entry](doc/IOS_INTEGRATION.md).
- [Web host Worker setup, payload and browser requirements](doc/WEB_WORKER.md).

Android merges notification, full-screen, own-call and phoneCall foreground-service
permissions. Review them in the host manifest. Android 14+ full-screen access uses
a separate settings flow; it is not granted by the notification permission dialog.
No microphone/camera permissions or media SDKs are added by this plugin.

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
