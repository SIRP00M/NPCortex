**Optional guided all-tests Datapack (6A–10):** `ALL-TESTS-DATAPACK.md` — requires updated M03 Forge bridge; is an acceptance assistant, not a source of PASS.

# M03-FINAL — Transport E reliability and acceptance

Technical ID: **C-F3 Option E · E3-D G3 + Transport E**.
One integrated source candidate. **Not release-ready; M04 is not implemented.**
Detailed generated run evidence is kept outside this public source package. Historical gameplay tests are scope-limited; do not infer a pass for this build.

## Install and run automated gates

Use a separate extraction and a **copy** of your test world/database. The ZIP
contains the complete source repository, character packs, wrapper, scripts,
documentation and test evidence. It excludes live saves, databases, credentials,
build products and caches. Do not replace your original world/data/config with
files from any candidate. JDK17 + Python3.10+ required (tested with Python3.13).

From the candidate root:

```powershell
.\scripts\test-m03-final.ps1
```

This performs G1 compile/PowerShell/classifier tests, G2 full backend tests,
G3 Forge build + actual Java/Gson transport tests + C1 lifecycle/inventory and
19 C2/Follow/terrain suites, then G4 owned real-process restart and G5 actual
socket response-loss tests. Logs and atomic `results.json` are retained in
`docs/m03-final/runs/<UTC-run>/`. First failed gate blocks later automated
gates. Later gameplay acceptance remains blocked by the preceding gameplay
gate even when its independent supporting protocol test passes.

Synthetic backend fixtures do NOT create real C0/Forge actions. Process tests
persist their isolated SQLite DB and exact dropped requests as evidence.
They never kill/adopt an existing Python PID, reset a DB, or read live tokens.
G2's one skipped test must be read in its log; skipped is not PASS.

## Start the opt-in game bridge

Close your **copied** world before installing the datapack, build/relaunch the
candidate Minecraft client, then join that world as an online OP:

```powershell
.\scripts\install-m03-harness.ps1 -WorldName 'YOUR_COPIED_WORLD'
.\scripts\dev-forge.ps1 -Task runClient
```

```mcfunction
/reload
/function npcortex_m03:enable
/function npcortex_m03:status
/function npcortex_m03:snapshot
```

Direct equivalents: `/npcortex debug cplus m03 enable`, `status`, and
`snapshot yui`. Status/disable only accepts the original online OP. Every
server restart requires enable again. No tick/load datapack functions exist.
All functions have real handlers. Functions cannot stop/start OS processes.

`snapshot <npc>` captures actual ServerPlayer position/UUID/session, C2 goal /
action / state, C1/Follow activity, inventory NBT/equipment, backend identity
and state, request latency, actor-filtered outbox envelopes and hashes. Files:
`<world>/data/npcortex/m03-evidence/*.json`. A snapshot is evidence, never PASS.
Live state is sampled on the server thread; backend/disk reads occur later on
a bounded IO worker. It is not an atomic cross-system transaction. Wait for
the “snapshot saved” message before lifecycle steps. Offline backend still
produces local evidence with an explicit backend error. Missing credentials,
corrupt outbox, >512 directory entries or IO failure HOLD the capture.

Single explicit movement, using Yui's position and loaded safe corridor:

```mcfunction
/npcortex debug cplus m03 move yui
```

It delegates once to existing G2; radius6/height3, ownership, grounded, uncertain
journal and backend fences remain authoritative. No terrain modifications,
inventory modifications, teleporting, NPC spawning or automatic DAG. Do not
mix Test5 and M03 commands on an in-flight attempt. No safe corridor => HOLD.

## Test 6A versus 6B

The fresh audit found the historical Test6 source removed despite leftover
docs/scripts/classes. Its **controlled before-network outage** is now available
through the integrated bridge:

```mcfunction
/function npcortex_m03:test6a
```

This writes the real reserve envelope then raises a scoped ConnectException.
Capture before/after snapshots and verify no C2 goal/position change, unchanged
backend task, new unique never-handed-off envelope and `BEFORE_NETWORK_SEND`
fault evidence. This is Test6A only; new-source gameplay remains PENDING.
The old `/function npcortex_test6:*` pack is obsolete in this source tree.
Use the M03 installer/cleanup; old Test6 source removal should not be invoked.

### 6B real backend restart (assisted with owned process control)

Prepare a consistent **copy** of SQLite while the source backend is stopped,
or use SQLite's backup API. Keep the original DB. Use a copied Forge instance
configured with the test backend URL `http://127.0.0.1:18765` and the same local
C+ credential source. The controller refuses an occupied port and the original
repository DB; it does not adopt the existing backend.

```powershell
.\scripts\start-m03-owned-backend.ps1 -Database 'F:\acceptance\m03-copy.sqlite3' -EvidenceDirectory 'F:\acceptance\owned-run-1'
```

The controller starts its own child, checks `/health`, and accepts `status`,
`stop`, `start`, `quit`. `stop` terminates **only its retained child handle**;
`start` reuses identical DB path, environment, port and credentials. An existing
non-owned service must instead be stopped/restarted manually by its operator.
No process-name kills. Exit/exception cleanup stops only the owned child.

