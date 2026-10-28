# C2-P0 — Architecture C Event-Driven Embodied AI (IMPLEMENTATION CANDIDATE)

**C2-P0 precision update (2026-10-16):** see [C2 Precision Movement](c2-p0-precision-movement-c.md) for the approved short-distance C2-only path and optional goal tolerance. The historical P0 distances and test notes below describe the earlier baseline, not the precision candidate.

**Baseline**: C1-R2 Fix #2 / Build Fix #1 Full Repository ZIP supplied by user.  
**Target**: Minecraft Forge 1.20.1, Temurin JDK 17, Python backend, local Ollama `qwen3:8b`.  
**Status**: Local code candidate, **NOT** Forge-build-verified or Windows Gameplay Accepted.

## Scope and architectural ownership

P0 builds the platform boundaries before adding further skills:

1. C1 Stored World/Actor/Profile UUID used as authority; explicit asynchronous registration with existing C0 `/v1/worlds` and `/v1/actors`. No name-derived or runtime-entity UUID substitute.
2. `C2Scheduler` owns bounded exclusive per-actor sessions (10 concurrent actors max); no multiplayer FIFO yet (future separate scope).
3. `C2Events` publishes typed bounded transition events; subscribers are synchronous server-thread listeners (do not block or mutate Forge world from listeners).
4. `C2SkillRegistry` declares real Minecraft executors. **Only `move_to` is implemented and advertised**. Future block/item/harvest Skills need individual audit, approval, preconditions, receipts and regressions.
5. `C2AgentRuntime` is the asynchronous state/orchestration layer. It sequences world/actor registration → create goal → trusted sensor observation → Qwen decision → registry executor → server-tick motion completion → new observation/result → C0 verified goal. C1 remains the sole Vanilla physics owner.
6. `C2ActionJournal` stores last per-actor Goal ID, Action ID, and phase in overworld SavedData; uncertain/interrupted results prevent automatic replay after restart. Explicit operator reconciliation/reset is required; never claim crash-proof exactly-once execution.
7. `C2MoveGeometry` verifies finite same-height 0.5..7.5-block world target and converts it into C1 forward/turn without pathfinding/teleportation.
8. `ChatBridge.c2Post` strictly allowlists C0 API paths and uses async `HttpClient.sendAsync`. All world reads/writes happen via `server.execute()` or C1's END tick; model/HTTP does not block the server tick.
9. `C2WorldSensor` reads actual live server position, dimension, saved C1 IDs and actual inventory counts. Blocks are **not scanned** (unknown, never invented `minecraft:air`). Only implemented executors are advertised. Backend validates skill args and postconditions again.

**C2-P0 is not full autonomous navigation, natural language spatial grounding, multi-agent social AI, block editing, combat AI, cross-dimension R3, or complete framework validation R4.** Foundation has modular extension points, not preimplemented future capabilities. The command is an operator-only fixed-coordinate test, not an AI understanding of natural language landmarks.

## Safety and recovery

- Permission level 2 `/npcortex c2` commands. Use Backend bound to `127.0.0.1` on the same trusted computer for singleplayer; do not expose unauthenticated C0 HTTP service to untrusted networks.
- Single 1.0-block Euclidean goal-radius: Qwen may only choose exactly the user-authorized target (<=0.05 position deviation). A stricter Forge controller checks this independently of the prompt.
- Target beyond C0's 1.5-block `move_to` precondition radius and within 7.5 horizontal blocks, Y within 0.65 block of the NPC, and destination chunk already loaded. Does NOT force-load; cannot find paths or deliberately cross obstacles.
- No async callback may control real NPC physics or inventory; adapter calls `C1P1MotionRuntime.turn` and `move`. Completion requires terminal C1 receipt **and** server position, then an independent C0 postcondition on a new snapshot.
- Blocked, timeout, death, cancel, unavailable backend, malformed response and uncertain delivery fail closed. If C0 may still hold a pending action, leave `INTERRUPTED`, not success. No automatic execution after restart.
- `/npcortex c2 reset <npc>` clears only Forge-side journal after investigation. It **does not** delete/reset or resolve a pending C0 goal; the old ID should be captured via `/npcortex c2 status` and investigated manually (GET `/v1/agent/goals/<uuid>`).
- `SavedData.setDirty()` is not fsync/atomic cross-process commit. Power-loss exact-once guarantees and an automated cross-runtime reconciler are NOT implemented; crash-injection remains an open acceptance item. Manual reconciliation is mandatory until this is solved.

