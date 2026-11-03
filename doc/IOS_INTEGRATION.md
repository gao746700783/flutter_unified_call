# iOS integration (implementation in progress)

The iOS implementation uses UserNotifications, with no CallKit or PushKit.
It requires iOS 13 or newer. The current example uses CocoaPods because the
local checkout directory and Dart package names differ, which conflicts with
the generated SwiftPM package override identity. SwiftPM consumers still need
separate verification.

## Notification delegate

Set `UNUserNotificationCenter.current().delegate = self` in your FlutterAppDelegate
at launch. For cold-start action recovery, route owned actions through the native
`FlutterUnifiedCallPlugin.handleNotificationAction` entry before calling super.
This entry does not require Flutter plugin registration or a Dart listener, and
reports processing/persistence errors through its completion callback. The
[example AppDelegate](../example/ios/Runner/AppDelegate.swift) shows the complete
integration, including implicit-engine registration.

```swift
override func userNotificationCenter(_ center: UNUserNotificationCenter,
  didReceive response: UNNotificationResponse,
  withCompletionHandler completionHandler: @escaping () -> Void) {
  if FlutterUnifiedCallPlugin.handleNotificationAction(
    identifier: response.actionIdentifier,
    userInfo: response.notification.request.content.userInfo,
    completion: { error in
      if let error = error { NSLog("Call action failed: %@", String(describing: error)) }
      completionHandler()
    }) { return }
  super.userNotificationCenter(center, didReceive: response,
    withCompletionHandler: completionHandler)
}
```

The method returns false without invoking completion for unowned notifications,
so the host can forward them to its usual notification SDKs. Do not forward an
owned response again after it returns true. If another SDK owns the notification
delegate, call the same native entry from that SDK's response callback and
preserve its unrelated-notification handling. The plugin never replaces an
existing notification delegate. Its ordinary Flutter lifecycle delegate remains
a fallback for hosts whose plugins are already registered when responses arrive.

The userInfo key `flutter_unified_call` identifies owned notifications and accepts
a JSON call object or its encoded string. Malformed owned payloads return an error.

Call `initialize` before receiving calls to register the notification category
`flutter_unified_call.incoming`. The category is merged with existing categories.
Its actions are `accepted` (foreground) and `declined` (background). Tapping the
notification body opens the app without accepting a call.

## Push transport

The host owns APNs registration and server delivery. For an alert push, include
`aps.category = flutter_unified_call.incoming` and the complete call configuration
under `flutter_unified_call`. `expiresAt` is UTC epoch milliseconds. The config
also includes `callId`, `callerName`, `mediaType` (`audio` or `video`) and JSON
object `extra`. Notification content and sound in a remote alert are supplied
by the server. A foreground notification is ingested into the local event inbox.

For a host push SDK's native callback, Swift can call
`FlutterUnifiedCallPlugin.showIncomingCall(config, completion: ...)`. This enters
the main queue and reports scheduling errors. It works without a running Dart
listener, but the notification category must already have been registered.
A registered Flutter background engine can call the same Dart API as the UI.
The plugin cannot grant background execution time or guarantee push delivery.

## Recovery and acknowledgment

Subscribe to `onEvent`, then read `getPendingEvents()`. Both paths can return
the same stable event ID; the host deduplicates by `eventId`. Persist or complete
the business operation before calling `acknowledgeEvent`. Do not connect media
again for an event already handled by the host. `accepted` records user intent;
call `setCallConnected` only after the external media engine connects.

State and events use one atomic JSON file in Application Support. Failed writes
leave the in-memory store unchanged and propagate an error to Dart/native callers.
Notification callback failures are logged; the inbox remains available for replay.

## Limits still requiring verification

- iOS can suspend timers. Expiry is checked at foreground recovery, inbox reads
  and acceptance; a delivered notification can remain visible while suspended.
- The shared CallBannerHost renders foreground calls while AVAudioPlayer loops
  a default PCM or configured bundled sound. Foreground system sound is suppressed.
  Activity/scene changes stop/resume only unexpired ringing calls, with local
  notification re-posting on background entry. Actual audible output, interruption
  behavior and locked-device transition still require physical-device testing.
- `iosTimeSensitive: true` requests a time-sensitive interruption level on iOS 15+;
  host entitlements and user settings still govern delivery.
- Physical-device APNs delivery, locked-device actions, cold-start callback timing,
  coexistence with other notification SDKs, and App Store archive validation are
  not yet verified. Simulator lifecycle tests do not prove these behaviors.

## Action recovery verification

Eight native XCTest cases passed, including an isolated runtime with no Dart
listeners: a valid action creates incoming/accepted durable events, duplicate
acceptance adds no second accepted event, payload survives reload, and expired
acceptance cannot create an active call. A public-entry ownership test confirms
unrelated notifications are not consumed. This proves the handler/store contract;
it does not prove an actual APNs-delivered cold-launch callback on a physical iPhone.
