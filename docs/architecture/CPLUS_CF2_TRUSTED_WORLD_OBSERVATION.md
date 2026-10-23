# NPCortex GWI C+ C-F2 D — Trusted Perception + Permissions (P1–P5)

Status: **implementation candidate, READ-ONLY; Windows Forge and gameplay acceptance pending**.
Baseline: C-F1 Clean Baseline ZIP; C1/C2 physics, follow, inventory, movement ownership, legacy C0 snapshot and C-F1 journal/transport remain unchanged. **No GitHub operations.**

## Decisions and strict boundaries

1. **P1** Observation v2 has explicit `schema_version=2`, world/dimension/actor/session/observation IDs, server capture tick, source, fixed 3×3×3 cells, coverage, nearby entities/drops with truncation, permission decision and binding to same evidence ID/tick. The Python validator is in `cplus_f2_observation.py`; C0 v1 remains strictly unchanged, with an explicit `legacy_v1_view` adapter that never grants new skills. The v2 contract is **not yet an authenticated new network API**.
2. **P2** Forge `CPlusF2WorldSampler` captures live C1 avatar on the Minecraft **server thread only** using loaded-only chunk checks. Never calls `getChunk`, edits blocks, opens block-entity inventory, requests forced loads, mutates Entity/ItemStack, or evaluates action event hooks. Samples 27 positions around NPC feet; captures up to 8 nearby drops and 8 other entities, with truncation flags. Drops have identity/location/count only: absence never proves collection, deletion or external consumption.
3. **P3** `CPlusF2ReadOnlyPolicy.decide` is a **diagnostic pre-decision**, not execution authority. `ALLOW` is only used for `observe` when a cell is observed; `break_block`, `place_block`, `use_block` default `UNKNOWN` even when seen, since claim/protection mods have not been verified. `OUT_OF_HEIGHT/OUT_OF_BORDER` => DENY; `UNKNOWN_CHUNK` => UNKNOWN. No mutation adapters and no new skill advertised. At future action time, the authoritative server must re-evaluate both target and permissions freshly.
4. **P4** Operator debug `/npcortex debug cplus world <npc>` is read-only: prints center/status/tick/permission in chat; detailed v2 JSON is only in local server log and never submitted to legacy unauthenticated HTTP endpoints. v2 `capture_tick`, `session_id` and `observation_id` allow future C-F1 evidence correlation; they **do not change C-F1 receipts** and **cannot mark effects VERIFIED**. A world-save/rejoin must not reuse old session data. This is **diagnostic evidence only**, not a durable v2 network bridge.
5. **P5** Java isolated policy regressions + Python strict contract/fault tests + synthetic actor-isolation checks. No claim of Forge build, Windows game performance or claim-mod integration until native testing.

## Fields and semantics

- Every block record is `position, status`; observed cells alone contain `block_id, properties`; unknown cells **cannot claim air** or a block ID. Coverage must contain all 27 distinct positions, but can include unknown cells due to unloaded chunk/out-of-bounds. The scanning window cannot silently omit cells.
- `source=SERVER_WORLD` is **metadata only**. JSON text cannot authenticate itself; future authenticated transport must bind the sender to real server identity before granting any authority.
- The permission decision is anchored to the center block, with `evidence_id=observation_id`, `checked_tick=capture_tick`. Current `break_block` default is `UNKNOWN` (not ALLOW) unless a world bound is violated, which is DENY.
- Capture is **at most 16 KB**; large modded properties or high-density scenes cause fail-closed/HOLD, not silent truncation of critical fields. Nearby entity/drop lists are explicitly truncated with flags. Scope is local, not global perception.
- v2 future freshness: exact world/dimension/actor/session, current tick >= capture tick, age <= 20 ticks for a read-only displayed observation. Freshness does not make a mutation permissible.
- No universal protected-claim adapter exists in this sprint. "UNKNOWN" should be considered no execution permission. Minecraft/Forge/modded interaction hooks may change permissions after observation.

## Milestone ordering

This is D (sensors + permission evidence) only. E is a **future target**: C-F3 scheduled/verified execution and C-F4 registered Skills / mod adapters, followed by C-F5 performance and C-F6 GWI acceptance. Do not implement E's mutation skills by skipping unclosed foundations.

## Known limitations

- No persistent v2 world observation cache/ledger, v2 HTTP endpoint, asynchronous v2 stream, target tracking, owner/claim mod adapter, E executor, or authoritative world-effect completion verification in this candidate.
- P2 item/entity sampler is bounded in world radius and output list, but Forge query internals may allocate proportional to local entity density. True 1/3/5/10 NPC MSPT and GC must be measured on Windows.
- No real Forge compilation in this environment (Gradle wrapper cannot download distribution); Minecraft validation and other mod compatibility are pending.