## Build and isolated tests (Windows PowerShell)

```powershell
cd C:\path\to\NPCortex
java -version
javac -version
.\scripts\test-c2-p0.ps1
.\scripts\dev-forge.ps1 -Task build
```

Ensure backend is running and Ollama has the model:

```powershell
ollama list
ollama ps
.\scripts\dev-backend.ps1
# Another terminal, after successful Forge Build:
.\scripts\dev-forge.ps1 -Task runClient
```

**Operator fixture**: create flat safe terrain, enable cheats, spawn Yui.

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c1 motion yui
/npcortex c2 move yui 100 64 100
/npcortex c2 status yui
```

**Replace `100 64 100` with a point 2–5 horizontal blocks ahead of Yui, same Y, same dimension, loaded chunk**. Those numbers are only sample arguments: they will be rejected if beyond range.

Check `C2` in `/npcortex c2 status yui` until terminal: `COMPLETED` only if C0 action verified and goal completed; `FAILED` means known failure; `INTERRUPTED` needs manual reconciliation. Use `/npcortex c2 cancel yui` for user cancellation; only use `/npcortex c2 reset yui` after inspecting the backend goal. Run a new task with a new goal ID.

### Minimum gameplay acceptance cases

- Qwen chooses `move_to` via actual Ollama and NPC moves **without teleport**. Verify live server position and C0 completed goal. Compare `/npcortex c2 status yui` with `/npcortex c1 motion yui`, server console logs and screenshot/video.
- Qwen returns invalid/unauthorized target: executor rejects; no unauthorized move. Offline Ollama/Backend: no move and no false completed status.
- Place wall along straight line or walk toward obstruction: C1 reports BLOCKED/TIMED_OUT and C2 does **not** report `COMPLETED`; no crash, no world/chunk force-loading.
- Cancel while MOVING; damage NPC/death/despawn while MOVING; save & quit mid-request; reopen and inspect journal. No resumed movement or stale success. Pending backend action requires explicit investigation.
- C1 regressions: Spawn, re-tracking/rejoin, head/body rotation persistence, Movement/Jump, player/zombie knockback, collision, damage, death/poof/respawn, inventory persistence/replay.
- Basic resource checks with 1/3/5/10 active NPCs: record TPS/MSPT (e.g. `/forge tps` if available), C2 HTTP request latency, backend errors, server CPU/memory, and whether combat/movement/network regress. These are **not yet measured**.
- Dedicated server, multiplayer, modded items, cross-dimension and abrupt power-loss/kill remain outside P0 acceptance and need later explicit validation.

## Tests run while preparing candidate

- Pure JVM C2 geometry, bounded event history/subscription and per-actor exclusive scheduler: 32 assertions pass under `javac --release 17` (host Java 21; not Temurin 17).
- C0 backend focused regression: 69 unittest cases passed on host Python 3.13.
- C2 Backend service contract: 4 unittest cases passed using a mocked provider and sensor, real SQLite; not a Forge/Ollama live test.
- Forge `compileJava`: **NOT VERIFIED**. Gradle wrapper 8.8 unavailable and distribution download blocked before compilation. No claim that the Forge classes compile or launch.

## Future architecture upgrade gates

When P0 is actually accepted on Windows, propose separately: goal queue/policy (FIFO for multiple player requests), structured recovery state and durable cross-process journal, generalized observations, multi-step Nav/Pathfinding, Item/Block adapters, and eventually whole-tree/fancy-oak harvesting as its own Perfect Vertical Slice. No silent expansion before approval.

- Complete backend test discovery: **189 unittest cases PASS** (including existing B1/B2/B3 and the new C2 contract test; several expected negative-test server tracebacks are logged).
