# Sprint 2 — Vanilla 3D Motion Primitives — Windows Acceptance

**Status: Candidate. Forge Gradle build and real physics gameplay NOT YET VERIFIED.**

## Commands

```powershell
cd F:\AI\NPCortex
.\scripts\test-terrain-s2.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

No Backend/Ollama required for these **operator-only C1** fixtures. Use Creative, peaceful terrain and a known-alive spawned Yui. No `/npcortex c2 navigate`/3D routing is expected in Sprint 2. `/npcortex c1 motion yui` reports the terminal outcome, position and grounded state. Take screenshots/logs of results.

**Orientation:** `/npcortex c1 turn yui 0` means `forward` is **+Z**. `/npcortex c1 info yui` shows coordinates; construct fixtures relative to Yui's **actual X, footY, Z**, ideally centered on X/Z block centers. `c1 traverse` moves relative to NPC facing, not the operator's facing.

| Test | Fixture / command | Required acceptance |
|---|---|---|
| Flat regression | `/npcortex c1 move yui forward 1` | `COMPLETE`, same vanilla movement behavior |
| STEP_UP | Half-slab supported at +Z, 0.5 higher; `/npcortex c1 traverse yui step_up forward 1.0` | `TRAVERSAL_COMPLETE_STEP_UP`; final grounded; Y+0.5 |
| STEP_DOWN | Safe support 0.5 below at +Z; `/npcortex c1 traverse yui step_down forward 1.0` | `TRAVERSAL_COMPLETE_STEP_DOWN`; Y-0.5 |
| JUMP_UP | Full 1-block ledge at +Z; clear takeoff/headroom; `/npcortex c1 traverse yui jump_up forward 1.0` | `TRAVERSAL_COMPLETE_JUMP_UP`; airborne detected; final Y+1 |
| SAFE_DESCEND | Safe support 1 block below at +Z; `/npcortex c1 traverse yui safe_descend forward 1.0` | `TRAVERSAL_COMPLETE_SAFE_DESCEND`; observed fall and grounded landing |
| Stairs | Stair collision shape, multiple orientations: try 0.5-step `step_up` where supported | Real position and collision verified; no phasing or false `COMPLETE`; conservative refusal is reported and investigated |
| Impossible ledge | 2-block wall in path | Refused/blocked/failed, no teleport, no false completion |
| Low ceiling | 1-block headroom above takeoff or destination | Refused before motion or fails safely |
| Missing support | Remove target floor then command descend | Rejected as `UNSAFE_LANDING` / `LANDING_HEIGHT_MISMATCH` |
| Hazard | Magma/lava/water at landing | Rejected (no false safe goal) |
| World mutation | Remove/replace target after motion starts | Must never claim `TRAVERSAL_COMPLETE_*` if target becomes unsafe |
| Cancel in air | `/npcortex c1 stop yui`, then `/npcortex c1 motion yui` | Stops commanded input, preserves vanilla gravity; terminal `CANCELLED` once grounded |
| Knockback | Trigger Vanilla combat impulse during traverse | Combat priority; terminal interrupted, not a false verified traversal |
| Death/despawn | Remove actor mid-route | No stale C1 session, no resumed motion |
| Unloaded boundary | Target a legitimately unloaded neighboring chunk | Fail closed; no intentional chunk loads |
| Multiple NPCs | 1/3/5/10 NPC operator sessions | Isolation and live MSPT measurements; report unknown until tested |
| C2 regression | Run ordinary flat `/npcortex c2 navigate` with backend online | Original 2D navigation and cancellation still work; **not 3D navigation** |

## Expected terminal receipts

- `TRAVERSAL_COMPLETE_<KIND>` only after confirmed grounded, correct X/Z/Y, and landing terrain recheck.
- `TRAVERSAL_BLOCKED`, `TRAVERSAL_LANDING_MISMATCH`, `TRAVERSAL_INTERRUPTED_COMBAT`, `TRAVERSAL_LANDING_CHANGED_OR_UNSAFE`, `CANCELLED`, `TRAVERSAL_TIMEOUT` etc. are safe failure outcomes; they are not PASS for reachable fixtures.
- Early `S2 UNSAFE_LANDING`, `S2 LANDING_COLLISION`, `S2 JUMP_HEADROOM_BLOCKED`, `S2 CHUNK_UNLOADED/OUT_OF_BOUNDS` are intended fail-closed responses for unsafe fixtures.

**Scope restrictions:** no new planner edges, no A*/hierarchical changes, no AI navigation integration, no teleport/setPos, no force chunk loads, no GitHub operations. Sprint 3 requires separate approval.
