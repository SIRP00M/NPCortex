# NPCortex C+ — F0-B/C/D/E Action Contracts v1

Status: **implemented pure Java + backend schema guards, but not invoked by game/backend service**. Not a commitment to changing existing C0 messages until future migration approval.

## Wire envelope v1 (draft implemented validator)

```json
{
  "protocol_version": 1,
  "world_id": "11111111-1111-4111-8111-111111111111",
  "dimension_id": "minecraft:overworld",
  "actor_id": "22222222-2222-4222-8222-222222222222",
  "goal_id": "33333333-3333-4333-8333-333333333333",
  "task_id": "44444444-4444-4444-8444-444444444444",
  "action_id": "55555555-5555-4555-8555-555555555555",
  "attempt_id": "66666666-6666-4666-8666-666666666666",
  "skill_id": "minecraft:use_block",
  "target": {"kind": "block", "position": [120, 65, -84]},
  "expected_outcome": {"kind": "npcortex:block_state", "key": "open", "expected_value": "true"}
}
```

- All UUIDs canonical lower-case non-nil. Action/attempt must differ. World, dimension and actor never change across attempts of the same action; enforcement across attempts requires the C-F1 durable ledger.
- `skill_id`, `dimension_id` and outcome `kind` use namespaced IDs. `target.kind` accepts `block`/`position` (bounded 3 integer coordinates), `entity` (UUID) or `none`.
- Unknown fields, unsupported protocol versions and missing keys are rejected. `expected_outcome` is **a proposed check**, not evidence.
- Java: `CPlusIdentity`, `CPlusActionContract`. Python: `cplus_contracts.validate_action_contract`. Neither is currently used by the gameplay service. Schema equivalence across serialized JVM and Python representation needs future integration tests.
- This wire envelope is not yet subject to the existing C0 8192-byte HTTP envelope; actual transport/budget mapping must be verified at integration, especially 6 KB snapshot limit.

## State machine (F0-C)

Happy path: `REQUESTED → VALIDATED → PREPARED → EXECUTING → VERIFYING → COMPLETED`. Optional `WAIT_RESOURCE` before PREPARED, `CANCEL_PENDING`, `IN_DOUBT` and terminal `CANCELLED`/`FAILED`.

Guarded edges:

- `VERIFYING → COMPLETED` requires `VERIFIED_WORLD_RECEIPT`, with a nonblank evidence ID from trusted server verifier.
- `CANCEL_PENDING → CANCELLED` requires `CANCEL_ACKNOWLEDGED`, not just a planner or operator request.
- `IN_DOUBT → VERIFYING` and `CANCEL_PENDING → VERIFYING` require `TRUSTED_RECONCILIATION` with evidence ID.
- `EXECUTING → VERIFYING` is only an evaluation transition; not proof of success.
- `COMPLETED`, `CANCELLED`, `FAILED` are terminal for this attempt. Terminal actions must not be resumed implicitly.
- Automatic retry in F0 policy only for non-mutating terminal FAILED/CANCELLED; actual retry scheduling is a C-F3 responsibility.
- A status is not proof of durability. Future C-F1 must define journal write points, acknowledgements and reconciliation when world changes occur between write/save/HTTP.

## Resource and FIFO contract (F0-D)

Keys contain `(world UUID, dimension ID, kind, locator)` and a globally stable deterministic order `world → dimension → kind → locator`, with bounded acquisition sets (<=16). Available kinds: `ACTOR_MOTION`, `ACTOR_INVENTORY`, `BLOCK`, `CONTAINER`, `ENTITY`. C1 holds actual motion/inventory ownership; a C+ reservation **never grants** raw C1 access. Real lock/lease/timeout/renewal not implemented in F0.

A FIFO `QueueTicket(actor UUID, task UUID, acceptedSequence)` is ordered for requests of the **same NPC**; tickets from different actors cannot be compared as a single actor queue. The future scheduler must atomically assign unique sequences and resolve duplicate actor/task/replay, queue limits and disconnected requesters; F0 only tests the ordering contract.

## Persistence/migration plan (F0-E; NOT executed)

1. Record existing `C2ActionJournal` NBT rows (last state per actor) and `C1InventoryJournal` bounded receipt records before any schema change.
2. Future C-F1 must define a new versioned durable ledger without overwriting either old SavedData key. On startup, old unresolved C2 rows remain fail-closed; never assume they were not executed.
3. No blanket auto-resume from old saves. Provide read-only legacy status and explicit reconciliation path (world evidence / old terminal receipt / operator resolution).
4. Inventory receipts stay with C1 and referenced by C+ action ID/fingerprint; C+ may not re-issue a successful C1 inventory transfer under a different transaction ID.
5. Treat SQLite and Forge SavedData as independent persistence boundaries: their commits are not atomic. Exactly-once world effects across a crash are not promised. Test crash windows and abort or mark `IN_DOUBT` when evidence is insufficient.
6. Migrate only with a separately approved plan for backups, roundtrip testing, corruption handling and rollback/old-version limitations. No migration runs in C-F0.

## Error taxonomy (contract requirement, not live implementation)

- `INVALID_IDENTITY`, `UNSUPPORTED_SCHEMA`, `UNSUPPORTED_SKILL`, `PERMISSION_DENIED`, `UNKNOWN_WORLD`, `STALE_OBSERVATION`, `RESOURCE_BUSY`, `OWNER_CONFLICT`, `PRECONDITION_FAILED`, `EXECUTION_FAILED`, `VERIFICATION_FAILED`, `RECONCILIATION_REQUIRED`, `TIMEOUT`.
- External failures may be **nonterminal uncertainty**; never convert to `FAILED`/`COMPLETED` solely because an HTTP request timed out. C-F1/C-F3 must map each concrete source into one typed error and stable action state.
