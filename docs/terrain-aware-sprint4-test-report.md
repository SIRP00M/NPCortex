# NPCortex — Terrain-Aware 3D Traversal Sprint 4, Architecture C — Test Report

**Candidate, not Full Quality Gate PASS.** Date: 2026-10-20. Baseline: Sprint 3
Full Repository Candidate. Strictly S4-A..D (recovery, lifecycle, tests/docs).
No GitHub activity, new AI skill, multi-chunk navigation, change to C1 travel()
physics, C0 backend schema or new autonomous goal source.

## Implementation

- **S4-A**: Added pure Java `C2S4RecoveryPolicy` typed terminal classification,
  exact C1 ticket ownership checks, safe retry vs fail-closed behavior. 3D landing
  mismatch, changed landing, timeout and combat-interrupted terminal receipts
  are eligible only for a *new bounded route*, never treated as completion.
  `CHUNK_UNLOADED`, `CANCELLED`, invalid receipt and foreign tickets remain fail-closed.
- **S4-B**: existing P1-D one-at-a-time bounded global replan kept. Added a
  max-100-tick stable-ground recovery gate requiring 3 successive grounded,
  loaded, supported server ticks AFTER the C1 motion token has ended, with
  fresh Terrain sensor and 3D B3 graph before issuing a new C1 ticket.
  If no safe pose materializes, action fails without faking success.
- **S4-C**: Preserve C0 Goal/Action ID and Vanilla Combat priority. Normal
  server shutdown requests the C1 motion stop and persists `CANCEL_PENDING`
  for action-owned runs; existing async C0 cancellation must confirm terminal
  and no pending action before the actor can get new C2 motion. On new server
  startup, abandoned `MOVING`/`REPLANNING`/`RESULT_PENDING`/`DECIDED` rows
  with known action and no local owner similarly enter reconciliation; they
  are **never** resumed. HTTP registration uncertainty remains fail-closed.
  Expanded no-active-C1 and grounded checks around existing cancellation.
- **S4-D**: self-test, Windows acceptance checklist, test script and carryover
  limitations documented. No claims for 1/3/5/10 Forge MSPT without real measurements.

## Tests actually run here

| Item | Outcome | Evidence boundary |
| --- | --- | --- |
| S4 pure Java self-test | **PASS**, 696 assertions | Failure matrix, receipt ownership, 1/3/5/10 actor-isolation matrix, stable ground, restart candidates |
| S3 pure Java Navigation | **PASS**, 104 assertions | Synthetic terrain/waypoint, not actual Minecraft |
| P1-D pure Java Reroute | **PASS**, 27 assertions | Synthetic replanning |
| P1-D Policy | **PASS**, 160 assertions | Bounded cooldown/search |
| C2 Backend targeted unittest | **PASS**, 16 tests | Cancellation / C2 contracts (unmodified Backend) |
| Backend full suite | **PASS**, 201 tests | First invocation timed out; complete retry ran 201 tests in 36.364s: OK |
| Real Forge build | **BLOCKED**, not passed | Gradle 8.8 download failed: `UnknownHostException: services.gradle.org` |
| Java full isolated regression | **PASS**, 23 suites / 4,439 assertions | Java 17 source target; includes C1 Combat/Motion, B1-B3, Terrain, C2 P1-C/P1-D and S4 |
| Java API-to-Forge compile | **NOT VERIFIED** | Pure Java does not replace Forge compilation |
| In-game 3D recovery and performance | **NOT TESTED** | Windows user must run |

Previous user-confirmed gameplay on Sprint 3 candidate: automatic JUMP_UP (+1.0)
and SAFE_DESCEND (-1.0) in separate Minecraft fixture scenarios PASS. These
are baseline evidence, **not** proof of Sprint 4 recovery behavior.

## Fail-closed limitations, intentionally left open

- Crash after Forge SavedData writes but before flush may not persist the newest
  journal row (SavedData != fsync or exactly-once). No crash injection acceptance.
- An in-flight create-goal HTTP request where Forge has *no known action ID*
  cannot be automatically reconciled safely; keep the Journal uncertain.
- Partial/mixed stair geometry can return NO_PATH; no broad permission to
  traverse unsupported edges. STEP_DOWN -0.5 still lacks gameplay acceptance.
- No realistic fakeplayer combat, cancellation-in-air, unloaded chunk, SMP or
  server MSPT measurements were performed in this environment.
- Backend C0 cancellation response may be temporarily unavailable; leave
  `CANCEL_PENDING` and retry asynchronously instead of manually clearing.

## Reproduction on Windows

```powershell
cd C:\path\to\NPCortex
.\scripts\test-terrain-s4.ps1
.\scripts\dev-forge.ps1 -Task build
# Terminal 2:
.\scripts\dev-backend.ps1
# Terminal 3:
.\scripts\dev-forge.ps1 -Task runClient
```

See `docs/terrain-aware-sprint4-windows-checklist.md` for acceptance, incl.
MSPT and separate PASS/FAIL/NOT TESTED result categories. **Do not push** before
Windows Forge Build, Gameplay, regression, and agreed final quality gates pass.
