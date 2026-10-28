# C2-P1-C — Windows Acceptance Checklist (Candidate; NOT YET ACCEPTED)

## 0. Environment / build

- [ ] Back up working B3 ZIP and unzip this C2-P1-C Full Repository candidate over `C:\path\to\NPCortex`.
- [ ] Java 17: `java -version` / `javac -version`.
- [ ] PowerShell `cd C:\path\to\NPCortex` then `.\scripts\test-c2-p1c.ps1`: must PASS P1-C protocol, B3/B2/B1/P1-A/C1 tests and Backend 193 cases.
- [ ] `.\scripts\dev-forge.ps1 -Task build`: must print BUILD SUCCESSFUL. A previously passed B3 build does NOT validate new C1/C2-P1-C changes.
- [ ] Start Ollama Qwen, `.\scripts\dev-backend.ps1` (separate window), `.\scripts\dev-forge.ps1 -Task runClient` (separate window).

## 1. First safe gameplay: ONE NPC, same-level grass

- [ ] `/npcortex c1 spawn yui` and `/npcortex c1 info yui`; use safe **actual feet-block coordinates**, loaded chunk, target within radius 6. Check `navhier` first.
- [ ] `/npcortex c2 navhier yui <X> <feetY> <Z>` returns PATH_FOUND.
- [ ] `/npcortex c2 navigate yui <X> <feetY> <Z>` starts one C0 goal/action and walks waypoint by waypoint.
- [ ] Repeat `/npcortex c2 status yui` (while moving and afterward); witness waypoint progress and final `COMPLETED`, position within tolerance and C0 `verified=true`.
- [ ] Path with a 2-block-high wall and a safe 1-block-wide opening: validate planned detour and actual walking without teleport or phasing.

## 2. Negative and recovery boundary checks

- [ ] Fully blocked/unsafe/unobserved target: `navigate` rejects at planning, NPC does not move.
- [ ] Close path mid-execution: NPC stops; no success, no automatic replanning. Report exact `C2` / `C1` phases and backend logs.
- [ ] Call `/npcortex c2 cancel yui` mid-segment; movement stops, no further segment is started, journal blocks a blind re-run.
- [ ] Verify a second simultaneous `navigate` or `/npcortex c2 move` cannot take control of the same NPC.
- [ ] Knockback from player/mob during a route: Vanilla physics still applies; no artificial motion ownership conflict.
- [ ] Death/despawn while walking: cancelled or interrupted without server crash.
- [ ] Save & Quit/rejoin while route pending: old route does NOT auto-reexecute; journal reconciliation required.
- [ ] Two NPCs with different goals: no mixed motion tickets, no cross-actor Action/Goal reports.

## 3. Compatibility & performance

- [ ] C1 Movement, Knockback, Death Presentation, Inventory Persistence, Rejoin Rotation regression still behaves as in B3.
- [ ] Record B2 vs B3 planner `elapsedMs`, P1-C segment timings, C1 motion latency and F3 Server MSPT. Test 1 / 3 / 5 NPC if convenient; 10 is still an open workload target, not an automatic requirement for candidate core acceptance.
- [ ] Preserve PENDING_TESTS.txt: real Unloaded Chunk Safety, per-position Partial Block/Terrain Accuracy, Per-Edge Collision Accuracy, concurrent MSPT are OPEN until real validation.

## Report back

Please send the PowerShell script last PASS/FAIL lines, Forge BUILD SUCCESSFUL (if gameplay is confirmed for THIS candidate, build is implied), and a chat screenshot/status for one successful detour plus a blocked path. Include any console stack traces. **No GitHub operations until Quality Gate accepted.**
