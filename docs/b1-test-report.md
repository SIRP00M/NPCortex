# NPCortex B1 — Test Report and Handoff (2026-10-14)

**Baseline:** `NPCortex_v0.1.1-alpha_A2_StabilityHardening_FullRepository.zip`.
**Candidate:** B1 World & Actor Identity Foundation. Not an accepted release.
**Authorization:** B1 implementation only. No code for B2 or C.

## Actual commands and results

| Verification | Actual result | Caveats |
|---|---|---|
| `python -m unittest discover -s backend/tests -v` | **41/41 PASS** | Python 3.13 in this environment; 3 consecutive runs (1 verbose, 2 quiet) |
| `python -m compileall -q backend/src backend/tests` | **PASS** | Python syntax only |
| HTTP `ThreadingHTTPServer` against Mock provider | **PASS** | B1 tests exercise new REST endpoints over localhost |
| SQLite file create / actor persistence / re-open | **PASS** | Unit & regression tests with temporary SQLite DB |
| Entrypoint `python -m npcortex_backend` actual subprocess | **PASS** | Live local HTTP, create world+actor, stop/restart, retrieve actor; no Ollama call |
| Same-actor concurrency and bounded transcript | **PASS** | Mock AI provider, single Python process |
| Rollback on transcript DB insertion failure | **PASS** | SQLite abort trigger: neither user nor assistant message persisted |
| A2 legacy API regression | **PASS** | Existing 22 tests included in 41; legacy `/v1/chat` unchanged |
| Java source modifications | **NONE** | Compared 3 `.java` files byte-for-byte against original A2 ZIP |
| ForgeGradle build | **BLOCKED (NOT PASS)** | Tried `bash ./gradlew --offline --no-daemon build`; wrapper needs Gradle 8.8 download, DNS `UnknownHostException: services.gradle.org`; available Java is 21, not required JDK 17 |
| Real Ollama/Qwen 3 8B | **NOT RUN** | Model not verified in this environment; Mock provider used |
| Minecraft B1 actor identity integration | **NOT RUN** | No new Forge actor command or NPC entity in B1; user Windows verification pending |

## Scope and architectural notes

- `WorldStore` implements local SQLite v1 schema for world UUID, actor UUID,
  dimension ID, immutable pack identity, conversation ID, and bounded transcript.
- UUID for the same conversation is derived deterministically from world, actor,
  player ID; repeated startup uses the same SQLite data by configured DB path.
- Actor chat is still **player-to-actor only**. B2 will add multi-actor turns.
- `ChatService` shares A2 concurrency limits and model scheduler semaphore, but
  actor-chat reads history from durable SQLite under an in-process turn lock.
- Legacy `/v1/chat` remains in RAM, uses the same JSON response and is still used
  by the unchanged Minecraft Forge bridge. A2 RAM history cannot be migrated
  after restart, because it never had disk persistence.
- SQLite DB files are deliberately **excluded** from Repository ZIP and Git;
  existing world data must be backed up and retained by the user.
- Future C features are documented as `PLANNED`/`BLOCKED`/`DEFERRED`, not built.

## Residual risks / gates pending

1. Run ForgeGradle with JDK 17 and network access; this host cannot verify it.
2. Test actual Qwen model with the new B1 endpoints and confirm the persisted
   transcript across a backend restart (Windows commands in `docs/b1-verification.md`).
3. Validate Minecraft legacy `/npcortex ask` has no regression under real Forge.
4. Localhost only; there is no API authentication, authorization, multi-process
   conversation ordering, DB encryption, semantic memory or NPC-NPC dialogue.
5. Dimension identity is currently fixed per actor; dimension transfer is deferred.
6. Acknowledged client replies are not protected by idempotency keys; interrupted
   HTTP responses followed by manual retry may produce repeated turns. B2 planning.

**Decision:** B1 code candidate ready for user testing. **Quality Gate incomplete.**
No Git, branch, merge, commit or push operations were performed.
