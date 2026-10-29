# M04 automated acceptance: scoped source audit and architecture options

Baseline: `NPCortex_M04_OptionD_Integrated_FullRepository_CANDIDATE(2).zip`
(26 Oct 2026). Source read in isolation; there was no Git/GitHub operation and
no access to a newer local `F:\AI\NPCortex` working tree.

## Audited implementation surfaces

- Existing `scripts/test-m04-option-d.ps1`: M04 static checks, typed Python
  contract/store tests, read-only fixture review, pure-Java contract, optional
  Forge build. No aggregate machine-readable result; no report ZIP.
- Existing `scripts/test-transport-e.ps1`: full backend regression plus Java
  build/contract tasks. It requires separate invocation.
- Existing `scripts/review-m04-evidence.ps1` and `m04_acceptance_review.py`:
  read-only world/DB matching; no gameplay PASS is possible from metadata.
- `integrations/datapacks/NPCortex_M04_Acceptance`: operator-driven menu,
  preflight, inventory, after_rejoin; no autonomous block/item operations.
- `backend/tests/test_cplus_f1_recovery_hardening.py`: a nested Python
  subprocess imports `npcortex_backend`; without inherited `PYTHONPATH`,
  CI can fail before executing recovery assertions.
- `.github/workflows/backend-tests.yml`: previously did not explicitly
  propagate the backend source path into nested processes.
- `docs/m04/OPTION_D_IMPLEMENTATION.md`: Gate 0 command provenance, Gate 1
  authority, Gate 3 cancellation quiescence, Gate 4 real pickup, Gate 5
  observed effects, Gate 6 end-to-end gameplay remain blocked/HOLD.

## Five reviewed directions

| Option | Benefit | Tradeoff/risk | Decision |
|---|---|---|---|
| A. Keep separate scripts | Zero changes | Frequent manual commands; no one-file report | Not selected |
| B. PowerShell-only master script | Natural Windows shell | Duplicates runner logic and ties tests to PS | Not selected |
| C. Add live Forge GameTest now | Would reduce future gameplay clicks | Unsafe false assurance without native mutation/cancel/permission implementation | Deferred |
| **D. Offline orchestrator + report ZIP + optional read-only correlation** | One command; repeated tests automated; fail-closed HOLD | Cannot claim gameplay; Forge deps still needed | **Selected, narrow scope** |
| E. Fall back to published GitHub main | Simpler baseline | Loses M04/Transport E work and does not resolve new gates | Not selected |

## Implemented in this scoped iteration

- Add `scripts/m04_auto_accept.py`, `scripts/accept-m04.ps1`, a focused
  self-test, docs and current candidate manifest.
- Execute static, focused backend, read-only reviewer, full backend, pure-Java
  and Forge contract/build gates with machine-readable status and logs.
- Preserve all world/DB/outbox/journal contents, configure Python environment
  for child tests, and create a locally scoped report ZIP (no raw world/DB).
- Preserve `mutation_permitted=false`; do not implement or enable mining,
  pickup, autonomous task dispatch, world mutation or live GameTest.
- GitHub Actions YAML is updated **only as a source file in the candidate ZIP**;
  no push, branch, commit or action was performed.

## Proof boundary

Passing synthetic tests is not proof of Minecraft native mutation or the
Windows Forge build. Local source tests and a backend regression suite can
be certified only by their actual logs. An environment missing Gradle is HOLD,
not PASS. Read-only correlation requires explicitly selected real world and DB.
A later real GameTest harness needs new audit/approval after the prerequisite
authority and cancellable native APIs are verified.
