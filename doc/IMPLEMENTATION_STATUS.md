# Implementation status

This is an in-progress implementation, not a release candidate.

## Implemented

- Android/iOS/Web plugin scaffolding.
- JSON-safe immutable call payloads with absolute expiry.
- Call events with stable identifiers and explicit acknowledgment API.
- Method/Event channel contracts and permission status model.
- Web shared IndexedDB transactions, packaged page bridge, BroadcastChannel
  event subscription, page expiry/recovery and optional host Worker routing.
- Cross-platform active call snapshots and shared customizable CallBannerHost,
  with foreground recovery, local expiry, busy actions and error/retry UI.
- Complete example showing serialized event replay/ack, permission requests,
  active calls and explicit media-connected simulation.
- Android atomic-file call state and event inbox committed before dispatch, with
  failed-write rollback and legacy SharedPreferences reads.
- Android notification actions, dedicated lock-screen Activity, self-managed
  Telecom ConnectionService and audio route event callbacks.
- Android notification permission request and full-screen settings entry point.
- iOS atomic file call/event store, actionable notifications, category merging,
  Flutter lifecycle notification callbacks, permission query/request and foreground
  timeout reconciliation. Native push SDK entry point is available without Dart.
- Model round-trip, validation, replay contract and native error propagation tests.

## Required before release

- Android: actual process-kill/Doze verification and full Telecom recovery,
  Telecom/lock-screen UI runtime verification, API 34+ permission/device coverage,
  physical ringtone/audio behavior and coexistence validation.
- iOS: physical-device sound/APNs and notification-action
  verification, notification SDK coexistence, cold-start timing and SwiftPM checks.
- Web: actual Web Push/notification action verification, broader browser coverage,
  physical audio/focus policy coverage and Worker lifecycle failure tests.
- Native compilation, device/integration testing and browser verification.
- Final repository/author metadata and remaining platform release gates.

## Contract decisions

- Push transport and media engines belong to the host application.
- Acceptance expresses user intent; only the host marks media connected.
- Expiry is absolute so delayed messages cannot restart the ringing window.
- Events are at-least-once and acknowledged only after host handling succeeds.
- Notification/full-screen/Telecom permissions are reported independently.
- No promise of guaranteed background launch or notification delivery on iOS/Web.

## Verification (2026-11-02)

- Android debug APK compiled successfully.
- Four Robolectric API 34 state/inbox regression tests passed.
- Android API 27 physical device: permission query integration test passed.
- Android API 27 physical device with Telecom disabled: lifecycle, deduplication,
  durable inbox read/acknowledgment and ringing timeout integration test passed.
- These tests do not prove Telecom, hardware controls, lock-screen interaction,
  foreground service survival, push delivery or API 34+ full-screen behavior.

- iOS simulator debug build passed using CocoaPods.
- Swift store regression executable passed: dedupe, legal/illegal transitions,
  expiry, file reload/acknowledgment and atomic-write failure rollback.
- iPhone 17 / iOS 26.5 simulator lifecycle integration test passed: dedupe,
  accepted/connected/ended ordering, durable inbox read/ack and timeout.
- iOS notification permission UI, notification action taps and actual remote push
  delivery remain unverified. See IOS_INTEGRATION.md for the current boundaries.

- Four shared Banner Widget tests passed: restoration/duplicate clicks, stale
  snapshot rejection, error retry/custom actions and lifecycle resume/filtering.
- Full example Widget test passed: initialization and empty call state.
- iOS simulator and Android API 27 physical-device active snapshot assertions
  passed for ringing, accepted, connected and ended. iOS missing-ringtone
  configuration returned the expected platform error. Static analysis passed.
- Web release example build passed (including Flutter's Wasm compatibility dry
  run). Browser runtime, Web Push and Service Worker behavior remain unverified.

- Shared Web IndexedDB transaction core and host-owned Service Worker adapter
  implemented. Browser tests cover concurrent state operations, replay/ack,
  expiry, arbitrary IDs and Worker RPC/shared database access.
- Flutter Web now uses the shared core, cross-tab event subscriptions, capability
  checks, page expiry scheduling and optional host Worker routing.
  See WEB_WORKER.md. Real Push/OS notification interactions remain unverified.

