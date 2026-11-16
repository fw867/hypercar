# ID9 CarPlayAvailability group 0 comparison

**Goal:** Perform the user's single-variable ID9 experiment by adding group 0 to 0x4300, preserving group 1 and every RTSP/media setting.

**Evidence:** The supplied 574 log has three type110 empty-body 400 responses despite accepted session HEVC and resource requests. The user supplied the original pklg during implementation; independent complete parsing confirmed 7435 records, 395 complete L2CAP packets, 25 checksum-valid iAP2 frames, 19 complete CSM messages, no pending fragments, and two byte-identical 75-byte 4300 messages. Groups are ordered 0 then 1, both available=1. Group 0 identifier is 24 hex characters plus NUL; group 1 is a 17-character Bluetooth MAC plus NUL. Earlier repository analysis already recorded both groups, describing group 0 as wired availability and nested field 0 as an available boolean. Raw identifiers remain outside the repository.

**Design:** Only `bmw_id9` includes group 0 before unchanged group 1. Group 0 contains available=1 and a stable 24-character uppercase hexadecimal identifier derived using domain-separated SHA-256 from this phone's existing Bluetooth identity. This is an experimental local identifier, not an Apple USB identity or a claim of working wired transport. All initial/retry 4300 sends use the same builder. No HEVC, URI, latency, audio, authentication order, Wi-Fi policy or pairing identity changes.

**Baseline:** Current clean HEAD is 575, including previously committed Wi-Fi lease work; this experiment does not revert that work. Release 576 increments shared version once. Reuse the unchanged native library from 575 and verify its packaged hash remains identical.

- [x] Add failing real-sender tests: ID9 group order/75-byte CSM/boolean/NUL strings/stable identifier and unchanged group 1; other builtin profiles retain byte-identical 37-byte CSM.
- [x] Implement the optional group and route all three sender call sites through the profile-scoped body builder.
- [x] Run targeted and complete JVM checks plus lint; review the narrow diff.
- [x] Build release, compare native library hash, install with `adb install -r` if connected, preserve data and pairing; no commit/push.
- [x] Record the experiment and physical-vehicle verification boundary. Do not claim SETUP 400 is fixed without the user's test.

