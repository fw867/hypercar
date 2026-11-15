# BMW absolute session URI single-variable comparison

**Scope:** Only bmw_id85 and bmw_id9 profiles. Use the selected profile even when receiver identity is DiPlay; do not enable for ID6, generic or other vehicles. Preserve every capability, body, timeout, authentication/audio setting and request order.

**Design:** Generate one URI after pairing has selected the final control channel: rtsp://<authority>/<UUID-derived unsigned64 session ID>, without an explicit port, matching the box's format. Bracket IPv6 literals, URI-encode raw socket scope IDs, keep socket host untouched. Attach an immutable URI policy to that control channel. Resolve legacy session SETUP /setup, screen SETUP /, RECORD / and TEARDOWN / to the same URI before formatting/encryption. Leave audio SETUP's existing absolute URI, auth, info, command, feedback and incoming responses unchanged. Shared control ownership covers failed negotiation, stream reclaim/rebuild, auxiliary and final teardown without generating another ID.

- [x] Add red-green tests for exact profile scope, IPv4/IPv6/zone/invalid authorities, immutable ownership, reconnect isolation and encrypted real-socket transactions.
- [x] Thread the profile flag through SettingsStore, EngineConfig and AirplayParams; install policy only after pairing retries are complete.
- [x] Verify negotiated failure cleanup and final TEARDOWN reuse the same policy, including URI-independent HEVC fallback retry.
- [x] Run JVM/native checks, build/install existing573/10669 batch and validate BMW-profile DiPlay flow. No commit/push; real BMW acceptance remains pending.

Result: 545 JVM tests and301 native tests pass, one existing live test ignored; lint0 errors/76 warnings. Release573/10669 installed. User confirmed normal picture; actual IPv6 DiPlay logs show both SETUP stages and RECORD using one URI, 200 responses, original audio URI unchanged. App Stop sends TEARDOWN with that URI; control closes before ACK. Reconnect creates a new URI and restores HEVC2400×1080. No claim of TEARDOWN200 or BMW vehicle400 resolution.
