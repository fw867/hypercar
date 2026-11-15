# CarWith hardware HEVC transcode implementation plan

**Goal:** HEVC priority converts CarWith AVC through phone hardware decoding and HEVC encoding, with H.264 passthrough fallback. BMW ID8.5 is the primary vehicle; DiPlay is the first integration target.

**Architecture:** Keep existing source parsing, AirPlay negotiation, encryption and stream lifecycle. Add a source-scoped Rust router and JNI-owned Kotlin MediaCodec pair. Hardware AVC decoder renders through an external GL texture to the HEVC encoder Surface, retaining PTS and encoding the receiver main screen dimensions. User clarified full-screen stretching on 2026-11-16; remove aspect-fit sidebars and retain normalized touch mapping. Real HEVC remains passthrough. H.264 preference or a receiver without negotiated HEVC bypasses conversion.

**Constraints:** No commits/push. Reuse uncommitted 573/10669. Preserve PCM audio and pairing data. Configuration changes retire the old codec pair; fallback waits for AVC SPS/PPS and IDR, requests fresh source keyframe, and stays latched until a new source round. Bounded codec queues and an input-active output watchdog prevent accumulation or permanent black video. No CPU YUV copies or software codec fallback.

- [x] Add regression tests for negotiation gating, hardware failure, keyframe fallback, source reset, source format changes and native HEVC bypass.
- [x] Implement hardware MediaCodec selection, GPU decoder-to-encoder connection, timestamped draining, bounded pending input and background cleanup.
- [x] Integrate source-scoped router and polling into video pump; keep existing transport counters/keepalive and nonce continuity.
- [x] Run JVM/native checks and a real-phone production codec probe. Build/install, then inspect DiPlay CarWith logs and ask for display confirmation if needed.
- [x] Record verification and remaining vehicle-only limitations.

**Result:** Release573/10669 installed with data retained; 542 JVM tests, 294 native tests plus one existing ignored live test, lint0 errors/76 warnings. Real phone hardware probe decodes all three target sizes. Final DiPlay produces HEVC2400×1080 around30fps; user confirms full-screen display and correct sliding/clicking. Early JNI delegation and burst backpressure failures were observed and corrected, with actual AVC fallback logged. BMW ID8.5 vehicle validation remains outstanding. No commit or push.