1. Online: snapshot Yui, run `taskmovestatus yui`, ensure previous state is
   NONE/COMPLETED/CANCELLED, no Follow/C1/C2 operation. Keep one unrelated NPC
   snapshot to compare isolation.
2. Controller `stop`; confirm OFFLINE. Run **one** `m03 move yui`. Wait for
   reserve CONNECT_FAILED/HOLD. Snapshot again: new unique task/attempt in
   outbox, `handoff_possible=false`, unchanged C2 goal/action and NPC position.
3. Controller `start`; snapshot, run `tasktransportrecover yui`, snapshot.
   An unsent request must not create a backend row or navigation. Compare
   original envelope hash and identities. Recovery never resubmits reserve.
4. Separately exercise a confirmed reserve/admitted-goal shutdown window on
   another explicit attempt in the copied fixture. Record task/attempt/goal
   before `stop`; `start` must preserve IDs and set unresolved backend work
   HOLD, without a second C2 goal. Existing protocol may keep the actor fenced.
   Do not force release or restart movement. Record HOLD and its reason.
5. Collect records using the command below. Real process lifecycle alone is
   not a gameplay pass. Unable to establish state => HOLD. An accepted-goal
   restart HOLD that prevents required subsequent work remains a blocker.

## Test 7 — response lost after commit

First verify online status is clear and take a snapshot. Then:

```mcfunction
/function npcortex_m03:reserve_loss
/npcortex debug cplus m03 snapshot yui
/npcortex debug cplus tasktransportrecover yui
/npcortex debug cplus m03 snapshot yui
```

The one-shot fault is outside the shared HTTP retry: after a successful real
backend response and before ACK reaches G2. It preserves `handoff_possible=false`.
Require backend RESERVED and matching retained envelope before recovery;
recovery may abandon only this proven unstarted attempt. Require CANCELLED,
same task/attempt, no new C2 goal, no motion and original envelope hash unchanged.
The separate Python gate also drops TCP responses after upstream commit, repeats
exact requests, and verifies SQLite idempotency. These two methods are labelled
separately in evidence; neither is the “request never reached backend” case.

Terminal response loss: perform one ordinary explicit move and wait for actual
C2 completion. Find its task UUID in the snapshot, then:

```mcfunction
/npcortex debug cplus m03 loss-terminal yui finalize TASK_UUID
/npcortex debug cplus taskmovefinalize yui
/npcortex debug cplus m03 snapshot yui
/npcortex debug cplus tasktransportrecover yui
/npcortex debug cplus m03 snapshot yui
```

For cancellation, use `taskmovecancel yui`, wait for matching C2 CANCELLED,
arm `loss-terminal yui cancel-ack TASK_UUID`, then `taskmovereconcile yui`.
Fault expires in 60 seconds and matches route/world/actor/session/requester/task.
Require an `AFTER_SUCCESSFUL_RESPONSE` evidence file; an unconsumed/expired fault
is NOT a successful loss test. No fake ACK, resend of reserve, or repeat movement.
Require exactly one terminal backend row, same C2 goal/action, no contradictory
terminal, unchanged unrelated NPC, original envelopes retained. Duplicate and
stale attempt/goal tests run on synthetic actors in G5, not on live world actions.

## Test 8 — actual Save / Quit / Rejoin

1. After terminal and separately with outstanding HOLD, take `m03 snapshot yui`;
   capture `/npcortex debug cplus syncstatus yui` and `audit yui` (F1 checkpoint /
   local and backend sequences; E3 has no equivalent cursor). Wait for IO replies.
2. Save & Quit or orderly server stop. Collect evidence while world is closed.
3. Restart/rejoin **the same copied world**. Enable M03 again; snapshot Yui and
   repeat syncstatus/audit. Stable world/actor/profile UUID; new live session UUID;
   old backend attempt session stays attached to its original record.
4. Invoke `tasktransportrecover yui` once; snapshot. Observe no navigation,
   duplicate completion/cancel, item changes, or changed old goal. Old-session
   metadata may HOLD. Never reset a journal/fence to make rejoin pass.
5. Compare selected before/after snapshot files:

```powershell
python scripts/m03_final.py compare-rejoin --before 'F:\acceptance\before.json' --after 'F:\acceptance\after.json' --output 'F:\acceptance\rejoin-result.json'
```

This checks metadata only, not gameplay. When the protocol permits, issue one
**new explicit** `m03 move yui`, verify real arrival and finalize. If old HOLD
blocks admission, preserve it and report HOLD. Unit tests cannot close this gate.

## Test 9 — regression checklist (manual operations, observer does not mutate)

Record each row with snapshot filenames, exact goal/task/transaction IDs,
operator, candidate hash, outcome and reason in `acceptance-record.json`.

