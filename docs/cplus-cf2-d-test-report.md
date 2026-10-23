# C-F2 D P1–P5 Test Report (implementation candidate)

## Test results available from build environment

- Python 3.13 contract fixture/fail-closed tests: 16/16 PASS.
- Java JDK 21 compiled with `javac --release 17`: 16/16 PASS (isolated read-only policy only).
- Full Backend Suite: **NOT VERIFIED** (attempt exceeded execution timeout; no terminal result). Do not claim PASS.
- Forge compilation: **BLOCKED** (Gradle 8.8 wrapper distribution unavailable from this environment; no Minecraft dependencies installed). This is NOT a Java Forge compilation pass.
- Windows Gameplay, C-F1 regressions and 1/3/5/10 real MSPT: **PENDING** for this Candidate.

## Scope evidence

- Added only C-F2 read-only server sampler + operator `world` debug hook, isolated pure policy, Python v2 contract validator, new tests and Windows runner.
- No registered block/item-use executors; C2WorldSensor remains legacy v1 with `blocks={}`; C2SkillRegistry still advertises only actual registered `move_to`.
- C1 physics, ownership, inventory, C2 follow/navigation, C-F1 IO/cursor/Journal, Backend server auth/SQLite, Qwen/Ollama untouched.

## Constraints

- No authenticated network v2 ingestion yet. The new local `verify-cplus-f2-observation.py` validates Java-emitted diagnostic JSON from Minecraft logs, but **requires Windows Forge Gameplay output** and is NOT verified yet. Do not infer authenticated network v2 ingestion.
- Permission UNKNOWN is a safety hold, not a blanket denial from an installed claim mod.
- Modded blocks may exceed 16 KB payload -> HOLD, not partial data; local entity query cost needs real profiling.
