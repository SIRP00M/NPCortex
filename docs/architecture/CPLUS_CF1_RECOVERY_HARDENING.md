# NPCortex C+ C-F1 D+ Recovery Hardening H1-H5

Status: implementation candidate (2026-10-22). Source baseline: Automatic Local Auth Full Repository Candidate. Operator-only, read-only/synthetic. No GitHub.

## Ownership and invariants

- `CPlusDurableLedger`: immutable, world-bound, SHA-256-chained journal and explicit `force(true)` on **journal worker**. Remains the authority for synthetic intent and fence; no C1/C2 owner changes.
- `CPlusF1AsyncDiagnostics`: bounded **journal worker (32)** solely for durable append/open/inspect/snapshot, and distinct bounded **network worker (8)** for HTTP, cursor writes, and read-only recovery comparisons. A stalled backend does not occupy the journal worker. Thread shutdown waits bounded periods; ambiguous I/O remains operator-held after restart.
- `CPlusF1Delivery.Source`: immutable event/record-hash snapshot taken on journal worker, delivered on network worker. Manual `sync` remains opt-in and bounded to 8 records. Journal is still bound by existing 32 MiB guard, so this is **not** an unlimited transport stream.
- Cursor: `<world>/data/npcortex/cplus_f1_delivery.cursor` records the locally durable, hash-verified Backend ACK. `.tmp` **must not be auto-deleted** after failure; an orphan causes `HOLD` and requires backup/operator review. Atomic rename alone is not a power-loss-fsync guarantee for the containing directory. Missing cursor means idempotent redelivery, NOT world-action replay.
- `syncstatus`: now asynchronous, reads ledger and durable cursor, returning local world-wide checkpoint and pending count derived from disk. Not authoritative for backend liveness; `audit` compares backend status separately.
- `audit`: `/npcortex debug cplus audit <npc>` authenticates with existing Automatic Local Auth and POSTs read-only `/v1/cplus/f1/status`. Compares world ID, remote sequence, hash, local cursor; `HOLD` for inconsistent state, `PENDING` for lagging transport, `IN_DOUBT` for unfinished actions, `READY` only for a consistent transport checkpoint. **READY does NOT certify world effect completion.** Audit is world-wide, regardless of NPC name used to authorize/debug it.
- Backend `DurableBridge`: existing SQLite WAL synchronous FULL; event/ACK durable before success response, duplicate dedupe preserved. No backend schema/protocol changes in H1-H5.

## Fail-closed matrix (operator-only)

| Condition | Decision | What is NOT done |
|---|---|---|
| Slow/offline Backend | Delivery `HOLD`; local journal remains writable via separate worker | No fake ACK; no automatic network retry |
| Orphan `.tmp` | Cursor `HOLD` preserving file | No silent deletion or truncation |
| Broken cursor / hash mismatch | Cursor `HOLD` | No moving checkpoint forward |
| Remote checkpoint behind cursor | Recovery `HOLD` | No speculative Backend reset |
| Remote hash/world mismatch or ahead of Journal | Recovery `HOLD` | No remote authority assumption |
| Undelivered sequence | Recovery `PENDING` | No world action replay |
| Unfinished Action | Recovery `IN_DOUBT` | No auto execute/undo |
| Corrupt Journal | Journal `ERROR` fail closed on inspect; no export | No auto repair |
| Backend SQLite commit then process kill | Redelivery yields same durable ACK | No second Event insert |
| Disk/SQLite IO failure | No ACK returned | No checkpoint advancement |

## Known limits and future work

- **No actual world/inventory authoritative observation** yet; effect-aware/compensating task-level logic belongs to C-F2/C-F3/C-F4.
- No world mutation Executor, no Saga, no automatic World Action Replay, no automatic transport/retry background worker.
- This implementation cannot guarantee across-world-DB exactly-once effects or power-loss durability of all filesystems.
- Real process-kill testing after SQLite commit is included via separate child-process tests; Minecraft process-kill during Journal fsync and real Windows power loss remain separate acceptance gates.
- Windows Temurin17 Forge build, in-game diagnostic tests and 1/3/5/10 NPC real Forge MSPT must be verified independently.
