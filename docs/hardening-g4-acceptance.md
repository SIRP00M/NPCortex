# Hardening Sprint G1/G2/G4: acceptance and handoff (2026-10-26)

Status vocabulary: PASS / FAIL / PENDING / BLOCKED / NOT TESTED, plus UNVERIFIED for claims with no executed evidence.
Evidence source is stated for every line. "User-confirmed" means reported by the project owner; no engineering agent executed it.

## 1. Accepted scope
Accepted for the **tested Windows single-player** scope only.

| Gate | Status | Evidence |
|---|---|---|
| G1 M04 SQLite test leak (`backend/tests/test_m04_acceptance_review.py`, `committed()` helper) | PASS | 4/4 tests under `-W error::ResourceWarning`; test-only change |
| G2 backend hardening (`config.py`, `server.py`, `test_http_security.py`) | PASS | 9 new tests; Host/Content-Type/loopback-only/413 message |
| Python regression | PASS | `unittest discover -s backend/tests`: 435 tests, OK, 1 skip, repeated 6 times |
| G4 Java compile (`compileJava`, `compileTestJava`, offline Gradle 8.8, JDK 17) | PASS | BUILD SUCCESSFUL |
| G4 Java self-tests | PASS | 52 direct + `verifyG2Identity`, `verifyG3TerminalContract`, `verifyTransportOutbox`, `verifyM03` |
| G4 Windows gameplay: C1 Spawn/Despawn/Motion | PASS (user-confirmed) | Owner report |
| G4 Windows gameplay: Save/Quit/Rejoin | PASS (user-confirmed) | Owner report |
| G4 Windows gameplay: active C2 goal shutdown | PASS (user-confirmed) | Owner report |
| G4 Windows gameplay: Follow regression | PASS (user-confirmed) | Owner report; supplied `latest.log` search showed no NPCortex server-thread assertion exception |

## 2. Changed files
- Backend: `backend/src/npcortex_backend/config.py`, `server.py`
- Backend tests: `test_m04_acceptance_review.py`, `test_http_security.py` (new), `test_http_request_lifecycle.py` and `test_stability.py` (helpers now send `Content-Type: application/json`; no assertion changed)
- Java: `ServerThread.java` (new), `C2AgentRuntime.java`, `C2CancellationRecovery.java`, `C1P0Runtime.java`, `C1P1MotionRuntime.java`, `src/test/.../ServerThreadSelfTest.java` (new)
- Docs: this file, top block of `PENDING_TESTS.txt`
- Asserted sites: `ServerThread.require(...)` is the first statement before any mutation in C2 `cancel`, `interrupt`, `beforeServerStop`, cancellation reconcile tick; C1 `spawn`, `despawn`, `detachForLifecycle`, sync tick, four viewer packet handlers, five viewer events; motion `moveInternal`, `turn`, `jump`, `settle`, `stop`, `onTick`, `beforeDespawn`.

## 3. Still UNVERIFIED (do not report as PASS)
- Dedicated-server shutdown (`stop`, Ctrl+C) with active NPC and C2 goal. The assertions in shutdown paths rely on `ServerStoppingEvent` firing on the server thread; only single-player evidence exists.
- Multiplayer and multi-viewer (second player logout, dimension change, respawn while NPC moves).
- Comprehensive thread enforcement. Intentionally unasserted: `C1P0Runtime.onServerStarted/onServerStopping/spawnExisting`, `activeAvatar`/`liveAvatar` and other read accessors, `C1P1MotionRuntime.c2Ticket/c2Receipt/c2WalkProgress`, vanilla hooks (`onVanillaWorldTick`, `onVanillaKnockback`, `afterSpawn`), `beforeServerStop`. `ServerThreadSelfTest` covers only the null-server branch.
- Fault injection: deliberate wrong-thread call, backend loss, ACK loss, restart during reconcile.
- One unexplained Python test error seen once after the first G2 edit, not reproduced in 6 later full runs; cause unknown.
- PowerShell scripts `test-b2-dialogues.ps1`, `test-b3-social.ps1`, `test-c0-headless.ps1` were checked by reading (they send `application/json` to loopback), not executed.
- Forge `build`/reobf: only `compileJava` was run.

## 4. Preservation statement
No change to durable journals, ACK, HOLD, outbox, SQLite records, recovery contracts, movement ownership or navigation logic. Old M03 `C2_ACCEPTED`/HOLD records remain preserved.

## 5. Remaining foundation work (proposal, NOT approved)
Each item needs its own explicit approval of an option. Findings below come from reading source this session; none were implemented.

### Priority order
1. **G3: token auth for legacy routes** (largest open security gap; independent of the others).
2. **F3: identity-safe cleanup after failed NPC spawn** (small, contained, low risk).
3. **F1: shutdown branch cleanup** (small; comment/dead-code only if the branch is confirmed unreachable).
4. **Graphify-C: stub contamination and contract map** (tooling/documentation; no runtime risk).
5. **M04 gates 0-6** (gate 0 first; everything else depends on it; gameplay-evidence heavy).

