> **Update (C2-P0 Precision candidate, 2026-10-16):** For current evidence (1,224 isolated Java assertions, 192 Backend tests, still no Forge compile), see [C2-P0 Precision Movement](c2-p0-precision-movement-c.md). Below is the historical first C2-P0 implementation report.

# C2-P0 Architecture C — Local Test Report (2026-10-16)

**Candidate only.** This report describes commands actually run within the Linux execution environment; it does not certify Windows Forge 1.20.1 gameplay or live Ollama.

## Test outcomes

| Test | Result | Limitations |
|---|---|---|
| `javac --release 17` + C2FoundationSelfTest | PASS: 32 assertions | Java 21 host, source/target 17; only pure geometry, event bus, scheduler |
| Nine existing C1 dependency-free selftests | PASS: 1,149 assertions | Does not compile Forge mappings; no world/entity simulation |
| C0 backend focused (`test_c0*.py`) | PASS: 69 tests | Mocked provider/world |
| C2 service contract (`test_c2*.py`) | PASS: 4 tests | Real SQLite, mocked Qwen and sensor |
| Entire Backend unittest discovery (`test_*.py`) | PASS: 189 tests | Python 3.13, expected negative-test traces printed to log |
| Forge `./gradlew --offline compileJava` | **BLOCKED** before compilation | Missing Gradle 8.8 wrapper distribution; SSL/network download unavailable |
| Windows Forge `build` | NOT RUN | Must run JDK 17 on Windows |
| Windows Forge gameplay + live Ollama | NOT RUN | User's acceptance required |
| 1/3/5/10 NPC TPS/MSPT, dedicated/multiplayer | NOT RUN | Future recorded measurements required |

## Required quality gate

1. Run `./scripts/test-c2-p0.ps1` with Temurin 17 and confirm isolated/backend tests.
2. Run `./scripts/dev-forge.ps1 -Task build` successfully.
3. Start backend and Ollama, then `./scripts/dev-forge.ps1 -Task runClient`.
4. Execute the scenarios in [C2-P0 Windows Test Checklist](c2-p0-event-driven-embodied-agent.md), capturing logs/screenshots and action/goal UUIDs.
5. Do not call C2-P0 accepted until actual AI-driven movement, negative/recovery cases and required C1 regressions pass. No GitHub Commit/Push by assistant.

## Source-only caveats

- C2 Action Receipt is still a candidate and has **not been compiled against actual Forge/Minecraft dependencies here**.
- `C2ActionJournal` records per actor in SavedData but cannot guarantee atomicity with backend SQLite; ambiguous requests fail closed and need explicit manual reconciliation.
- No natural-language spatial grounding; operator coordinates only. No multi-step 3D navigation or action queue/FIFO.
- `move_to` is the only executor. Future skills must register real Forge executors plus matching observations and per-skill verification.
