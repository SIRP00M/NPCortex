# C+ C-F1 D+ — Test Report

## Confirmed within this build environment

- C-F1 pure Java classes compiled using `javac --release 17` on OpenJDK 21; synthetic journal tests include restart fencing, persistent reopen, intent before execution, forbidden retry, side-effect uncertainty, partial/external item status, corruption/truncation, file lock and identity conflicts.
- C-F1 backend `test_cplus_f1_dplus.py`: event projection / SQLite transactional outbox, idempotent duplicate ACK, identity mismatch, epoch/intent guards, staged effect policy.
- C-F0 Backend contract tests and C2 backend focused regression re-executed.
- Baseline airborne Follow/Navigation 22 pure Java suites re-executed.

## Not certified in this environment

- Real Forge JDK 17 compilation, gameplay, genuine crash/power-loss durability and 1/3/5/10 NPC MSPT are not proven by Java 21 --release 17 or Python tests.
- The synthetic Java ledger calls `FileChannel.force(true)` synchronously in a temporary test process. It is **not integrated into server ticks**. A future bounded async IO bridge needs its own performance gate.
- Full Backend Suite was run, `Ran 229 tests ... OK` appears in captured output; wrapper process did not give a normal exit status before the tool deadline. Treat global runner exit as **unverified**, rather than claiming full CI PASS.
- No transport/inbox worker or real world-effect provenance exists yet.

## No Changes / Regression Boundary

No original C1/C2/Forge game-runtime Java sources were modified; added new C+ package members, test harnesses, Backend staged helpers, documentation and scripts. Existing C-F0 contracts and legacy commands remain supported unchanged.
