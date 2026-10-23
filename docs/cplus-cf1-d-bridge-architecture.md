# C-F1 D+ — Diagnostic Transaction Bridge (D1–D5)

**Scope:** Approved D1–D5 on the Windows-safe SQLite Fix #1 baseline; synthetic/read-only only. **NOT** a world-mutation recovery engine or distributed atomic commit.

## Ownership

- **Forge `CPlusDurableLedger`:** immutable per-world binary journal; the only source of original event intent. Java `DataOutputStream` v1 frames are chained with SHA-256 of `previous_hash || frame_payload`, durable only after `force(true)` on diagnostic IO worker. No action replay.
- **Forge `CPlusF1AsyncDiagnostics`:** existing bounded (32 pending tasks) worker; new operator-triggered `sync()` uses at most **8 events per command**, local-loopback HTTP, 3-second per-event timeout; a network failure leaves local journal READY and transport HOLD. `CPlusF1Delivery` persists a per-world delivery cursor after a valid Backend ACK. Backlog survives process restart in the unchanged original journal. There is no autonomous polling/retry loop in this stage; reissue `sync` explicitly.
- **Backend `DurableBridge`:** additive SQLite tables `cplus_f1_bridge_inbox`, `cplus_f1_bridge_checkpoint`, `cplus_f1_bridge_receipts`. Incoming event, sequence checkpoint and PENDING receipt commit in **one WAL synchronous=FULL SQLite transaction**. Returns ACK only after commit, no effect execution. Forge persists cursor after ACK, then confirms the receipt via another authenticated endpoint; lost confirmation is retried from the saved cursor. Backend confirmation is not a world-effect receipt.
- **C0/C1/C2:** unchanged: C1 Inventory Journal remains inventory authority; C2 Navigation/Follow/Cancel remain motion/cancellation authority; Backend old `cplus_f1_events/outbox` staging schema remains intact and not reinterpreted. New bridge inbox is a separate additive projection, not an in-place migration or replacement.

## Wire schema (v1)

`POST /v1/cplus/f1/events` with header `X-NPCortex-CPlus-Token` and JSON:

```json
{
  "version": 1,
  "previous_hash": "<64 lowercase hex>",
  "record_hash": "<64 lowercase hex>",
  "event": {
    "sequence": 1, "event_id": "<uuid>", "world_id": "<uuid>", "actor_id": "<uuid>",
    "task_id": "<uuid>", "action_id": "<uuid>", "attempt_id": "<uuid>",
    "parent_event_id": null, "epoch": 1, "stage": "FENCE",
    "fingerprint": "<64 lowercase hex>", "evidence_id": ""
  }
}
```

Server strictly checks Java modified UTF-8 binary frame hash parity, journal global per-world sequence and hash chain, world-scoped actor fencing, duplicate IDs and stage/identity guards. Unknown schema and payloads larger than 8192 HTTP / 4096 event JSON bytes are refused. Journal hash and transport token are *integrity/authentication guards*, not proof that an action changed Minecraft's world.

### Endpoints, all POST, localhost-only + opt-in secret

- `/v1/cplus/f1/events` → `{status:"ACKED",duplicate,event_id,sequence,receipt_id}`; an ACK means only **SQLite inbox committed**.
- `/v1/cplus/f1/confirm` → confirms persisted Forge delivery cursor (idempotent); never confirms a world effect.
- `/v1/cplus/f1/status` `{world_id}` → backend sequence, hash and counts.
- `/v1/cplus/f1/recovery` `{world_id}` → **read-only** last-action classification plus number of PENDING receipt confirmations, `can_replay_world_actions=false`, `has_authoritative_world_observation=false`.

Configuration: `NPCORTEX_CPLUS_F1_TOKEN` of >=24 characters must be set to the **same unpredictable value** in backend and Minecraft processes. Never commit or paste the secret into logs. Backend client must be loopback IP; Forge configured backend origin must be `http://127.0.0.1[:port]` or `http://localhost[:port]`; no remote/TLS support in this diagnostic stage. Legacy HTTP routes retain their existing behaviors. Do not expose backend service on a public interface.

## Read-only Recovery Classification

Backend `recovery(world_id)` classifies each action by its *last journal event*: `PREPARED` (only intent), `PARTIAL` (observed partial/external), `IN_DOUBT` (effect may have occurred), `VERIFIED` or `CANCELLED`. This is a **journal-state classification**, not authoritative world/inventory inspection. A journal `VERIFIED` from a future executor cannot be trusted as proof of real side effects until Forge-based observation verification is implemented. No retries, compensations, undo, or gameplay commands derive from this report.

## Crash windows (no exactly-once claims)

| Crash position | Conservative behavior |
| --- | --- |
| Before Forge journal fsync | No durable intent ACK, no permitted effect |
| After local fsync, before backend send | Original ledger persists; explicit sync resends |
| Backend SQLite commit, response lost | Forge retains old cursor; redelivery returns idempotent ACK |
| After received backend ACK, before cursor replace | Cursor old; idempotent redelivery |
| After cursor replace, before confirmation HTTP | Next sync re-confirms saved cursor |
| Backend removed/rewound while Forge cursor advanced | ACK/receipt mismatch ⇒ HOLD, no silent reset |
| Journal, cursor or schema corrupted | HOLD; operator retains evidence and handles manually |
| Actual world mutation before world save | **Not covered by D1–D5**; must enter IN_DOUBT pending authoritative World/Inventory evidence |

Cursor uses temp-file force + atomic rename where supported. Directory metadata durability and true Windows power-loss behavior are **not** proven by these synthetic tests. Journal is immutable and the source of truth for redelivery; duplicate event storage and duplicate ACK are idempotent.

## Future E compatibility

Action/task/attempt/parent event IDs, ordered immutable Event History and effect categories are retained; Task DAG, provenance of drops/pickup by other NPC/hoppers, and Saga compensation are **not** implemented. Never invent drop provenance or remove items held by other actors. Pause/replan based on authoritative evidence only in separately approved later work.

## Quality Gate scope

Focused pure Java, Python, cross-language golden parity, HTTP real-loopback, duplicate/stale/gap/auth and synthetic offline tests are runnable. Pending: Windows Temurin 17 Forge compilation, actual in-game commands, Save/Rejoin after bridge delivery, true process crash/power outage, disk-full/partial-writes, full backend suite, actual 1/3/5/10 NPC MSPT, and all world mutation recovery.