### G3: legacy-route authentication (implemented in phase 1, see section 7)
Facts: legacy POST/GET routes (`/v1/chat`, `/v1/worlds`, `/v1/actors`, `/v1/agent/*`, dialogues, social) are unauthenticated. G2 only blocks browsers and non-loopback binding. The Java clients `ChatBridge.ask` and `c2Post` send no token; `CPlusLocalAuth.readToken()` and Python `read_secret()` already exist. 8 backend test files and 3 PowerShell scripts use the legacy routes. `cplus_local_auth.py` skips the file-permission check on Windows.

| Option | Description | Trade-off |
|---|---|---|
| A | Leave as is, rely on G2 | No work; other local users/processes remain unblocked |
| B | Token required on all routes, always on | Strongest; breaks 8 tests + 3 scripts, needs Java token read |
| C | Token behind `NPCORTEX_REQUIRE_TOKEN` (default off), enforced in tests by flag | Compatible rollout; two modes to test |
| D | Token for state-changing legacy POSTs only; reads stay open | Smaller change; transcripts remain readable by UUID |
| E | C, then flip default after Java/scripts/tests updated, plus Windows ACL check | Most complete; spans two sprints |

Recommendation: **E, delivered as C first.** Risks: Java reads the secret file on the server thread unless cached once at startup (cache it); a stale cached token after rotation needs a defined refresh. Gates: backend negative tests (missing/wrong token), Java compile and self-tests, Windows chat + C2 goal smoke test.

### F3: failed-spawn cleanup (implemented as Option C, see section 7)
Source facts (`C1P0Runtime.spawn` / `spawnExisting`): in `spawnExisting`, `LIVE.put` happens before `afterSpawn`; if `afterSpawn` throws, the avatar is discarded but the `LIVE` entry stays. The `spawn` catch block calls `store.deactivate(name)` keyed by name, not by profile id.

| Option | Description |
|---|---|
| A | No change |
| B | In `spawnExisting`'s catch, remove the `LIVE` entry only if it is the same avatar instance (identity check), then rethrow |
| C | B plus make `spawn`'s `deactivate` use the profile id of the entry it created |
| D | Introduce a transactional spawn helper |
| E | Defer until a failing case is reproduced |

Recommendation: **C** (small, preserves identity records, no persistence-schema change). Gates: unit test where practical (stub-limited), Windows spawn/despawn regression, confirm no identity re-id or SQLite reset. The failure is currently theoretical: no reproducing case has been run (UNVERIFIED).

### F1: shutdown branch
Source facts: `C1VanillaLifecycleCoordinator.onServerStopping` calls `C2FollowRuntime.beforeServerStop`, `C1P0Runtime.onServerStopping`, `C1P1CLifecycleRuntime.onServerStopping`, `C2AgentRuntime.beforeServerStop`, `CPlusE3G3Runtime.onServerStopping`, in that order. `C1P0Runtime.onServerStopping` clears `LIVE` after calling `C1P1MotionRuntime.beforeServerStop`, so by the time `C2AgentRuntime.beforeServerStop` runs, `run.avatar()` may be null and its motion-stop branch may be unreachable. I have not proven this: it needs a trace of `Run.avatar()` against `LIVE`.

| Option | Description |
|---|---|
| A | Leave as is |
| B | Keep the order; remove the confirmed-unreachable branch; correct the comment |
| C | Reorder so C2 runs before C1P0 (changes shutdown semantics) |
| D | Add logging to prove reachability first, remove later |
| E | Combine D then B |

Recommendation: **E** (evidence before deletion); B alone is acceptable if the trace is produced from source. Do not reorder (C) without owner approval. Gates: compile, self-tests, dedicated-server and single-player Save/Quit with an active C2 goal.

### Graphify-C
Facts: `graphify-out` lists "Minecraft Server Stubs" and test-stub classes as graph communities mixed with real sources. No mapping of Java-HTTP-Python contracts exists beyond what the code shows.

| Option | Description |
|---|---|
| A | None |
| B | Exclude `forge-mod/src/test-stubs` from the graph |
| C | B plus a hand-written contract table (route, Java caller, Python handler, auth, size limit) in `docs/architecture/` |
| D | C plus an automated check that fails when a route in `server.py` has no table row |
| E | Full semantic re-extraction (costly, flagged by graphify itself) |

Recommendation: **C**. Gates: table reviewed against `server.py` and `ChatBridge`/`CPlusTransport`; `graphify update .` only (AST, no cost). No runtime code touched.