- Compiled Flutter Web example passed real Chrome accessible-UI testing across
  two tabs: incoming Banner, acceptance, connected state and end cleanup.
- Shared browser tests also passed page-to-Worker bridge configuration and
  same-origin launch URL validation. No localStorage backend remains.

- Final Web build and static analysis passed. Compiled Flutter UI tests passed
  both foreground-only and host-Worker modes across two tabs, waiting for async
  command completion and verifying empty IndexedDB active state after ending.

- Android now restores ringing notifications and schedules process-independent
  inexact timeout alarms. New-process accepted/connected sessions end as failed;
  repeated initialization in the same process preserves active sessions.
- Seven Robolectric API 34 tests passed, including three recovery/alarm cases.
  Actual process-kill/Doze timing is not yet verified. See ANDROID_RECOVERY.md.
- Android native push entry points now marshal calls/cancellations to the main
  looper and return CompletableFuture errors without requiring a Flutter engine.
- Eight Android native tests passed, including native push invocation/error paths.
- Android API 27 lifecycle integration passed through integration_test driver
  with --no-dds. Direct flutter test runner had DDS/golden-stream infrastructure
  failures; these are not counted as passing runs. Process-kill/Doze device tests,
  foreground service and complete Telecom recovery remain pending.

- Android phoneCall foreground service implemented for accepted live Telecom
  connections, with last-call cleanup and delayed-start handling. Start failures
  emit platformError; fallback-only calls do not claim Telecom foreground service.
- Duplicate Telecom creation is rejected; creation failure ends ringing as failed.
- 13 Android native tests passed, including service and system callback lifecycle.
- Real Telecom API 27 driver test passed. System audio route callback observed;
  dumpsys confirmed foreground service during call. Post-driver absence is affected
  by driver uninstall and is not independent proof of normal end cleanup.
  Android 14+ background restrictions and physical hardware/lock screen remain
  unverified. See ANDROID_RECOVERY.md.

## Persistence and package preparation (2026-11-03)

- Android now atomically saves call/inbox state to a file and rolls memory back
  on failed writes. Existing SharedPreferences snapshots are read as a fallback
  until a successful atomic save. Atomic backups take precedence over legacy data.
- 18 Android native tests passed, including actual file-write failure, state and
  acknowledgment rollback, legacy reads and atomic backup recovery.
- Apache-2.0 selected by the user; official license text added. README, changelog
  and development package description/version updated. No publication performed.
- Package dry-run found missing repository metadata and the plural docs directory;
  the directory has been renamed to doc with links updated. Metadata is pending
  the user's repository/author details. The dry-run was local validation using
  the configured pub mirror, not proof of pub.dev publication readiness.
- After the atomic-file change, Android API 27 physical-device lifecycle driver
  passed. Static analysis and all eight Dart/Widget tests also passed.

## Permission typing and Android ringtone

- CallPermissionStatus now exposes CallPermissionState enum values. Unknown future
  native strings map to unknown; malformed non-string values are rejected.
  Example UI and integration bindings were updated with the model.
- Android custom raw-resource ringtone validation, dedicated stable channels,
  pre-channel API sound/vibration and ringing repeat flag implemented. Recovery
  safely defaults when a previously bundled sound has been removed.
- 21 Android native tests and nine Dart/Widget tests passed. API 27 driver passed
  valid-resource, missing-resource error and typed-permission assertions.
- Driver teardown uninstalls the test package: post-driver service/channel queries
  cannot establish end-call cleanup or inspect the removed channel. Earlier live
  foreground-service evidence is valid; actual sound and normal end cleanup need
  separate device-level observation. See ANDROID_RECOVERY.md.

## Web foreground ringing

- Added default pulsed Web Audio tone and configurable looping HTML audio, gesture
  unlock API/handlers, visible/focused-page gating and stop on terminal/accepted
  state, expiry and lifecycle changes. Failures persist platformError events.
- Real Chrome audio tests passed for gesture unlock, blocked events, actual node
  lifecycle, explicit focus handlers, WAV media playback/end stop and load failure.
  Native window focus changes and actual speaker output are not established by
  the headless test. Other browser/mobile policies remain unverified.
