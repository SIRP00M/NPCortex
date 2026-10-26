# M03-FINAL acceptance review — 25 October 2026

**M03 cannot close and is not ready to enter M04.** Integrated infrastructure
and automated protocol verification are delivered. Candidate gameplay 6B–10
remains unverified. No M04 code, Git mutations or GitHub operations.

Authoritative integrated run: `runs/20261025T115358Z/results.json`
(25 October, 18:53–18:55 Asia/Bangkok). Windows10 build19045, Python3.13.1,
Adoptium JDK17.0.20, 24 logical processors.

| Gate | Implementation / automated result | Current candidate Windows gameplay |
|---|---|---|
| G0 fresh audit | IMPLEMENTED; 498-file baseline, read-only SQLite/outbox inventory; no Git metadata | Historical evidence separately retained |
| G1 infrastructure | AUTOMATED PASS: Forge compile/test compile, PS syntax, 8 evidence/cleanup classifier tests | Not a gameplay gate |
| G2 backend | AUTOMATED PASS: 396 discovered, 395 passed, 1 POSIX-only permission test skipped | Not gameplay |
| G3 Java/Forge | AUTOMATED PASS: actual Forge build; transport/outbox, G2 identity49, G3 contract36, M03 recovery29 checks | New build not launched into an acceptance world |
| G3 regression support | AUTOMATED PASS: Test5 decision14; inventory ledger544; SavedData mock14; C1 policy14 + coordinator13; 19 C2/Follow/terrain suites | Does not certify C1/C2/Follow/inventory gameplay |
| G4 Test6B | AUTOMATED PASS: actual owned backend stopped/restarted, same isolated SQLite/config, reserved and accepted attempts retained as HOLD, goal preserved; synthetic envelope retained | PENDING / NOT TESTED; real Forge correlation and admitted-goal HOLD disposition needed |
| G5 Test7 | AUTOMATED PASS: 3 real socket-loss tests after backend success; reserve/finalize/cancel ACK, exact duplicate requests, stale identity/goal rejection, one terminal effect and foreign-actor isolation. Java checks real outbox/recovery against HTTP test doubles | PENDING / NOT TESTED; live Forge no-replay and terminal receipt correlation needed |
| G6 Test8 | IMPLEMENTED: durable snapshots, read-only collector, rejoin comparison and assisted steps | PENDING / NOT TESTED; actual Save/Quit/Rejoin, F1 cursor, permitted new task |
| G7 Test9 | IMPLEMENTED: checklist + supporting actual Java suites | PENDING / NOT TESTED; C1/C2/Follow/inventory. Empty-slot unequip API is HOLD |
| G8 Test10 | IMPLEMENTED: count-checked live observer, repeated-round workflow, OS CPU/memory sampler, summaries | PENDING / NOT TESTED: 1/3/5/10 actual NPCs, workload, baseline and agreed thresholds |
| G9 final review | HOLD / BLOCKED by gameplay gates | No release-ready or 10-NPC stability claim |

The skipped backend test is `test_world_readable_file_denied`: POSIX file
permissions are not a Windows permission test. Skipped is not counted PASS.

## Failed runs retained and resolved

- `runs/20261025T114414Z`: G3 failed on obsolete C1 coordinator test doubles;
  G4/G5 blocked. Updated test-only hooks/order assertions. Follow runner's
  missing trajectory dependency was also reproduced and corrected.
- `runs/20261025T114836Z`: classifier test exposed unavailable terminal state
  reported as FAIL instead of HOLD. Corrected with a regression.
- `runs/20261025T114853Z`: G4 fixture reused a fixed synthetic actor for two
  cases; backend correctly refused a second unresolved reservation. Fixed the
  fixture to use a distinct actor, without changing the production fence.
- Focused process/loss reruns and the full final run above pass. Failed logs
  remain. Subsequent documentation, read-only OS sampler, F1 journal/cursor
  collection and compatibility/cleanup script entries have separate validation
  in `supplemental.log` (9 classifier/collector/cleanup tests, syntax and legacy
  Test6 compatibility entry). Production Java is unchanged since the final run.

## Transport defect analysis

Recovery trusted a subset of reserve provenance (world/actor/session/dimension)
and, on terminal replay, accepted state/effects without checking all durable
ACK/fence fields. A mismatched attempt or incomplete ACK could be described as
delivered. The fix compares task/request/attempt/requester/observation before
recovery, terminal requester/goal before send, and goal/durable ACK/fence release/
completion flag after reply. Negative tests verify rejected identities do not
send metadata and original envelopes remain. No recovery branch invokes
navigation. No production schema or C1/C2 behavior changed. Existing ambiguous
and cross-session work stays HOLD.

## Live-data and test boundaries

Existing Minecraft/backend processes were not started by this harness. They
were not stopped, reconfigured or adopted. No automated Save/Quit or new-source
game launch was performed against their worlds. Assisted steps are provided
for a copied fixture. Automatic process faults used private child processes,
random loopback ports, synthetic identities and isolated SQLite.

Final read-only preservation check: all five original outbox hashes, all 28
original SQLite table counts and original movement-state counts remain unchanged.
See `preservation-check.json`; this is not a full live database byte-identity claim.

Historical user-confirmed Windows Tests 1,2,3,4,4B,5,6A PASS on 25 October are
accepted as history only. Existing latest.log corroborates Test6A controlled
CONNECT_FAILED with backend online, no C2 movement and a retained unsent reserve.
It does not demonstrate physical restart or certify the new bridge.

## Known risks / acceptance limitations

- Restarted admitted goals remain fenced HOLD. Safe stopping does not prove
  subsequent work can recover; do not reset to clear this gate.
- SavedData vs SQLite/outbox crash atomicity and Windows power loss are unproven.
- No empty-slot `unequip` API. Existing-item swap is testable; adding inventory
  capability needs separate scope. Do not use destructive clear.
- Tick-event P95/max cover the Forge event interval, not every tick phase.
  Server rolling average and vanilla profiler are separate. Real repeated-load
  measurements and agreed thresholds are still required.
- Outbox retains envelopes and has no dequeue marker. Retained count/bytes,
  unresolved latest attempts and unknown historical records are separately
  labelled; exact delivery queue depth is unavailable. Fence conflict counts
  require correlated HOLD/409 logs. No sample does not imply zero conflicts.
- Snapshots are bounded observations, not an atomic cross-system checkpoint.
  Remove the opt-in M03 helpers before the Test5 helper.

Blocking acceptance tests remaining: **6B, 7, 8, 9, 10**. Follow `README.md`
in order; fill `acceptance-record.json` with actual Forge and backend evidence.
