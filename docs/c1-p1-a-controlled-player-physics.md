# NPCortex C1-P1-A — B+ Controlled Player Physics (candidate)

Date: 2026-10-15. Status: **candidate / Windows Forge gameplay quality gate open**.

## Scope

- Use the already accepted `C1PlayerAvatar extends ServerPlayer` from C1-P0 Fix #2.
- Do not call `ServerPlayer.tick()` or `doTick()` while the synthetic passive listener is in use.
- Operator-only, bounded movement intents; direction and distance are inputs to a separate controller, not Minecraft Client input emulation.
- Use Vanilla `Player.travel(Vec3)` for physics and `Player.jumpFromGround()` for jumping. Neither the motion controller nor Qwen directly teleports the avatar each tick.
- Keep C1-P0 two-phase network tracking / viewer-scoped recovery unchanged; rely on Vanilla ChunkMap for movement replication. If client movement is not received, treat this as a failed acceptance gate and audit before adding more packets.
- Persist observed ServerPlayer position/yaw to existing C1 SavedData records without changing world/actor/profile UUIDs. Persist every 20 active movement ticks and on command completion, despawn or clean shutdown. A power loss between world saves can still lose recent motion (no crash-safe movement journal in P1-A).
- AI Scheduler, Qwen, Navigation3D, autonomous tasks, inventory, combat, TaCZ, damage/death and dimension lifecycle **excluded**.
- Gravity simulation is executed only while an intent or bounded settling is active. Idle avatars consume no per-avatar vanilla physics tick from this controller.

## Windows commands

From Repository root (`F:\AI\NPCortex`):

```powershell
.\scripts\test-c1-p0-sync.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

On a backed-up, flat Single Player test world with cheats enabled:

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 info yui
/npcortex c1 sync yui
/npcortex c1 motion yui
/npcortex c1 settle yui
/npcortex c1 turn yui 90
/npcortex c1 move yui forward 3
/npcortex c1 motion yui
/npcortex c1 move yui left 2
/npcortex c1 jump yui
/npcortex c1 stop yui
```

If an avatar is initially ungrounded, use `settle` first and confirm `grounded=true` before `move`/`jump`. `move` is local to its facing direction, not automatic navigation. Movement distance is limited to 0.5–8 blocks. Commands do not mine/place or load distant chunks.

## Acceptance checklist (user executes Minecraft on Windows)

1. **G0 Build**: `dev-forge.ps1 -Task build` and `runClient` complete. Paste compile/log output; syntax-only checks do NOT satisfy G0.
2. **P0 Regression**: initial spawn; `/npcortex c1 sync yui` reports `PRESENT`; exit/rejoin with same three UUIDs; no duplicates; repair still works; explicit despawn and re-spawn work.
3. **P1 Movement**: `/npcortex c1 settle yui` gives grounded state on solid flat ground. `/npcortex c1 turn yui 90` visibly turns the player model. `/npcortex c1 move yui forward 3` visually advances approximately 3 blocks, is not a teleport-per-tick, and stops. Confirm client view AND `/npcortex c1 motion yui` server position.
4. **P1 Jump/Gravity**: `/npcortex c1 jump yui` rises and falls to the ground; no hovering/ground clipping. Motion eventually becomes `JUMP_COMPLETE`.
5. **P1 Collision**: place a solid wall 2 blocks in front of NPC, issue `/npcortex c1 move yui forward 5`. NPC must not phase through; result should be `BLOCKED` or another bounded non-success, not `COMPLETE`.
6. **P1 Stop/Safety**: start an 8-block move; immediately `/npcortex c1 stop yui` before it reaches goal. Must stop horizontal motion and settle under gravity; no movement continues indefinitely. Test unloaded-chunk boundary: no force-loaded chunks.
7. **P1 Persistence**: after move, inspect `/npcortex c1 info yui` (stable World/Actor/Profile UUIDs, updated pos), quit/rejoin, verify the same positions within a reasonable autosave tolerance and same UUIDs. `info` gives persisted values; `motion` gives current live position.
8. **P1 Performance preliminary**: record MSPT, TPS and client-visible stutter with 1 and (only if safe) several NPCs active. The code does not claim measured 5/10/20 NPC scaling in this environment.

### Expected result interpretation

- `motion=COMPLETE`: server motion state claims distance intent completed; **requires visual and collision validation** before acceptance.
- `BLOCKED`, `TIMED_OUT`, `CHUNK_UNLOADED`, `SETTLE_TIMEOUT`: bounded stop/failure; do not claim movement success.
- `movement busy`/`not on ground`: a rejected command, not a success.
- `CLIENT_ENTITY_PRESENT`: client has the entity, but it does not prove client movement is synchronized, visually smooth or physics-correct.

### Known risks and limitations

- Forge/Vanilla ServerPlayer normally receives movement from a real client connection. This experimental synthetic player calls `Player.travel` from a server-side motion controller while keeping the passive listener; **only Minecraft gameplay will prove** that movement is simulated/synchronized correctly and animation renders naturally.
- The non-standard `tick`/`doTick` override from P0 remains. Player effects, swimming, fall damage, step sounds, fluids, sprint, sneaking, vehicles, elytra and advanced ground contact are **not verified**. Invulnerability remains a P0 feasibility protection; lifecycle/damage belongs to P1-C.
- Active physics runs at server tick frequency; decision/planning is not executed here. Motion intents expire after at most 240 active ticks + 100 settle ticks and stop on apparent collision after 16 ticks without forward progress. No chunk tickets or background scans.
- The idle synthetic entity does not receive a full Vanilla player tick. This is an explicit compromise for the feasibility scope and **not** yet full player-equivalent physics in every situation.
- Sending movement exclusively through Vanilla ChunkMap is deliberately a test hypothesis, not a confirmed claim. If client stays still while server moves, investigate tracking/packet behavior instead of asserting P1 passed.
- After severe crash, SavedData may lag behind the in-memory position. The movement state is not persisted/replayed as an action. No Exactly-once World Action semantics are claimed.
- Custom skin from P0 remains open. Multiplayer and Mod compatibility remain unverified.

## Candidate validation recorded in packaging environment

- Dependency-free `C1P1MotionPlan` state machine compiled with `javac --release 17` and its 29 assertions passed.
- Full Java 17 AST syntax parse is possible but does **not** resolve Forge API symbols.
- Python backend regression displayed **185 tests / 175 subtests passed**, but the process did not exit normally after printing the summary and was terminated by the execution time limit. Assertions passed; clean test-process completion is **not** claimed. C0 source and API are unchanged.
- Actual Forge compilation and Minecraft gameplay still require the user's Windows environment.
