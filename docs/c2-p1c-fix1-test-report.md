# NPCortex C2-P1-C Fix #1 — Candidate Test Report

Baseline: `NPCortex_C2-P1-C_C_TransactionalWaypointEngine_FullRepository_CANDIDATE.zip`

## Tests actually run in this container

- Python full backend suite: **201 tests, OK** including cancel/result race coverage, including **8 new cancellation/reconciliation tests** (SQLite persisted cancellation, idempotency, goal fencing, wrong identifiers, HTTP endpoint and replay after new service).
- Dependency-free Java compilation with `javac --release 17`: **2,286 assertions PASS**, comprising 2,275 previous C1/C2 tests and **11 new cancellation policy assertions**.
- Compilation of the new Forge cancellation reconciler against minimal local stubs: **PASS**. This checks Java syntax/interface shape, **not** the actual Forge dependency graph.
- Actual Forge Gradle build: **NOT VERIFIED**. Wrapper attempted to fetch missing Gradle distribution but network resolution prevented startup before any Forge compile.
- Live Windows Minecraft gameplay: **NOT TESTED** for this candidate.
- Concurrent MSPT, cancellation during in-flight goal creation, hard crash injection, server stop while HTTP callback is pending: **NOT VERIFIED**.

### Regression notes

- The backend's negative-case test logging includes simulated SQLite errors, invalid model JSON and mocked provider failures. The suite reports `OK`.
- No change to existing C1 physics implementation, inventory, combat, C2 navigation planner or P1-D.
- All older Unloaded Chunk/Partial Terrain/Per-edge safety caveats remain as carried in `PENDING_TESTS.txt`.

## Windows evidence needed

Run `scripts/test-c2-p1c-fix1.ps1`, Forge Build and gameplay tests documented in `docs/c2-p1c-fix1-post-cancel-recovery.md`; provide build output, status transitions, cancellation/restart scenarios, and optional backend logs.
