# Android Telecom audio endpoints

`getAudioEndpoints(callId)` returns typed CallAudioEndpoint values with opaque id,
human-readable label and CallAudioRoute. This requires a live Telecom Connection;
notification-only fallback calls return an error. iOS and Web return unsupported
because their external media engines own audio routing.

Call `requestAudioEndpoint(callId, endpoint.id)` after acceptance. Select IDs from
the current list; do not construct them from route names. Android API 34+ passes
the actual system CallEndpoint to requestCallEndpointChange, with its asynchronous
outcome and a five-second bounded wait. Earlier versions submit setAudioRoute using
the system-supported mask. Legacy completion means the request was submitted, not
that hardware switched. Always observe audioRouteChanged for the actual result.
Bluetooth endpoints can represent distinct devices on newer Android versions.

`audioEndpointsChanged` reports available-list updates in metadata.endpoints.
`audioRouteChanged` reports route/endpointId. `muteToggled` reports isMuted and
deduplicates repeated state callbacks. It is a system state event, not permission
to mute an RTC engine; the host must apply its media-engine mute operation.
The plugin does not capture audio or modify any RTC SDK object.

The example's Audio output action handles empty lists, selection cancellation,
busy commands and platform errors. It transmits the selected system ID unchanged.

Verification: API 27/34 Robolectric tests cover identities, supported lists,
invalid/stale endpoint/state rejection and mute deduplication. An API 27 physical
Telecom driver test queried endpoints, requested the speaker and received the
system speaker callback before completing the call lifecycle. An earlier run was
interrupted by external phone operation; the subsequent full run passed.
Actual speaker sound, Bluetooth hardware and API 34+ device routing remain
unverified. No Android 14+ AVD or system image is currently installed locally.
