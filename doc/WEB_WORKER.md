# Web background implementation status

Flutter Web and the host-owned Worker adapter share the IndexedDB transaction
core. Packaged core/page scripts load automatically from the Flutter asset bundle.
Secure contexts (HTTPS or localhost), IndexedDB and BroadcastChannel are required.
Cross-context committed events update the Flutter stream; page timers and visibility
resume reconcile expired calls. The durable inbox remains the recovery source.

## Page initialization

`initialize()` enables foreground Banner/state handling without taking ownership
of any Service Worker. After installing the host Worker adapter, use
`initialize(webUseServiceWorker: true, webLaunchUrl: '/calls')` to route commands
through it. The launch URL must use the application origin. A missing adapter
returns a timeout error; it never silently installs a replacement Worker.
Permissions are requested only by an explicit host user gesture.
The initial development-only localStorage store is no longer read.

## Host-owned worker

Copy `web/unified_call_core.js` and `web/unified_call_worker.js` to the same public
worker directory. Import `unified_call_worker.js` from the application's existing
worker. The adapter does not register a worker, claim clients, or replace a push
SDK's worker. The host owns registration, subscription and server push transport.
The import path for the core is relative to the worker entry script URL; keep all
three worker files in the same directory or adjust the host build accordingly.

Push payloads use the top-level key `flutter_unified_call`. Incoming payloads
contain a full call config or `{type: 'incoming', call: config}`. Remote cancellation
uses `{type: 'cancel', callId: '...'}`. Unrelated pushes are ignored. Notification
clicks are identified by notification.data.unifiedCallId; a body tap opens/focuses
the page without acceptance, acceptance persists an event before opening, and
decline persists an event without opening a page. Browser support controls the
availability of notification action buttons.

Page-to-worker requests use a transferred MessagePort and this envelope:
`{type: 'unified-call', method: 'getActiveCalls', args: {}}`.
Responses are `{ok: true, value, events}` or `{ok: false, error}`. Supported state
operations match the Dart controller, plus internal getOptions. initialize stores
acceptText, declineText and optional same-origin webLaunchUrl. The channel
`flutter_unified_call_v2` broadcasts committed events to other contexts.

## Storage and expiry

One IndexedDB read/write transaction covers each state transition, event append
and acknowledgment. Concurrent incoming messages with the same callId produce
one incoming event. Every operation reconciles expired ringing calls. Acceptance
cannot revive an expired or terminal call. Worker lifetime and system notification
expiry are controlled by the browser; a sleeping worker cannot run a reliable
continuous timeout. The page must schedule expiry reconciliation while active.

Committed events remain until the application acknowledges their event IDs.
Broadcast delivery is transient; storage is authoritative for cold-start recovery.
This layer does not provide exactly-once external signaling or media connection.

## Current verification

`test/web/core_test.cjs` uses Playwright with installed Google Chrome. Supply
Playwright through the Node module search path or PLAYWRIGHT_MODULE. Run from the
repository root with `node test/web/core_test.cjs`.

Real browser coverage: concurrent writes across pages, accept/decline races,
state transitions, replay after page reload, acknowledgment from another page,
late acceptance, special call IDs, worker message RPC and shared worker/page DB.
Actual Web Push delivery and OS notification action clicks remain unverified.

The compiled Flutter Web example passed testing in real Chrome through its accessible
UI: a call shown in one tab appeared in another tab's Banner, acceptance synchronized,
media-connected state synchronized, and ending the call removed it from both tabs.
The same UI flow passed with page commands routed through the host Worker adapter.
`test/web/flutter_app_test.cjs` serves the output of `flutter build web` locally.
This does not prove actual remote push delivery or OS notification action clicks.

## Foreground ringtone and audio unlock

`initialize` accepts `webEnableRingtone` (default true) and optional
`webRingtoneUrl` (HTTP/HTTPS URL or relative asset URL). With no URL, the page
uses a pulsed Web Audio tone; a supplied URL uses looping HTML audio. Only the
visible, focused page rings. Acceptance, decline, timeout, loss of focus and
page hiding stop its nodes/media. The Service Worker never plays continuous audio;
background sound belongs to the browser's system notification behavior.

The page installs pointer/keyboard handlers to create/resume its AudioContext.
Hosts can call `unlockWebAudio()` directly from a user gesture after initialization.
Call it before awaiting unrelated work so the browser can recognize the gesture.
Autoplay and file failures produce a durable platformError event with
metadata.operation = ringtone. The host can offer an Enable audio action; failure
does not end the call or imply that media joined. Actual browser policy still
controls whether an audio element or context can play. Playback attempts have a
bounded timeout and errors are not treated as successful playback.

The configured URL is validated before storing new options; script/data schemes
are rejected. Make the audio reachable with the correct media content type.
Provide host/localization UI for retry/unlock if autoplay is blocked.

`test/web/ringtone_test.cjs` exercises real Chrome AudioContext/oscillator and
HTML audio objects: gesture unlock, blocked-event persistence, acceptance/expiry
stop, explicit blur/focus handlers, custom WAV playback/end stop and failed media
loading. Instrumentation counts actual node start/stop calls. The focus portion
explicitly dispatches events because headless tab switching is not proof of native
window focus behavior. These tests do not measure speaker output or prove mobile
Safari autoplay/focus behavior.

## Background Push runtime test

`test/web/worker_push_test.cjs` uses Chrome's DevTools test Push delivery against
an actual activated Worker. The observer page is outside the application Worker
scope, so it does not count as a visible application page. With test notification
permission granted, incoming Push produces a browser-managed notification with
accept/decline actions. The test then stops Workers, delivers remote cancellation,
checks notification removal and recovers the incoming/ended inbox with the original
payload. This passed in real Chrome. It validates Worker wakeup and Push handling,
not a production Web Push subscription/server, encryption or a trusted OS action
click/open-window authorization flow.