| Area | Execute / observe |
|---|---|
| C1 | Existing `c1 spawn <name>` in copied fixture, lifecycle/status, ServerPlayer avatar; UUID/profile persistence through Test8; two NPCs isolated |
| C2 | Explicit movement then finalize; existing mixed slab/stair/jump fixture, replan/blocked route, exact-goal cancellation; no reset |
| Follow | `/npcortex c2 follow yui PLAYER`, walk/turn and observe tracking; snapshot; attempt `m03 move yui` must refuse without stealing ownership; `/npcortex c2 unfollow yui`; confirm stop |
| Inventory read | `/npcortex c1 inventory yui`, `equipment yui`, `ledger yui`; compare full NBT snapshots |
| Equip / swap out | Use an item **already present**: `/npcortex c1 equip yui SLOT offhand TX_UUID`; repeat exact TX_UUID, contents must not apply twice. Equip another existing item to offhand with a new TX_UUID; the original equipped item must return to that source slot. Recheck after Save/Rejoin |
| Isolation | Keep unrelated actor before/after snapshots; no goal, inventory, fence or identity changes caused by another actor's recovery |

No `give`, `clear`, item pickup, block edits or fabricated inventory success in
this harness. Do not mark Follow/Inventory gameplay PASS from Java policy tests.
**Unequip to an empty equipment slot is not exposed by the current command API**:
`equip` rejects an empty source and there is no `unequip` handler. Record this
specific acceptance item HOLD; do not invent a command or use destructive clear
as an unequip substitute. Adding that inventory capability is outside Transport E.

## Test 10 — actual NPC count and performance

Prepare existing actors in the copied test fixture. No automated spawning.
Use counts 1,3,5,10, **at least three rounds each**, same terrain, render/view
distance, JVM heap, hardware and duration. Warm up separately. Extra active NPCs
invalidate the requested count. Example (substitute actual names):

```mcfunction
/npcortex debug cplus m03 measure 1 120 yui
/npcortex debug cplus m03 measure 3 120 yui npc2 npc3
```

For 5/10 provide exactly that many distinct live names. During each measurement
issue explicit `m03 move NAME` per actor, then the existing finalize/cancel
commands as appropriate. No automated movement loops. Use the same workload
for every round and record timing; no completed workload => HOLD.

Each run records actual count validity, wall duration, rolling server average
MSPT, START-to-END tick-event mean/P95/max, JVM heap, OS/Java/CPU count and
per-actor C2 goal/state transitions. Event duration is labelled separately from
full server profiler tick time; it excludes work outside Forge's event pair.
`/debug start`, run the workload, `/debug stop` provides the vanilla server
profile for corroboration. CPU utilization/RSS need a separate trusted process
sampler; the optional read-only Windows sampler records CPU time deltas,
working set/private memory and hardware. Supply the verified Minecraft PID
(not the Gradle daemon) and correlate the interval with the measurement UUID:

```powershell
.\scripts\test-m03-process-metrics.ps1 -JavaProcessId 12345 -DurationSeconds 120 -Output 'F:\acceptance\process-round1.json'
```

Missing OS samples remain unavailable, not zero. CPU percent is normalized to
all logical processors; it is not single-thread utilization.

```powershell
.\scripts\test-m03-performance.ps1 -EvidenceDirectory 'F:\acceptance\collected' -Output 'F:\acceptance\performance.json'
```

Summary reports observed nav latency (left-censored tasks excluded), backend
latency including retries, completion rate, failed/HOLD goals, ID collisions,
retained outbox/journal byte growth, unresolved latest attempts and unknown
historical attempts. **Transport E retains envelopes; it has no dequeue counter**,
so envelope count must not be advertised as exact pending queue depth. Actor
fence conflict counts require matching G2 HTTP_409/HOLD logs. Missing metrics
remain unavailable. Agree performance thresholds from the 1-NPC baseline or
requirements before PASS. Compile/synthetic actors do not prove 10-NPC stability.

## Collect, acceptance, cleanup

```powershell
python scripts/m03_final.py collect --world 'C:\path\to\NPCortex\forge-mod\run\saves\COPIED_WORLD' --database 'F:\acceptance\m03-copy.sqlite3' --output 'F:\acceptance\capture-1'
```

Collector copies M03/transport evidence and NPC SavedData, hashes it, and exports
E3 tables via a read-only SQLite transaction. It never updates source records.
Capture online and after clean save; live collection is not an atomic backup.
Keep game screenshots and relevant redacted log excerpts alongside it; never
publish token files. Use `acceptance-record.json` per scenario and leave status
PENDING until an operator actually verifies both Forge and backend evidence.

Close copied Minecraft before uninstall; ensure no live test is in flight.

```mcfunction
/function npcortex_m03:disable
```

```powershell
.\scripts\remove-m03-harness.ps1 -WorldName 'COPIED_WORLD' -RemoveFromSource
.\scripts\dev-forge.ps1 -Task build
```

Cleanup validates exact hooks, backs up source, removes only temporary bridge,
fault class/test/task and the named datapack. Production recovery hardening,
worlds, SQLite, journals, outbox and evidence remain. Remove M03 **before Test5**
(M03 reuses its target finder). No process-name kill, reset, fence release or Git
operation. After uninstall the M03 test runner is intentionally unavailable;
use production `test-transport-e.ps1` for the resulting build.
