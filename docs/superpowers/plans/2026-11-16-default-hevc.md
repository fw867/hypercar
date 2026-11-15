# Default capability-gated HEVC

User requests HEVC priority by default across every vehicle profile. Preserve an explicitly stored H.264 choice. Missing preferences default to HEVC; unknown values fail closed to H.264. Before native startup, require a hardware HEVC Main Surface encoder; actual dimensions/rate and decoder availability remain checked by each existing encoder/transcoder. Session SETUP proposes HEVC consistently across profiles only for the effective HEVC preference. Actual video uses HEVC only if this session explicitly returns enabledFeatures/hevc. Keep all other profile capability, audio, authentication and ordering differences.

- Test missing/existing/invalid preferences and hardware-unavailable selection.
- Test every profile combination with preference on/off and strict session acknowledgment.
- Update default, preflight and profile feature filtering. Build/test/install existing573 batch without another version increment; no commit/push.
- Verify DiPlay negotiation and sustained output, document limits on other vehicles.

Completed: 544 JVM and297 native tests pass, one existing live test ignored, lint0 errors/76 warnings. Release573/10669 installed; DiPlay explicitly acceptsHEVC and produces2400×1080 around30fps with no observed fallback in the inspected round. Hardware and preference tests observed failing before implementation; all-profile and actual RTSP400 retry regressions likewise failed before the corresponding change. Static review found no P1/P2. No commits/pushes; vehicle coverage remains unverified beyond the existing DiPlay observations.