- Static analysis and all nine Dart/Widget tests passed. Shared IndexedDB/Worker
  browser regression, Web build and compiled Flutter app foreground/Worker
  cross-tab regression passed. An explicit Web audio-unlock example button was
  added; final compilation/static analysis and the compiled Flutter UI test passed,
  including an actual click through the public audio-unlock API.

## iOS foreground ringing

- Added looped AVAudioPlayer with original default PCM or configured Bundle sound,
  once-per-call updates, disable support, failed-playback deduplication/reset and
  state/lifecycle stop/resume. Playback errors become durable platformError events.
- Foreground system sound is suppressed; background entry re-posts unexpired
  ringing notifications. Local notification sound respects iosEnableRingtone.
  No explicit AVAudioSession category/activation change is made by the plugin.
- Swift store regression and all six final native XCTest cases passed, including
  local notification sound disable/payload preservation and runtime lifecycle wiring.
- Final Flutter-to-Swift lifecycle driver passed on iPhone 17 / iOS 26.5 simulator.
  Default PCM was decoded by actual AVAudioPlayer in XCTest; playback lifecycle
  assertions use a controlled playback implementation. Audible hardware output,
  real background/lock transitions and APNs delivery remain unverified.
- CocoaPods/SwiftPM resource declarations now package PrivacyInfo.xcprivacy as a
  resource. The CocoaPods build contains flutter_unified_call_privacy.bundle.

## Android audio endpoints

- Added typed endpoint query/request APIs, API 34 system CallEndpoint requests,
  legacy supported-route requests, endpoint/route events and mute deduplication.
  iOS/Web explicitly report unsupported. No media SDK or microphone control added.
- All 24 Android native tests and ten Dart/Widget tests passed. Final late-callback
  guards compiled and passed native regression. Both example Widget tests passed,
  including empty endpoint UI and opaque-ID selection. Static analysis passed.
- API 27 physical Telecom driver passed speaker endpoint selection and system
  callback confirmation, then connected/end lifecycle. An earlier run had external
  device-operation interference and is retained as a failed run, not counted green.
- No Android 14+ AVD/system image was found locally. Modern device routing and
  hardware/audio output remain unverified. See ANDROID_AUDIO.md.

## Background Push and Android validation

- Real Chrome DevTools test Push passed with no app-scope client: browser-managed
  notification/actions appeared, a stopped Worker woke for cancellation, the
  notification was removed, and durable events retained the original payload.
  Real external Push services and trusted OS notification clicks remain unverified.
- Android Lint found API guards missing on the route API/helper. Explicit runtime
  guards now reject routing below API 26 and endpoint access below API 34. Lint
  and 25 native tests passed, including API 24 rejection coverage.
- User requested the connected Android 13/API 33 device instead of installing a
  test image. The image installer was interrupted and its partial download removed.
  API 33 test APK compiled, but the device rejected installation with
  INSTALL_FAILED_USER_RESTRICTED; runtime assertions have not run on that device.

## iOS early notification action entry

- Added a host-callable native action entry that persists actions without relying
  on Flutter lifecycle registration. Example AppDelegate routes owned actions
  before super, leaving unrelated SDK notifications on the normal path.
- Eight iOS XCTest cases passed. New isolated-runtime coverage verifies durable
  acceptance without Dart listeners, duplicate/expired acceptance and payload
  recovery. Public entry ownership returns false for foreign notifications.
- Real APNs cold-launch callback timing still needs physical-device verification.
- Android 13 tests remain pending the device's USB-install allowance; no repeated
  install attempts were made during this independent work.

## API 33 runtime finding

- User allowed one test installation; API 33 lifecycle test then exposed a real
  CallStyle policy rejection. This was an executed failure, not an install issue.
- Added narrowly matched policy fallback preserving notification actions and
  explicit degradation metadata. Non-policy errors and failed fallback propagate.
- 27 Android native tests, Android Lint, static analysis and ten Dart/Widget tests
  passed. Fix APK compiled, but reinstallation was denied by device policy, so
  no passing API 33 runtime result is claimed for the fix.
- Next requested phone run will retain the app with --keep-app-running. It needs
  the user available for the phone's install confirmation.