### M04 gates
Status from `OPTION_D_IMPLEMENTATION.md`: Gate 0 PARTIAL/HOLD (source-command provenance and a common live C1/C2/Follow claim guard are not proven); Gates 1-6 PENDING or NOT IMPLEMENTED; no mutation entrypoint is registered. Old G2 HOLD rows must stay fenced.

| Option | Description |
|---|---|
| A | Pause M04 until G3/F3/F1 are done |
| B | Design-only audit of Gate 0 (provenance + shared claims) now, no code |
| C | Implement Gate 0 provenance in Java and Python, no mutation |
| D | Gates 0-2 as one sprint with Windows evidence per gate |
| E | B first, then C after approval of the design |

Recommendation: **E.** Gate 0 touches ownership and persistence boundaries (CRITICAL level under the project policy), so it needs an audit, A-E design and a `reviewer` pass before code. No mutation, no clearing of HOLD/outbox records, gameplay stays PENDING until you supply evidence.

## 6. Approval gate
Sections 5 and 6 originally recorded nothing as approved. Since then the Owner approved G3 Option E and F3 Option C (section 7). F1, Graphify-C and the M04 gates remain unapproved.

## 7. Update: G3 phase 1 and F3 Option C (2026-10-26)
Both were approved by the Project Owner (G3 Option E, F3 Option C). Sections 5's G3/F3 entries are superseded by this section; F1, Graphify-C and the M04 gates remain NOT STARTED and unapproved.

| Gate | Status | Evidence |
|---|---|---|
| G3 backend token (`NPCORTEX_REQUIRE_TOKEN`, default OFF) | PASS | `backend/tests/test_legacy_token.py`: 7 tests |
| G3 compatibility mode (flag off) | PASS | legacy routes work without a token; existing suite unchanged |
| G3 enabled mode, all 11 legacy GET/POST routes | PASS | missing, wrong, truncated, extended and non-ASCII tokens get 403 `forbidden`; correct token passes auth; `/health` stays open; C+ routes always require the token; fails closed with no secret; token never in response body |
| G3 Java client (`ChatBridge.ask`, `c2Post`) sends token when one exists | PASS (compile only) | `compileJava`; behavior against a flag-ON backend is UNVERIFIED |
| F3 identity-safe spawn failure cleanup | PASS (compile only) | `compileJava`; failure path never exercised (NOT TESTED) |
| Python regression | PASS | 442 tests, OK, 1 skip, `-W error::ResourceWarning` |
| Java regression | PASS | 52 direct self-tests + `verifyG2Identity`, `verifyG3TerminalContract`, `verifyTransportOutbox`, `verifyM03` |

### G3 changes
- `server.py`: `token_required()`, shared `_token_ok` (loopback client + shared secret, bytes compare), `_legacy_auth_ok` applied to every legacy GET and POST except `/health`; C+ routes reuse `_token_ok` with unchanged 403 message; startup banner states the mode.
- `ChatBridge.java`: `withLocalToken` adds `X-NPCortex-CPlus-Token` from `CPlusLocalAuth.readToken()` when available, never logged; omitted when no secret exists.
- Loopback-only binding, Host check and Content-Type check unchanged. No unauthenticated LAN path was added. The default was NOT flipped (needs separate Owner approval).
- Windows token file: inspected once with `icacls`; bridge directory and `cplus-f1.secret` are limited to SYSTEM, Administrators and OWNER RIGHTS. No code verifies Windows ACLs.

### F3 changes (`C1P0Runtime.java` only)
- `spawnExisting` catch: removes the `LIVE` entry only if `LIVE.get(profileId) == avatar` (instance identity), so a replacement avatar cannot be removed. The full detach path is deliberately not used because it rewrites the persisted position.
- `spawn` catch: deactivates the name only if the stored record still has the profile id this attempt spawned (`spawnedProfile`).
- Deviation from the literal Option C ("deactivate by profile id"): `C1IdentityData` has no profile-id deactivate and adding one would change the identity persistence class, so the equivalent check was done in `C1P0Runtime`. A literal profile-id method remains a separate change.
- Motion sessions left after a failed `afterSpawn` are dropped by the existing tick check (`avatar.isRemoved()` or not the live avatar).

### New UNVERIFIED / NOT TESTED
- Java client against a backend with `NPCORTEX_REQUIRE_TOKEN=1` (needs Windows chat + C2 goal).
- PowerShell scripts `test-b2-dialogues.ps1`, `test-b3-social.ps1`, `test-c0-headless.ps1` send no token and will get 403 with the flag on; not executed, not modified.
- F3 failure path (no fault injection; pure-Java stubs cannot build an avatar).
- The token file is read on every Java call from the server thread (not cached).

### Windows checks for the Owner
1. Flag off: normal chat and a C2 goal (regression).
2. `NPCORTEX_REQUIRE_TOKEN=1` on the backend: repeat chat and C2 goal; confirm they work and that a plain `curl` to `/v1/chat` returns 403.
