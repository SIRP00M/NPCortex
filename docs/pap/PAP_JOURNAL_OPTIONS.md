# PAP scalable journal foundation: options A-E (audit, 2026-10-29)

Nothing here is wired into production. The accepted C1 journal (`C1InventoryJournal`, 512 receipts per NPC, fail-closed at the cap, never pruned) is unchanged; the cap was not raised. Evidence base: `PAP_G1_REPORT.md`, `PAP_G1b_REPORT.md`.

## Facts from the source that constrain every option
- The journal is per actor profile but persisted inside ONE overworld SavedData file together with each actor's 41-slot inventory snapshot. Every save rewrites the whole file: 10 NPCs x 512 receipts = 254 KB gz, 13.8 ms real save (measured, server thread).
- A receipt is committed only when an action changes the actor's inventory (native break with drops, pickup, place, `copyhand`). Attempt ids are random UUIDs; the fingerprint is `native-<kind>|<attempt>`.
- The backend ledger already keeps every attempt id with its exact terminal and receipt hash (`cplus_wn_*`, `cplus_pap_*`, about 2 KB per attempt, no cap, 2-6 ms restart sweep). It is durable (WAL, `synchronous=FULL`) and exact.
- `journalPrecheck` and `commit` run synchronously on the server thread and never wait for the network (a deliberate accepted rule).
- Persist-only repair needs a free slot and an exact REPLAY check first; REPLAY must be answered even when FULL (pinned by `test_pap_g0_contracts`).

## Options
| | Option | Exact NEW/REPLAY/CONFLICT for all history | Write cost | Migration and recovery risk | Verdict |
|---|---|---|---|---|---|
| A | Raise the cap (2048 or 4096) | yes, until the cap, then BLOCKED again | linear: 10 NPCs full = about 49 ms at 2048, 97 ms at 4096 (SIMULATED projection) | restore bound check only; no data move. Postpones, does not remove the finite lifetime | cheap stopgap; needs Owner choice |
| B | One SavedData per actor (shards) | same as now (bounded per actor) | rewrite cost bounded by ONE actor's journal | splits an accepted single-file contract: needs a one-time split, a crash-safe cutover and a rollback plan; touches the C1 snapshot contract | prerequisite for any larger cap with many NPCs |
| C | Hot window + archive-before-evict to the backend ledger | yes, if the archive is consulted on a window miss and is fail-closed | window stays small and constant | new persistence boundary: a Forge-to-backend dependency on the journal path (lookup becomes asynchronous or needs a cached index), ordering rule archive THEN evict (prototyped), phantom REPLAY after a crash (degrades to the accepted HOLD, never a duplicate) | the only option that removes the finite lifetime without a new local storage engine |
| D | Local append-only segment files + index | yes | append only | a new storage engine: torn writes, CRC framing, compaction, memory index, its own fsync protocol; the highest risk | not recommended |
| E | Dense per-actor sequence ids + watermark | only if ids are allocated densely and never reused | tiny | changes the id scheme of every accepted receipt, breaks historical random ids, not backward compatible | rejected |

## Recommendation (input only; the Owner decides, nothing is implemented)
1. Short term: keep 512, or raise to 2048 with at most about 5 full NPCs, after B or accepting a measured 25-50 ms save stall.
2. Long term: B then C. Retention rule for C: a receipt may leave the window only after the backend holds its exact terminal (or an archive row) and acknowledged it durably; an unreachable archive refuses new inventory-changing commits (never evicts, never answers NEW).
3. Keep a small reserve (order of 8 to 16 slots, Owner chooses) for persist-only repair and operator commands; 64 has no evidence.

## What the prototype proves (`JournalArchivePrototypeSelfTest`, test tree only)
A model of C with the real ordering: 200 commits through an 8-slot window keep every historical id exact (REPLAY, and CONFLICT for another fingerprint); an unreachable archive answers UNAVAILABLE (never NEW) and blocks the commit without evicting; 300 random crash cases (window saved only at checkpoints, archive durable on put) never produce NEW for a durable id. It proves the ORDERING rule only. Not proven: archive transport, latency, the asynchronous precheck design, migration, or interaction with the real `SavedData`.

## Owner decision needed before any production step
Whether a Forge-to-backend dependency on the journal path (option C) is an acceptable new safety boundary, and whether option B (per-actor shards) is approved as its own migration sprint.
