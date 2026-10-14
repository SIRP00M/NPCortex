# NPCortex B2 — Test Report

**Delivery:** `v0.1.1-alpha` B2 Multi-Actor Dialogue Engine — implementation candidate.
**Source base:** `NPCortex_v0.1.1-alpha_B1_WorldActorIdentity_FullRepository.zip`.
**Scope:** B2 general multi-actor dialogue, additive SQLite migration, legacy API compatibility; no C functionality.

## Verified here (Linux / Python 3.13.5)

| Test / evidence | Result |
|---|---|
| Python `unittest discover -s backend/tests -q` | **64/64 PASS**, three consecutive runs |
| Python `compileall` (backend source and tests) | PASS |
| B1 41-test suite included in 64 total | PASS (one schema-version assertion updated for v2) |
| v1→v2 migration of actual B1 DDL and sample original records | PASS; B1 tables/transcript remain intact |
| Re-opening migrated DB and B1 actor conversation | PASS |
| New B2 multi-actor creation, player and NPC turns | PASS with mocked model |
| Three actors in one dialogue, correct speaker IDs and sequence | PASS |
| Same-speaker rejection, max-turn auto-close, manual close | PASS |
| Durable request_id replay after service restart, no model recall | PASS |
| Session ordering, SQLite optimistic sequence check, model error rollback | PASS |
| SQLite insert failure rollback and recovery | PASS |
| B1 private transcript excluded from B2 Qwen context | PASS (prompt inspected) |
| Real localhost HTTP B2 route tests | PASS with mocked model |
| OllamaProvider actual `/api/chat` HTTP transport through fake local server for B2 | PASS (model='qwen3:8b', stream=false, think=false) |
| Fresh `python -m npcortex_backend` startup and `/health` | PASS; fresh schema user_version=2 |
| Forge source compared with B1 | Unchanged |
| ZIP integrity and B1 repository coverage | Checked at packaging |

The Python test count includes 41 prior B1/A2 regression tests (with a version
assertion updated to expect the migrated v2) and **23 new B2 cases**.

## Attempted but not confirmed

- `cd forge-mod && ./gradlew --no-daemon --offline build` **FAILED TO START BUILD**:
  Gradle Wrapper attempted to download `https://services.gradle.org/distributions/gradle-8.8-bin.zip`
  and received `java.net.UnknownHostException: services.gradle.org`.
  This environment has **Java 21** rather than required development **JDK 17**.
  Result is **BLOCKED / NOT VERIFIED**, **not** a successful Forge Build.
- **Real Ollama and Qwen 3 8B** not available/tested here. The Ollama transport
  test used a local fake HTTP server and does **not** establish AI dialogue quality.
- **Minecraft gameplay** not executed here; the project owner must test Forge
  and legacy chat using the Windows checklist.
- **PowerShell `test-b2-dialogues.ps1`** was authored and reviewed, but a native
  Windows PowerShell runtime was unavailable in this environment; the script
  itself is **not claimed runtime-tested** here.

## Risks / limitations

- No authentication; local-only single Python server. World IDs provided over
  API are not yet linked to actual Minecraft save identifiers.
- Old B1 code cannot open v2 DB. Back up a stopped B1 DB before upgrade.
- One in-process A2 lease per dialogue, not cross-process distributed locking.
- Qwen receives at most the last 12 dialogue turns; stored B2 turns limited to 64.
- Idempotent `request_id` guarantees replay if the turn was committed; not
  exactly-once model invocation if backend crashed before SQLite commit.
- No character entity/renderer or autonomous social simulation. C remains roadmap only.

## Quality Gate status

**B2 = Implementation Candidate, pending Windows JDK 17 Forge Build, real local
Qwen, owner-run Minecraft/HTTP verification and acceptance.** Nothing was
Committed, Pushed, Merged or Branched on GitHub.
