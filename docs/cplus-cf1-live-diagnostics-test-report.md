# NPCortex C-F1 D+ Stage 2 — Test Report

Candidate: C-F1 D+ bounded asynchronous **diagnostic-only** journal integration on the user-confirmed C-F1 staged baseline.

## Exact source scope

Added: `cplus/CPlusF1AsyncDiagnostics.java`, `CPlusF1DiagnosticRuntime.java`, pure-Java async worker self-test, Windows runner, architecture note/checklist/report.
Modified: `NPCortex.java` registers operator-only `debug cplus` commands and server-stopping cleanup; `CPlusDurableLedger.java` binds an existing ledger to its first world UUID and exposes read-only recovery/epoch metadata. `CHANGELOG.md` and `PENDING_TESTS.txt` updated.

NOT modified: C1 Physics, Inventory Journal, C2 Navigation/Follow/Scheduler/Journal, Backend runtime and SQLite schema, any world effect executor. No GitHub changes.

## Automated testing executed in container

| Test | Result | Boundaries |
|---|---|---|
| Pure Java C-F1 worker | **14/14 checks PASS** | reopen, epoch increment, separate durable ACKs, wrong-world refusal and corrupt log fail-closed |
| Pure Java C-F1 D+ synthetic | **35/35 checks PASS** | existing journal/recovery contracts |
| Pure Java C-F0 contract | **71/71 checks PASS** | existing action/ownership contract |
| Backend C-F1 D+ | **13/13 PASS** | Python SQLite projection/outbox synthetic |
| Backend C-F0 contracts | **15/15 PASS** | Python schema validation |
| Backend C2 focused | **16/16 PASS** | Python regressions |
| Follow/Navigation pure Java | **22/22 suites PASS** | Java 17 bytecode target, not Forge/MSPT |
| ZIP Integrity | Pending until packaging validation below | No missing baseline files expected |

Java tests were compiled with `javac --release 17` using host OpenJDK 21. This is NOT a Temurin 17 Forge build.

## Forge build attempt

`./gradlew --offline compileJava --no-daemon` could not start build: Gradle Wrapper attempted downloading `https://services.gradle.org/distributions/gradle-8.8-bin.zip` and failed with `java.net.UnknownHostException: services.gradle.org`. No Forge compile success claimed. Validate on Windows Temurin 17.

## Safety evidence and scope

- Every synthetic probe enqueues `FENCE` (once per actor session), `INTENT`, `CANCELLED` with *synthetic-no-world-effect* evidence, and completes its future only after the ledger durability barrier.
- I/O writes and force are performed on the bounded worker, never in the command handler or server END tick. A queue-full result is rejected, not sync-written.
- Existing log checksum/world mismatch fails closed. The tool neither truncates bad data nor retries an action automatically.
- Nothing creates real Game/World/Inventory Action records; C1/C2 runtime is unchanged.

## Pending / not verified

- Windows Forge command registration & Play-in-game; Save & Quit/Rejoin epoch tests.
- Full backend suite exit certification, real process kill/power failure and disk-full injection.
- Bukkit/Forge event hooks for passive pickup, world mutation, permissions, and player inventory changes.
- Actual Minecraft-world + backend SQLite recovery and multi-NPC measured 1/3/5/10 MSPT.
- Safe retention/compaction, manual quarantine/repair workflow, and live backend outbox transport.

This stage is not proof of safe automatic world effect recovery. Do not enable a mutation executor until future audits, explicit approval and fail-closed end-to-end tests.
