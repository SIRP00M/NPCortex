# PAP-G0 contract freeze (2026-10-28, docs + static pins only; no production change)

Status: PENDING Owner sign-off. Source: current repository (read-only audit). Pins: `backend/tests/test_pap_g0_contracts.py`.

## 1. Invariants that PAP must not change
| # | Invariant | Where it lives today |
|---|---|---|
| I1 | Durable START ACK before any world effect. Restart sweep: RESERVED (start never durable) -> FAILED_NO_EFFECT, claims released; EXECUTING (unknown) -> HOLD_IN_DOUBT, claims HELD. Verified in `cplus_wn_store._sweep` | `cplus_wn_store._sweep`, `NativeEffectFlow` |
| I2 | Exact replay: same id + same fingerprint = REPLAY, same id + other fingerprint = CONFLICT, answered even when the journal is FULL | `C1InventoryJournal.lookup` |
| I3 | HOLD_IN_DOUBT never becomes NO_EFFECT/COMPLETED by itself; only operator Reconcile from observed state; HELD claims never auto-release | `cplus_wn_holds`, `NATIVE_BREAK_PICKUP.md` s2 |
| I4 | No eviction/auto-prune of receipts; FULL fails closed ("migration required") and is prechecked BEFORE dispatch (`journalPrecheck`), so a full journal never starts an effect | `C1InventoryJournal`, `NativeEffectRuntime:596`, `C1P1InventoryRuntime:59` |
| I5 | Default-deny autonomous mutation; allow-list is Owner config | `WorldActionPolicy`, `NativeActionPolicy` |
| I6 | Movement keeps C2 durable goal / cancel / terminal witness / recovery; the WN START protocol is NOT imposed on movement | C2*, `cplus_e3_g2_moves`, `cplus_e3_g3_terminals` |
| I7 | Approach before native reserve: no block/item claim is held during an extended walk | PAP-G3 requirement (not yet code) |
| I8 | No SQLite/outbox/journal/HOLD record is reset, re-identified or replayed; no Tier 1 durability relaxation (WAL + synchronous=FULL on the E3/M04 stores, BEGIN IMMEDIATE) | `cplus_e_graph.py`, `cplus_m04_store.py`, `persistence.py` |

## 2. Action inventory (current source)
| Kind | State | Store / claim | Journal |
|---|---|---|---|
| C2 move / follow | implemented | `cplus_e3_g2_moves`, one active move per actor | none |
| `noop` | implemented | `cplus_we_runs`, active-actor unique index | none |
| `break_block` | implemented (allow-list) | `cplus_wn_*`, block claim | C1 receipt only if inventory changed |
| `pickup_item` | implemented (allow-list) | `cplus_wn_*`, item claim (entity UUID + proximity 2.0/1.0) | C1 receipt on transfer |
| `place_block`, `use_block`, `select_tool`, `use_item` | NOT implemented; denied | none | would need C1 inventory commit |
| crafting, containers, duration-use, passive pickup | research-gated, out of scope | | |

## 3. Claim namespaces (arbitration today)
- WN claims: `cplus_wn_claims`, `kind CHECK IN ('block','item')`, partial UNIQUE indexes on ACTIVE/HELD; taken inside one `BEGIN IMMEDIATE` transaction (check and claim are one atomic step, no TOCTOU inside WN).
- Actor fences, one active row per actor: `cplus_we_active_actor`, `cplus_wn_active_actor`, `cplus_e3_g2_active_actor`, `cplus_m04_actor_fence`; M04 also has `cplus_m04_target_fence`. All stores share one SQLite file, so one write lock.
- Gap for PAP-G2: no single arbiter spans these; legacy stores do not consult each other's claims except via the documented cross-fences.
- Design hypothesis to VERIFY in G2 (not decided): `place_block`/`use_block` targets are positions, so they can reuse `kind='block'` rows and the existing unique index. That needs no schema change and gives atomic arbitration with legacy Break for free. A new kind would need a table rebuild (SQLite cannot alter a CHECK).

