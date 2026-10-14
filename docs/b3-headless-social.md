# NPCortex B3 — AI-Driven Headless Social Runtime

Status: **Implementation candidate**, pending real Qwen/Windows/Forge acceptance. This is a **bounded headless social conversation**, not an in-game autonomous NPC, background worker, or C world simulation.

## B3 behavior and guarantees

1. Create an ordinary B2 dialogue between 2–8 registered NPC actors in the same world and dimension (no player participants, no existing turns).
2. Create a `social_run` attached to that dialogue. Exactly **one run per dialogue**. `max_steps` 2–24, not greater than the dialogue's `max_turns`.
3. Caller explicitly sends `POST /v1/social-runs/<run-id>/step` with an **idempotent UUID request_id**. There is **no timer, background loop or real Minecraft entity**.
4. Qwen makes a separate **coordination decision** (JSON: `speak` with an allowed actor UUID, or `stop` after at least two turns), which the runtime validates. It may never select the last speaker or a nonparticipant. The Ollama adapter uses `format: "json"` only for the planner; the normal speech call uses regular chat.
5. If `speak`, the selected actor uses B2's character system prompt and the **dialogue-local shared transcript** to produce exactly one line. B1 private actor/player transcripts are not forwarded.
6. A B2 dialogue lock serializes B3 steps; manual B2 turns against an active social-run-owned dialogue return **409**, other dialogues remain independent.
7. Atomic `social_steps` rows record each completed action. The matching B2 speech uses the same request UUID. If the backend crashes between the B2 speech commit and B3 journal commit, retry **the same request_id** to reconcile without calling the model again. A different request is refused until recovery.
8. Runtime stops upon a model choice to `stop`, an operator stop, exhaustion of `max_steps`, a B2 turn cap, or **three consecutive model failures**. Failing model responses never persist fake speech. Retry an invalid coordinator output once before failing that step. A temporary model-capacity error does not consume the failure budget.
9. Everything is persisted in SQLite schema **v3**, which adds two tables without rewriting any v1/v2 tables. Backup database with backend **stopped** before migration. Migration is forward-only; do not use the B2 binary with a migrated v3 database.

## API

All POST bodies are JSON; localhost only; 8 KiB limit. Existing B1/B2 endpoints unchanged.

### Create headless run

`POST /v1/social-runs` → 201

```json
{"dialogue_id":"<existing-dialogue-uuid>","max_steps":4}
```

Responds with `run_id`, `dialogue_id`, `max_steps`, `status: active`, `steps: []`, `steps_completed: 0`, `steps_remaining: 4` and bookkeeping fields.

### Advance one model-coordinated step

`POST /v1/social-runs/<run-uuid>/step` → 200

```json
{"request_id":"<new-or-retried-uuid>"}
```

Response fields: `run_id`, `dialogue_id`, `step`, `turn` (null when model chose stop), `status` (`active`, `completed` or `stopped`), `steps_remaining`, and `replayed`. A `speak` step stores `speaker_id` and `dialogue_seq`; a `stop` step stores `reason: model_stop`.

Use a **new** request_id for each intended step. To safely retry an uncertain HTTP response or recover after a restart, use the **same** request_id. If an unjournaled speech exists, recover its request_id first before submitting any new one.

### Inspect or manually stop

`GET /v1/social-runs/<run-uuid>` → 200 (no mutation)

`POST /v1/social-runs/<run-uuid>/stop` with `{}` → 200 and status `stopped`.

Existing `GET /v1/dialogues/<dialogue-uuid>` remains the authoritative saved ordered dialogue transcript. B3 responses contain no world state and cannot move any Minecraft entity.

## Failure handling

| Condition | Response | SQLite effect |
|---|---|---|
| Unknown run | 404 `registry_not_found` | None |
| Invalid UUID / step payload | 400 `invalid_identity` / `invalid_request` | None |
| Already closed, ownership conflict, out-of-order step | 409 `identity_conflict` | None |
| Invalid JSON decision twice, failed speech inference | 502 `provider_unavailable` | Consecutive failure count increments; no new speech |
| Local provider busy | 503 `model_busy` | No new speech or penalty |
| SQLite unavailable | 503 `storage_unavailable` | Transaction rollback; if speech was committed, recover with original UUID |
| Overqueued same dialogue | 429 `session_busy` | No new speech |

**Security**: B3 is intended for a trusted single localhost backend process; there is no authentication, actor authorization, distributed queue, or multiplayer support. Never port-forward this API. Model text is never converted into game commands or real-world actions.

## C roadmap extension (design only)

A future approved C sprint may add **world-event inputs**, a **goal/policy interface**, a **Minecraft entity adapter**, social memory, and autonomous schedule orchestration. The B3 boundary separates **model-selected intent** from **runtime-verified durable dialogue**; all world truth and actual actions must eventually come from Minecraft's server-side authority. These are TODO/design concepts only; none is implemented in B3.

## Windows acceptance

Run these from repository root:

```powershell
.\scripts\test-b3-regression.ps1 -Repeat 3
.\scripts\dev-backend.ps1
```

With backend running, in a second PowerShell window:

```powershell
.\scripts\test-b3-social.ps1
```

Expect PASS lines for 2 Yui UUIDs, idempotency, no repeat speaker, exact journal/transcript count, bounded stop. Save `RUN` UUID and restart backend, then:

```powershell
.\scripts\test-b3-social.ps1 -VerifyRunId 'YOUR-RUN-UUID'
```

To check restarting mid-run instead, run the initial test with `-PauseAfter 2`, restart Backend, then use `-ResumeRunId` as printed by the script. If Qwen emits malformed JSON twice or the model is unavailable, the API returns 502 and does not store a fake dialogue turn. Keep the same request_id when retrying a failed/uncertain step.

Forge regression is separate: `./scripts/phase3-check.ps1`, `./scripts/dev-forge.ps1 -Task build`, `./scripts/dev-forge.ps1 -Task runClient`, then `/npcortex status`, `/npcortex ask yui Hello!` and invalid-character handling. Forge only tests the unchanged legacy bridge; B3 has **no in-game interface**.
