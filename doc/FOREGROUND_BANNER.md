# Foreground banner

`CallBannerHost` is shared by iOS and Web. It is enabled on those platforms by
default; `enabled: true` also enables it on Android for applications that choose
an in-app UI. Place it inside `MaterialApp.builder` or around a page inside the
MaterialApp. Initialize the controller before receiving calls.

```dart
MaterialApp(
  builder: (context, child) => CallBannerHost(
    controller: FlutterUnifiedCall.instance,
    acceptText: 'Accept',
    declineText: 'Decline',
    onError: (error, stack) {
      FlutterError.reportError(FlutterErrorDetails(exception: error, stack: stack));
    },
    child: child ?? const SizedBox.shrink(),
  ),
  // ... existing code ...
)
```

The host subscribes to events and queries `getActiveCalls()` on attachment,
events and foreground resume. Only unexpired ringing calls are shown. Loading
keeps the existing child visible, an empty state removes the banner, and errors
show a retry affordance while being reported through `onError` (or Flutter's
error reporter). Commands disable the default action buttons while pending.
Older snapshot responses cannot overwrite newer state. An explicit local expiry
timer also removes the UI if the platform event is delayed.

The host never reads or acknowledges the business event inbox. The application
must subscribe to events, reconcile pending events, handle business actions and
explicitly acknowledge them. See the example for a serialized replay/live-event
queue, recoverable errors and manual media-connected simulation.

A `builder` can replace each call card. It receives `(context, call, busy, accept,
decline)`; custom controls should disable themselves when `busy` is true. The
underlying command also rejects duplicate concurrent actions for that call.
The child application remains visible and interactive below the banner. Multiple
ringing calls are ordered by expiry and displayed in a scrollable region.

## iOS sound

While active, iOS uses a looped AVAudioPlayer for ringing and suppresses the
system notification sound/banner to avoid duplicate presentation. The default is
an original generated PCM tone. Set iosRingtoneFileName on initialize to a
main-bundle caf, wav or aiff resource of less than 30 seconds to share a custom
sound with background local notifications. Missing/invalid configuration is
rejected before saving. iosEnableRingtone: false disables foreground playback
and sounds on plugin-created local notifications; remote alert push sound remains
controlled by the server payload.

Acceptance, terminal state and expiry stop foreground playback. Activity/scene
resignation stops it; active recovery resumes only unexpired ringing calls. On
background entry, still-ringing calls are re-posted as system local notifications.
This does not grant background audio execution or guarantee notification delivery.
Playback failure becomes a durable platformError with ringtone metadata and does
not mark the call connected. Reinitialization resets a failed playback attempt.

The plugin never explicitly changes or activates AVAudioSession. The external
media engine owns session configuration. Ambient/silent-switch/device policies
and actual audible output/cancellation require physical-device verification.