## 4. Journal facts that bound the plan (measured/read, see PAP_G1_REPORT.md)
- The C1 journal is **per actor profile** (`C1InventoryData.Record.journal`), capacity `MAX_RECEIPTS = 512` per NPC, never pruned. A receipt is written only when an action changes the actor's inventory (native break with drops, pickup, `copyhand`). At the cap the NPC's inventory-changing actions are BLOCKED for good until a migration exists.
- `CPlusDurableLedger.MAX_FRAME = 2048` is a single-frame size limit of the outbox ledger. It is unrelated to the C1 cap and is not benchmarked as one.
- A "reserve 64" rule does not exist anywhere in code or docs (searched `forge-mod/src/main`, `backend/src`, `docs/stage4`). It stays a proposal.

## 5. Use Item / Place / Use allow-list: EMPTY at G0
No `use_item` target is approved. Required evidence per candidate before it may enter the Owner list: (a) decompiled vanilla path (javap) of the `use`/`useOn` call, (b) enumerated world effects (fluid flow, fire spread, growth, TNT/portal ignition, entity effects, redstone), (c) a bound on them and a way to observe them afterwards, (d) a GameTest proving the bound, (e) inventory-failure behaviour (HOLD_IN_DOUBT, never a rollback of a world effect). Flint and steel, bone meal and buckets are NOT PRE-APPROVED; none of the evidence above exists yet (NOT TESTED). `use_block` (button/lever/door class) needs the same list, plus redstone side effects.

## 6. Crash windows (verified against `NATIVE_BREAK_PICKUP.md` s2/s3/s6/s6b and `NativeEffectRuntime`/`cplus_wn_store`)
**Discrepancy found in my first draft:** I defined W1 "after effect, before inventory persist" / W2 "after inventory persist, before receipt" / W3 "after receipt, before ACK". The accepted contract has no separate "inventory persist" boundary: the C1 receipt IS the SavedData commit (`C1InventoryData.commit`: snapshot + journal in one in-memory update + `setDirty`), written to disk only at Minecraft's regular save, not at HTTP ACK. Corrected windows (G5 must test each for Place/Use):
| Window | Accepted behaviour today | Evidence status |
|---|---|---|
| X0 crash after START ACK, before the effect | EXECUTING -> HOLD_IN_DOUBT at restart (never auto NO_EFFECT; block observed unchanged only supports an operator reconcile) | documented; chunk-save/crash window NOT TESTED |
| X1 effect applied, C1 commit throws | HOLD INVENTORY_COMMIT_FAILED carrying the intended receipt; operator persist-only repair after REPLAY is checked first, same post-effect snapshot/incarnation/session; effect never repeated, never rolled back | GameTest seam `journalFault`: PASS |
| X2 effect + commit done in memory, crash before the SavedData save or before the terminal reaches the outbox | "post-effect memory-only window" degrades to HOLD at next start; `ServerLevel.save` writes SavedData BEFORE chunks, so a receipt can persist without its block change | documented; NOT TESTED |
| X3 terminal/HOLD sent but ACK lost | Rec. resends the identical HOLD first, repairs only after its durable ACK; a never-acknowledged HOLD is not forgotten | reviewer finding 5, fixed + tested offline |
Not covered by the accepted contract and therefore NOT claimable for Place/Use: effect-after-crash ordering between world chunk and SavedData, and the single-process assumption of the claim map.

## 6b. Cross-fence gap (corrects my s3)
`NATIVE_BREAK_PICKUP.md` s6b item 11: "both directions" holds only for C1, C2, E3 G2, M04 and the No-op ledger. E1 coordinator, E2 dispatcher and `CPlusE3G3Runtime` have no pre-start guard against a native attempt (G3 is stopped only by the final `ownershipBlock`). PAP-G2's arbiter must close or explicitly exclude those paths.

## 7. Open Owner decisions
1. Sign the invariants I1-I8 and the research-gated list. 2. Use Item/Use Block allow-list: stays empty until evidence per s5 exists. 3. Journal cap after reading PAP_G1_REPORT.md.
