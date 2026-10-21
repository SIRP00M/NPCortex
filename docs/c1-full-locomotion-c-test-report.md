# C-L1..L4 Full Locomotion Animation Controller C — Test Report

Date: 2026-10-21. Baseline: **NPCortex_C2-F2_TurnAware_SafeSmoothing_B_FullRepository_CANDIDATE.zip**. Status: **IMPLEMENTATION CANDIDATE; Windows Acceptance not yet complete**.

## Scope and audited cause

- `C1P1MotionRuntime.java` set physical movement yaw, head and body equal every Continuous WALK tick. The original `C1VanillaMoveControlAdapter` allowed up to 32 degrees of rotation per server tick.
- `C1P0ClientSync.java` applied each custom revisioned rotation packet directly to `RemotePlayer`, potentially competing with vanilla entity tracking. Additional yaw overwrites were avoided after the initial snapshot.
- `C2FollowRuntime.java` has an explicit safe TURNING phase for vertical primitives; this is **not** removed and still obeys the C1 ticket busy gate.
- User-reported screenshot showed `F1=PAUSED`, `motion=CANCELLED` / `CONTINUOUS_WALK` and 0 follow failures. It does not prove which rendering subsystem caused a particular visual jerk.

## Implementation by sub-phase

**C-L1 — Presentation state:** `C1LocomotionPresentation` tracks IDLE/TURNING/STARTING/WALKING/CORNERING/STOPPING/INTERRUPTED per live NPC (transient, not persistent or an authority for motion). Presentation phase appended to existing `/npcortex c1 motion <npc>` diagnostics.

**C-L2 — Head/body:** authoritative C1 movement yaw and `Player.travel` unchanged. Bounded shortest-arc head/body with max 11° body and 18° head per tick, head lead max 30°, relative head-body max 55°. Separate target waypoint heading only when moving. All rotation uses angle wrapping.

**C-L3 — Walk/start/turn/stop:** Follow-only yaw turn cap adjusted from 32° to 24° for sharp turns, with error-proportional smaller final steps, preserving bounded forward braking and collision preflight. presentation changes are based on measured movement displacement, Follow steering and explicit stop/interrupt; no fake movement impulses. The actual Vanilla player walk animation remains controlled by normal entity movement. Explicit operator turn remains authoritative; its pose settles on idle world ticks. No additional Mob AI/renderer/controller was introduced.

**C-L4 — Client/lifecycle:** first verified rotation packet initializes the RemotePlayer/interpolation endpoints as before. Subsequent packet revisions target `C1ClientRotationBlend`, stepped each client END tick; physical yaw is left to Vanilla entity tracker after initialization, avoiding competing per-packet yaw snaps. Death state skips blend; new token/world change clears session; avatar detach/server stop clears server presentation. Revision/token validation and viewer ACK are unchanged.

## Automated verification — executed in container

- JDK target: `javac --release 17` — **PASS**.
- Java pure-unit: **15/15 suites PASS, 2,404 reported assertions**, including new C-L1..4 deterministic 52-assertion self-test and retained Graph/A*/TurnAware/Follow/Terrain checks. Command: `python /mnt/data/work_locomotion_c/run_java_regression.py` (author-side harness, not a shipped required dependency). Windows script: `scripts/test-c1-full-locomotion-c.ps1`.
- New tests: phase transitions including starting, walking, cornering, stopping, interrupt/restart; yaw wrap ±180°; bounded body/head rotation and relative twist; stale direction prevention in combat; packet pose interpolation, late target convergence; independent 10-NPC pose state; nonfinite inputs.
- **No Forge API compile**: attempted `forge-mod/gradlew compileJava --offline`; wrapper still tries to fetch Gradle 8.8 and fails with `UnknownHostException: services.gradle.org`. `--offline` cannot substitute a missing distribution. Not claiming Forge build PASS.
- **No actual Minecraft client or player-network visual acceptance** was possible here. Head/body blending may still need adjustment after real gameplay due to Vanilla tracker interactions. Combat authority, collision, transactional cancel, end-to-end chunk/load, cross-dimension, save/rejoin and performance need Windows verification. Backend code unchanged. Focused regression **16/16 C2 Python unittest PASS (0.943s)**; full Backend suite not rerun.

## Regression / architectural boundaries

- **No changes** to `C2TurnAwarePlanner`, `C2F2SafeShortcut`, hazard/terrain graph, C1 ticket ownership, C1 physics/`travel`, combat priority, motion result, C0 journaling, cancellation or backend AI.
- `C1P1MotionRuntime` sets physical movement yaw exactly as before for Follow and continues all swept collision and safety checks; presentation only sets `setYHeadRot` and `setYBodyRot`. Client blend affects a `RemotePlayer` only for the matching verified viewing token/UUID/entity id.
- Existing `C1P1MotionRuntime.turn` uses a single immediate authoritative yaw change (not altered). The visual head/body catches up; movement-ticket acquisition remains unchanged.
- No claims of a custom keyframe animation, universal animation parity, or a resolved visual bug until user Windows proof. No GitHub operations.
