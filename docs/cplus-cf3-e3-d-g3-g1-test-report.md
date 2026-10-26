# E3-D G3-1 — Typed Terminal Candidate Contracts / Dry-run Test Report
**2026-10-24 — IMPLEMENTED CANDIDATE, Windows acceptance PENDING.** Baseline: E3-D G2 Fix #1.

## Code changes
- `backend/src/npcortex_backend/cplus_e3_g3_terminal.py`: fail-closed shape/identity/goal/action/target/tolerance/C1/C2/C0 candidate validation. Returns `DRY_RUN_VALIDATED`, `completion_verified=false`, `durable_ack=false`, `release_actor_fence=false`. No HTTP route, SQLite update, mutation or movement.
- `docs/contracts/e3_g3_terminal_candidate_v1.json`: shared canonical typed fixture (one candidate and one accepted G2 attempt).
- `forge-mod/.../CPlusE3G3TerminalContract.java`: corresponding pure-Gson contract checker; neither issues nor trusts world evidence. Java/Gson self-test fixture and Gradle task `verifyG3TerminalContract`.
- `forge-mod/.../CPlusE3G3Diagnostic.java`: operator-only `/npcortex debug cplus taskmovejournal <npc>` gives last read-only C2 SavedData journal row with explicit warning that it is NOT C+ terminal proof or attempt correlation.
- `scripts/test-cplus-e3-g3-g1.ps1`: focused Python/Java test runner, `-BackendOnly` and optional `-IncludeG2Regression`.
- E3-E/E4/E5 source-backed options in `docs/cplus-first-world-action-integrated-audit-e3e-e4-e5.md`.

## Local evidence (in isolated clean extract)
- Python G3-1 contract: **8/8 PASS** (the cases include many subcase negative checks).
- Existing G2 Fix1 + G2/G1 + E2/E1 + F2/F1/F0 focused regression: **97/97 PASS** (13 listed modules incl. G3, total **105/105 PASS**).
- `javac --release 17` type/syntax compile of isolated G3 contract and self-test against lightweight Gson API stubs: **PASS**. **NOT** official Gson/Forge Compile. Target environment locally has JDK 21, Gradle wrapper 8.8 distribution unavailable; actual Java self-test and Forge Build **PENDING Windows**.
- ZIP/entry integrity to be verified on final packaging; full backend test suite **PENDING**. No real Minecraft G3-1 gameplay test yet.

## Safety and known limitations
- A valid candidate is **not** an authoritative server-side C1+C2+C0 witness; all candidate fields could otherwise be assembled from untrusted JSON. **No route accepts it to finalize an attempt.**
- No changes to `GuardedMotionStore`, its schema, active-actor index, `C2ActionJournal`, C1/C2 runtime or permission policy. Prior G2 `HOLD` rows remain fenced. No G3-2 completion, G3-3 goal-scoped cancel, E3-E DAG, E4 permission adapter or E5 break skill.
- Full Windows tests and user acceptance needed before enabling G3-2.
