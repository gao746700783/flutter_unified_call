# Android recovery and timeout behavior

The first CallRuntime instance in each process reads persisted call state before
Dart listeners are required. Unexpired ringing calls regain a notification, an
in-process timeout and an AlarmManager PendingIntent. Expired ringing calls emit
one durable missed event. Accepted/connected sessions from the dead process end
with reason failed, because the plugin cannot recover an external media engine.
Creating another Flutter engine or calling initialize again in the same process
does not terminate live calls or duplicate recovery.

Recovered ringing notifications currently use the notification fallback rather
than recreating a Telecom connection. Fresh incoming calls still use Telecom when
enabled. Full Telecom recovery and device verification remain pending.

## Lock-screen incoming calls

For a ringing call, the notification's full-screen intent opens the plugin's
`IncomingCallActivity`, which shows the caller name, call state, and accept/decline
actions. The plugin declares `showWhenLocked` and `turnScreenOn` on the Activity
in the merged manifest and also sets the corresponding runtime APIs on API 27+;
API 24–26 use legacy window flags. Tapping the notification body continues to
open the host app's launcher activity. If a device or OEM blocks full-screen
launches, only the notification can be shown.

The system decides whether to launch the full-screen activity. The incoming
notification must remain enabled and use a high-importance channel. On Android
14+, the host app must also be allowed to use full-screen intents; check
`checkPermissions().fullScreenIntent` and direct the user to the system setting
when it reports `settingsRequired`. Without that access, Android may show a
heads-up notification instead of launching the activity. Some OEMs also require
their app-specific "Show on Lock Screen" setting. OEM policy, Do Not Disturb,
user settings, and device state can still prevent screen wake or alter lock-screen
presentation. The plugin cannot bypass those system decisions.

Timeouts use Handler while the process is alive, system notification timeout on
API 26+, and setAndAllowWhileIdle with an explicit non-exported receiver as an
additional process-independent reconciliation trigger. Accepting, connecting or
ending a call cancels its scheduled alarm. Acceptance and state reads also check
absolute expiry. Inexact alarms may be delayed by the OS and Doze; this is not an
exact 30-second background scheduling guarantee. Device force-stop, reboot and
OEM restrictions remain separate lifecycle limitations. No exact-alarm special
permission is requested.

Verification: seven Robolectric API 34 state/recovery tests passed, including
fresh-process ringing restoration, alarm cancellation on acceptance, failed media
session recovery and receiver-driven expiry without a Dart engine. Actual OS
process-kill delivery and Doze timing still require device-level verification.

## Native push SDK entry

`UnifiedCall.showIncomingCall(context, config)` and
`UnifiedCall.cancelCall(context, callId)` return CompletableFuture<Void>. They
marshal work to the main looper and report validation/storage/platform errors
through exceptional completion. The host must observe completion and decide
whether to retry its business operation. They do not require Flutter registration
or a live Dart isolate. Persisted events are replayed when Flutter later starts.

The ordinary foreground/background push delivery rules still apply to the host
SDK; invoking these methods does not bypass Android background restrictions.

## Integration test harness

The current Flutter SDK's direct `flutter test` device runner encountered DDS
startup and golden-stream subscription failures. The same lifecycle assertions
passed on the API 27 physical device using the standard integration_test driver:

```sh
flutter drive --driver=test_driver/integration_test.dart --target=integration_test/plugin_integration_test.dart -d DEVICE_ID --no-dds
```

Run from the example directory. This pass covers normal lifecycle/dedupe,
event read/acknowledgment, active snapshots and foreground timeout. It does not
prove actual process-kill alarm delivery or Doze timing.

## Telecom foreground service

Accepted calls with a live Telecom Connection start a non-exported phoneCall
foreground service. The service declares FOREGROUND_SERVICE and
FOREGROUND_SERVICE_PHONE_CALL permissions; MANAGE_OWN_CALLS supplies the declared
phone-call prerequisite. It does not capture audio or configure a media engine.
Notification-only fallback calls do not start this Telecom-specific service.
The final ongoing Telecom call ending stops the service. A delayed start received
after all calls ended promotes then immediately stops to satisfy the foreground
promotion deadline. Background start restrictions still apply; platform failures
produce durable platformError events and native logs.

A duplicate Telecom creation cannot replace a live connection. Creation failure
emits a platform error and ends the call as failed rather than leaving ringing
state. Bluetooth/system answer/reject/disconnect callbacks drive the same state
operations as Flutter commands.

Verification on 2026-11-02: 13 Robolectric API 34 tests passed. A dedicated API 27
physical-device driver test enabled real Telecom, waited for a system audio-route
callback, accepted, marked connected and ended the call without platform errors.
Read-only dumpsys during the call confirmed CallForegroundService with
isForeground=true. The post-driver service record was absent, but driver uninstall
also cleans services; that observation alone does not prove normal end cleanup. This does
not prove Android 14+ background-launch rules, physical Bluetooth controls,
lock-screen interaction, process death or Doze behavior.

## Atomic persistence (2026-11-03)

Android state now uses AtomicFile. A write failure restores the last committed
in-memory snapshot and does not dispatch the attempted event. Failed event
acknowledgment preserves the inbox for retry. Atomic backups are recovered before
legacy SharedPreferences; old preferences remain readable when no atomic state
exists. The old cache is not destructively cleared during migration. Eighteen
Android native tests passed, including a real file-write failure and backup case.

## Android ringtone configuration

`initialize(androidRingtoneName: 'demo_ringtone')` resolves a host resource such as
`android/app/src/main/res/raw/demo_ringtone.wav`. Supply the lowercase resource
name without extension. Validation happens before options are committed; missing
resources and invalid names return an error. A resource removed by a host upgrade
falls back to the system default during recovery and reports/logs that fallback.
Keep resource names stable across upgrades.

Each configured resource name uses a stable dedicated incoming channel. Android
channel sound settings are immutable after channel creation and remain under user
control; old channels are not deleted or reset. API 24/25 configure notification
sound/vibration directly. Ringing notifications request FLAG_INSISTENT; accepted
notifications remove that flag and use the quiet ongoing channel. Actual playback
and device sound/DND behavior still need manual or audio-device verification.
The example bundles an original generated one-second tone for integration testing.

API 27 driver integration passed with a valid bundled tone and a missing-resource
error assertion. Notification history identified the custom channel. The driver
uninstalls its test package on teardown, so post-run dumpsys cannot verify that
channel's sound URI or distinguish end-call service cleanup from uninstall cleanup.
The earlier service-during-call observation remains valid; last-call service cleanup
is independently covered by Robolectric.

## Android 13 CallStyle policy fallback

The API 33 Xiaomi device rejected the incoming CallStyle notification with the
explicit policy error requiring a foreground-service notification or effective
fullScreenIntent. The code now retries that specific policy rejection as a generic
call-category notification retaining answer/decline actions and the full-screen
intent to `IncomingCallActivity`. It emits a durable `platformError` with
notification/call_style_rejected/generic metadata and lets unrelated errors or
failed fallback posting propagate. Ongoing per-call
notifications use normal call-category actions; the independent phoneCall service
continues to own its foreground notification. API 34 without full-screen access
preselects the generic incoming layout.

The fallback unit cases and Android Lint passed. The API 33 test that exposed the
issue is retained as failed; installation of the fix was again blocked by the
phone, so runtime verification of the fix is still pending. Future driver runs
can use --keep-app-running to retain the test app for direct OS-state inspection.
