# Global absolute session URI

User extends the existing BMW URI comparison to every vehicle profile. Remove the profile-scoped JSON flag and its Kotlin/Rust gates entirely; configure one immutable absolute URI on the final paired control channel for every negotiation. Preserve URI shape/IPv6/scope handling, audio SETUP target, request bodies/headers/timeouts/order and existing HEVC policy. Existing uncommitted573/10669 remains the only version increment; no commit/push.

Add a real-socket regression for the common URI initialization path across independent controls; retain encrypted URI/CSeq/body tests and cleanup coverage. Run JVM/native/lint checks, build/install, verify DiPlay reconnection and stop. Other vehicle symptoms remain hypotheses pending their own logs.

Completed: old profile-scoped flag and helper removed; initialization is unconditional. 544 JVM and302 native tests pass, one existing live test ignored; lint0 errors/76 warnings. Release573/10669 installed. Two DiPlay control sessions reuse their own URI across both SETUP phases and RECORD with200 responses; Stop TEARDOWN uses the old URI without200 ACK before control closure, reconnect allocates a fresh URI and restores HEVC. Audio SETUP path/body remain unchanged. Static review found noP1/P2. No commit/push; real vehicle root cause remains unproven.
