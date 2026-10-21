# NPCortex Sprint 4 Fix #1 — Transactional Replanning Recovery Hardening

## Baseline and scope

Source: `NPCortex_TerrainAware3D_Sprint4_C_VerifiedTransactional3DRecovery_FullRepository_CANDIDATE.zip`. **User-approved scope:** fix C1 passive physics versus C2 replanning ownership; typed origin/token; bounded safe wait; action-owning interruption reconciliation; regressions. No new physics, B3, skills or GitHub operations.

## Concrete Windows regression

- Screenshot: `yui C2=INTERRUPTED` reason `P1-D C1 motion resumed during replanning; fail closed` after adding a dynamic block wall during 3D navigation.
- Later screenshot: C1 `motion=SETTLED`, `grounded=true`, `horizontalCollision=false`, `velocity≈0.129`, C2 remained `INTERRUPTED`.
- **Not established:** type/token of ACTIVE session at time of interruption. This fix permits autonomous settling, without assuming that was the cause in the captured event. If origin is foreign, it still fences and reconciles rather than starting another motion.

## Implementation invariants

1. C1 owns every physics tick; S4 only reads a session-origin token and never changes travel / gravity / impulse rules.
2. `AUTO_SETTLING` or `AUTO_COMBAT` is passively awaited for at most 100 ticks; no 3D plan while any C1 motion remains active, no unsupported/unloaded terrain accepted as ready.
3. An operator command is always foreign, including explicit `c1 settle` and `c1 jump`; a finished foreign session is detectable from its persisted terminal token and origin.
4. Interrupting an action-owning C2 run fences the *same* C0 goal/action using durable `CANCEL_PENDING`; pending C0 action is never assumed terminal or marked successful without backend acknowledgment.
5. Only the known prior P1-D interruption signatures can migrate after reload; unrelated `INTERRUPTED` journals still block autonomous replay and require diagnosis.
6. No new chunk loads, Qwen decisions or physics/movement sessions during recovery.

## Executed tests

- Java `javac --release 17`: 24 pure Java self-test suites, **4,706 assertions PASS** including 267 for Fix #1.
- Backend: focused `test_c2_*.py` **16 tests PASS**, backend unchanged. Current full unittest suite **201 tests PASS (37.184s)**; backend source is unchanged in this Fix.
- Forge build blocked by unavailable Gradle distribution download in this container. Not Forge compile evidence.
- Real Windows Minecraft acceptance, restart / backend offline / 1,3,5,10 NPC MSPT **PENDING**.

## Validation / expected signals

On a known old action-owning `INTERRUPTED` P1-D journal, startup with same C0 database should progress to `CANCEL_PENDING`, and eventually `CANCELLED`, `FAILED`, or truthful C0 terminal state; no manual journal reset. The stale action is **cancelled and reconciled**, not resumed. Start a **new** goal after terminal acknowledgment.

During a new REPLANNING, verified `AUTO_SETTLING`/`AUTO_COMBAT` should log a passive owner and wait; once grounded/loaded/support-confirmed for three ticks, the system may reroute under the *same* C0 action. Foreign operator sessions are not taken over: expected interruption + C0 reconciliation.
