# NPCortex GWI C-F3/C-F4 E — Gated Implementation Roadmap

**Baseline:** C-F2 D Trusted Perception full repository candidate. **Date:** 2026-10-23.

User selected E over D to see integrated tasks earlier. This approval **does not authorize bypassing server permissions or manufacturing verified world receipts**. We will build E as gated vertical slices, not silently deliver a half-functional all-skills engine as “complete”.

## E-P0 — Implemented candidate, read-only

`/npcortex debug cplus epreview yui` obtains an actual bounded 27-cell server sample and POSTs it over the authenticated local-only bridge to `/v1/cplus/e/preview`. Backend transactionally admits the task with actor FIFO sequence, persists snapshot+hash and three dependent *read-only* nodes, verifies its contract, and reports `READ_ONLY_VERIFIED`. This proves multi-step dependency ordering and persistent history; **it does not prove the block can be broken**. No tasks are automatically executed after crash/restart. Existing C1/C2/C-F1/C-F2 remain owner-authoritative.

## Next gates — NOT implemented in this ZIP

1. **E-P1 Durable action DAG/Leases:** real typed requests, bounded durable per-NPC FIFO, owner/actor/world/epoch fencing, stable resource leases, cancellation acknowledgement and a single authoritative coordination core. Restart -> HOLD until reconciliation. Source Audit & explicit approval for modifications beyond E-P0 before dispatch.
2. **E-P2 Navigation handoff:** strictly opt-in `move_to` using existing C2; Follow must stop/ACK before taking motion; trusted move verification, no duplicate controller. No mutation.
3. **E-P3 Authoritative permissions & first effect-aware Skill:** implement one real vanilla interaction through correct Forge/player event/claim compatibility, with original C1 inventory custody, preflight/commit/reobserve and `UNKNOWN→HOLD`; no auto-replay on ACK loss. First skill must pass fault and rollback-free compensation tests.
4. **E-P4 Additional skills:** Break/Place/Use/Pickup/Drop/Container using reusable adapters, vanilla + mod compatibility (especially claims/capabilities). Typed provenance and external-item consumption must not create items or steal from players.
5. **E-P5 Autonomous orchestration & reliability:** limited approved graph goals via Qwen, per-player FIFO admission and multi-NPC locking; crash/Save-Rejoin/dedicated server acceptance, real 1/3/5/10 NPC MSPT, regression and mod fault matrices; C-F5/F6 quality gates.

## Invariants

- C2 and C1 own movement and inventory, respectively. C+ never directly overwrites velocity or inventory.
- The existing C-F1 journal records synthetic effects only; must not be mistaken for real-world action journal or exactly-once semantics.
- C-F2 mutation permission `UNKNOWN` is **not** an ALLOW. No world effect if current authorization cannot be confirmed.
- Durable ACK only acknowledges data transport, never a completed Minecraft skill. No automatic replay of uncertain mutation.
- Backend endpoint uses Local Auth and loopback; legacy unauthenticated C0 paths remain unchanged, not granted world-action authority.
- All Python/Forge unit test and gameplay PASS claims remain separate; real MSPT cannot be inferred from synthetic 1/3/5/10 tests.
