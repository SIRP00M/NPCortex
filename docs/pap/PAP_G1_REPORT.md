# PAP-G1 journal benchmark report (2026-10-28, test-only)

**Terminology.** What was measured is *serialization* of the production journal/SavedData classes with real NBT classes (build tag, gzip NBT write to a temp file, read, load). It is NOT real Minecraft save latency: `DimensionDataStorage`/`SavedData.save`, the server-thread schedule, MSPT, a dedicated server and live-world behaviour are NOT TESTED. Only the 512 column ran production journal code; 2048/4096 are SIMULATED. Column names below mean "NBT write", not "world save".

Isolation: the C1 benchmark compiles copies of `C1InventoryJournal`/`C1InventoryData` against the real Minecraft 1.20.1 jars (real `CompoundTag`/`ListTag`/`NbtIo`) and writes one disposable NBT file under a temp dir. It does not boot a game or world and never touches `run/` or a live world. The backend benchmark uses a temp SQLite file. Production sources were only read. Run: `scripts\pap\run-pap-g1-journal.ps1`, `python scripts\pap\pap_g1_ledger_growth.py`.

Labels: **PRODUCTION-512** = unmodified production sources. **SIMULATED-2048/4096** = temp copy with `MAX_RECEIPTS` rewritten; they are projections, not verified production behaviour. Limits: stub-free NBT is real, but `SavedData.save(File)` itself (DataVersion wrapper, server thread scheduling) was not exercised, and times are one machine, one JVM, 30 timed iterations after warm-up (median/p95).

## A. C1 receipts (per-actor journal; one SavedData file holds all actors)
Behaviour checks (519 / 2055 / 4103 checks, 0 failures at 512 / 2048 / 4096): NEW below the cap, FULL at the cap, REPLAY and CONFLICT still answered when FULL, commit at FULL throws and evicts nothing, restore round trip keeps exact replay, restore of cap+1 is rejected.

Size is linear: about 180 B raw and about 50 B gzip per receipt (shape: `native-pickup|<uuid>` fingerprint, ~70 char detail). Receipts are also the only thing that grows; the inventory snapshot is constant (41 slots).

| Cap label | Actors x receipts | raw | gz file | build tag | gz write | read | load |
|---|---|---|---|---|---|---|---|
| PRODUCTION-512 | 1 x 512 | 94 KB | 25.6 KB | 0.09 ms | 1.6 ms | 0.7 ms | 0.1 ms |
| PRODUCTION-512 | 10 x 512 | 942 KB | 254 KB | 0.77 ms | 14.1 ms (p95 15.1) | 4.9 ms | 0.6 ms |
| SIMULATED-2048 | 1 x 2048 | 371 KB | 101 KB | 0.27 ms | 5.5 ms | 2.0 ms | 0.3 ms |
| SIMULATED-2048 | 10 x 2048 | 3.7 MB | 1.0 MB | 1.3 ms | 53.8 ms (p95 56.7) | 14.9 ms | 1.6 ms |
| SIMULATED-4096 | 1 x 4096 | 739 KB | 201 KB | 0.31 ms | 10.9 ms | 3.3 ms | 0.4 ms |
| SIMULATED-4096 | 10 x 4096 | 7.4 MB | 2.0 MB | 2.9 ms | 108 ms (p95 143) | 30 ms | 3.1 ms |

Every save rewrites the whole file, so cost scales with actors x receipts, and `setDirty()` is hit by every inventory-changing action. Whether the write runs on the server thread was not measured here; if it does, a full 10-NPC file at 4096 is a ~110-140 ms NBT write (not a measured world save) (MSPT: NOT TESTED).

## B. Backend ledger growth (separate series, isolated SQLite, one actor, reserve/start/finalize each)
| Actions | DB bytes (incl. WAL) | per action | median ms/action by quarter | restart (new store) | errors |
|---|---|---|---|---|---|
| 512 | 1.1 MB | 2160 B | 20.5, 20.6, 20.8, 20.9 | 1.9 ms | 0 |
| 2048 | 4.1 MB | 1984 B | 20.7, 20.9, 21.5, 21.7 | 2.6 ms | 0 |
| 4096 | 8.0 MB | 1957 B | 21.0, 21.6, 23.3, 25.1 | 5.6 ms | 0 |

Rows grow 1:1 (runs, terminals, claims; none pruned). Latency is dominated by `synchronous=FULL`, with mild drift upward at 4096. This growth is independent of the C1 cap: the ledger has no cap and is not bounded by `MAX_FRAME`.

## C. What the numbers say (inputs for the Owner, not a selection)
1. A receipt is written only when an action changes inventory, and nothing is ever pruned. At 512, one NPC gets about 256-512 inventory-changing native actions in its lifetime (break+pickup of one drop can be two receipts) before such actions are BLOCKED. Any fixed cap only delays that. The real decision is cap plus a migration/retention path, which needs its own approval (I4).
2. Cost is not the constraint up to 4096 for a few NPCs (1 NPC: 11 ms write at 4096). It matters for many full NPCs sharing one file: 10 x 4096 is 2 MB and a ~110 ms rewrite. A per-actor file or incremental write would change that and is a persistence-boundary change.
3. The backend ledger is cheap (about 2 KB/action) and not the limiting factor at these sizes.
4. The "reserve 64" rule has no code or doc basis; its effect (earlier BLOCKED) can be argued only after the cap is chosen.

## D. Probes (new)
`scripts\pap\probe-pap-g1-bench.ps1` mutates a temp copy of the journal: FULL never reported, REPLAY reported as CONFLICT, and eviction at the cap were each DETECTED by the behaviour checks, and the control passed. (A first eviction mutant was MISSED because it sat behind the guard in `commit`, an equivalent mutant; it was replaced by one that removes the guard.)

## E. Status
PASS (executed): C1 journal behaviour 519 checks at the production cap; PAP-G0 static pins 8/8. SIMULATED (instrumented): 2048 and 4096 behaviour and sizes. NOT TESTED: real `SavedData.save`, server-thread cost / MSPT, dedicated server, crash during save, 1/3/5/10-NPC live patterns. No production file, live world or live DB was touched.

## F. Owner decisions
1. Choose the journal cap (512 stays, or a migration to 2048/4096 as a separate approved sprint) with a retention/migration plan. 2. Decide whether a per-actor save is needed before raising it. 3. Sign PAP-G0 invariants and keep the Use Item allow-list empty until evidence exists.
