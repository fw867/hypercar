# Box state machine and ID8.5 startup screen claim

> Execute inline with superpowers:executing-plans. No commit or push.

**Goal:** Preserve the box analysis and fix the independently demonstrated ID8.5 WAIT_OWNERSHIP startup gap without claiming all post-iAP2 EOFs are solved.

**Evidence:** Zero-base Ghidra: CarplaySimSessionStart 0x47a48 dispatches disableBluetooth, ScreenInit and delayed local BT stop. ScreenInit 0x42df4 starts a 50ms timer; 0x28cb8 calls ResourceManager_ControllerTakeScreen, whose 0x7d034 request uses priority/take 500 and borrow 100. App from_info uses priority 100; real ID8.5 short rounds have takeConstraint=500 and source frames discarded while no screen SETUP exists. The first short round already had a screen socket, so this is not the unique EOF cause.

**Architecture:** Keep initial RECORD/resource declaration unchanged. Before activating resource reconciliation, only the real BMW box bootstrap may make one local startup claim at priority 500, publish modesChanged and validate its reply. Use the initial screen revision to avoid overriding intervening receiver decisions. Never and uninterruptible borrow remain enforced. Keep ID9, NBT, MBUX, desktop and Wi-Fi cleanup unchanged.

**Files:** modes.rs labels a reusable constrained screen claim; screen_resource.rs owns startup claim/ACK boundary and socket tests; airplay.rs calls it after handoff and before activation. VALIDATION.md and BMW-BOX-CARPLAY-FLOW.md record evidence/limits. Tool installs, original binaries, Ghidra projects and raw exports stay outside the repository.

- [x] Add real RTSP socket regressions for constraint 500, Never, borrow, intervening revision, rejection, repeat and profile exclusion; observe a behavioral red with the existing no-claim behavior.
- [x] Implement the bounded claim and preserve receiver changes while awaiting ACK; verify green and source call ordering.
- [x] Complete firmware state/callback/bridge map, distinguish proven differences from EOF hypotheses.
- [x] Run native/JVM/lint checks, increment shared 576/10672 to 577/10673 once, build and install with data/pairings preserved.
- [x] Final independent review; document remaining physical-vehicle boundary. Never commit/push.

## Follow-up: measured CarWith source startup

- [x] Reproduce late entry with an optimized Android timing-only probe; compare two cold and two prepared rounds (first IDR 2460–2486ms vs 366–420ms).
- [x] Move entry preparation before Bluetooth/Wi-Fi negotiation, preserve attempt/cancellation checks and existing cleanup; remove connected-stage duplicate launch. Keep self UI, network and protocol behavior unchanged.
- [x] Review lifecycle (preserve preparation on activation-only rejection), run JVM584/lint0errors, build and reinstall the same 577/10673 batch preserving data. Vehicle EOF remains unproven until a fresh car round.
