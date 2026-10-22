# NPCortex C+ — Foundation-First Action Engine (C-F0)

Status: **F0 contracts only; NOT wired into gameplay**. Baseline: `NPCortex_AirborneFollowTargetProjection_Fix_FullRepository_CANDIDATE.zip`. Scope approved: F0-A..F0-G. No GitHub operations.

## Design goal

Qwen proposes intent; C+ will coordinate and validate; Minecraft server is the only authority for world outcomes. Retain existing C1 Custom ServerPlayer (physics, inventory, lifecycle), C2 (navigation/follow and C1 motion lease), and backend C0 as separate subsystems. **Never merge the implementations to create a unified command**. No new world-mutation skill, command registration, queue replacement, persistence migration, or live runtime integration is in C-F0.

## Source-audited dependency map (F0-A)

| Existing source | Actual responsibility | C+ integration boundary planned | F0 runtime change |
|---|---|---|---|
| `NPCortex.java` | Registers Brigadier `/npcortex` root, operator permission 2 and legacy C1/C2 command trees | Later command router; legacy aliases retained during migration | None |
| `C1P0Runtime`, `C1PlayerAvatar` | NPC identity/spawn and ServerPlayer lifecycle | NPC identity canonicalization, death/despawn invalidation | None |
| `C1P1MotionRuntime`, `C1F2ContinuousWalk`, `C1Persistent3DChain` | Player movement and motion ticket ownership | Lease/handoff request only; never C+ writes velocity or calls `Player.travel()` | None |
| `C1P1InventoryRuntime`, `C1InventoryJournal` | Inventory authority, idempotency ledger; 512 retained receipts | Action ID references existing inventory transaction; no second ledger | None |
| `C2AgentRuntime`, `C2Scheduler` | One active move-goal per actor, capacity 10; no FIFO pending queue | Coordination + scheduling contract, later compatibility adapter | None |
| `C2ActionJournal` | Per-NPC last journal row in SavedData; not fsync | Future durable/recoverable multi-action ledger migration, preserving old rows | None |
| `C2ActionVerifier` | Movement receipt/backend verification | Later typed verifier registry | None |
| `C2SkillRegistry`, `C2MoveExecutor` | Only `move_to` advertised as live Forge skill | Capability advertisement derived from usable executors | None |
| `C2WorldSensor` | Trusted sensor snapshot; block observation still empty | Later bounded semantic world snapshot | None |
| `backend/agent_contracts.py`, `agent_skills.py` | 6 KB snapshots, skill schema and goal success criteria | Separate C+ contract v1 module, not yet in service | None |

## Authority and collision matrix

| Contention | Source of truth | Future C+ rule | F0 testable artifact |
|---|---|---|---|
| Follow vs interact | C1 motion ticket and C2 Follow owner | Pause/finish/cancel with acknowledgement, never issue a second physical controller | `CPlusOwnership` |
| Block mutation vs world changes | Server-loaded world, permissions/Forge events | Validate loaded chunk and block state at execution; reject unknown | `CPlusActionContract` (declaration only) |
| Chest transfer vs inventory | C1 inventory and server container | Ordered locks and state recheck, never copy ledger | `CPlusResources` |
| Two NPCs on same block/container | Server-world resource | Stable order, conflict waits, bounded timeout; no double execution | Lock key sort contract |
| Player 1 vs player 2 tasks | Server request acceptance sequence per NPC | FIFO, no owner priority; validate actor/permissions | Queue ticket order contract |
| Qwen vs Forge outcome | Server World and trusted verifier | AI text or executor return is **not** success | `CPlusLifecycle` |
| Crash in world mutation | SavedData and later durable C+ journal | IN_DOUBT; reconcile before retry; never blind replay | `CPlusLifecycle` |
| Unsupported mod or unloaded chunk | Capability adapter/loaded-world check | Fail closed; never infer permission from block registry ID | Versioned future adapter interface |

## Non-negotiable invariants

1. A C+ envelope is not permission to execute. Server thread, live world, identity, capabilities, claims, and resources must all be revalidated by future runtime.
2. Actor motion has exactly one C1-owned ticket; C+ may request coordination through C2 but never issue raw movement commands.
3. Server truth, not planner text, yields a VERIFIED_WORLD_RECEIPT. `COMPLETED` is invalid without that proof.
4. Cancellation is not rollback. Cancellation after mutation must retain the actual outcome; `CANCEL_PENDING` must await terminal acknowledgement or trusted reconciliation.
5. Any potentially mutating `IN_DOUBT` action may not auto retry, even with a new attempt ID. Real crash consistency is **not certified** by C-F0.
6. World/dimension/actor identity is mandatory; immutable action/attempt IDs are distinct. `attempt_id` represents a specific try, not a new independent world-side task.
7. Resource acquisition always uses global deterministic order; no per-module raw lock held during callbacks. Locks need bounded leases and recovery in C-F3.
8. No snapshots may force-load/generate chunks. Unknown is not air; mod capability requires confirmed support.
9. Preserve C2 legacy names and save layouts until a migration is audited, tested and approved.
10. Budget target is 1/3/5/10 NPC; actual MSPT remains unverified until Windows Forge profiling.

## Foundation sprint sequence

- **C-F0 (implemented as proposed contracts only):** identities, action envelope, transition guards, resource/FIFO keys, responsibility and command routes, backend schema validator, docs, tests.
- **C-F1 (NOT APPROVED):** durable multi-action journal, idempotency fingerprint, crash/restart windows, reconciliation; no falsely promised global exactly-once across SQLite and Minecraft world.
- **C-F2 (NOT APPROVED):** loaded-chunk bounded perception + permission adapter + capability inspection (read-only first).
- **C-F3 (NOT APPROVED):** real queue, action runner, typed verifier, command router, resource manager and explicit C1/C2 handoff; legacy aliases.
- **C-F4 (NOT APPROVED):** Skill/Mod adapter API, registered capabilities, sample non-destructive adapter.
- **C-F5 (NOT APPROVED):** tick/backpressure budget, telemetry, fault injection and 1/3/5/10 NPC profiling.
- **C-F6 (NOT APPROVED):** end-to-end migration and Windows quality gate; thereafter separately approve world mutations.

## Out-of-scope protections checked by source diff

The new classes reside under `forge-mod/.../npcortex/cplus/`; the backend module is `backend/src/npcortex_backend/cplus_contracts.py`. None are called by the active C0/C1/C2 entrypoints. `NPCortex.java`, C1 movement/inventory, C2 follow/navigation/journal/registry and current persistence files are unmodified. The command surface below is DESIGN ONLY. See `ACTION_CONTRACTS.md` and `UNIFIED_COMMAND_API.md`.
