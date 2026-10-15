# C0-B Structured Decision Reliability — contract and limitations

**Scope:** Python Backend Agent Planner only. The NPCortex Forge Java bridge,
Minecraft player-based C1 avatar, SQLite tables (v4), B1-B3 social/actor code,
and skill executor/verifier behavior are not changed.

## Why C0 previously failed on Windows

`ollama list`, `ollama ps` and a manual JSON `/api/chat` test passed.
The latest C0 agent goals had `planner_failures=1` and
`last_error=invalid_model_decision`, not `model_unavailable`.
The original Planner prompt listed four skill names as alternatives but used
`args:{...}` rather than listing their precise fields, and retry did not
identify which requirement was violated. Two rejected decisions raised
`ModelError`, causing a misleading HTTP 502 `provider_unavailable`.

The model's actual raw rejected JSON was not captured, so the precise invalid
field from the original user's run remains unknown.

## New planner contract

The planner receives the **actual advertised** Forge skills from the validated
World Snapshot, each with descriptions, argument JSON schemas, and a bounded set
of grounded candidate examples. It also receives a JSON Schema union for:

- `{"action":"blocked","reason":"short explanation"}`
- `{"action":"use_skill","skill_id":"<advertised skill>","args":{...}}`

Only advertised skills appear in the `use_skill` union. Examples are derived
from observed blocks, inventory, and nearby drops. No example can grant a skill.
For movement, the model uses the goal's requested destination; the backend does
not invent one.

For `OllamaProvider` the C0 planner sends the JSON Schema in `/api/chat.format`
with `temperature:0` and `num_predict:256`. **This does not affect B3**:
B3 retains `format:"json"` and the original generation settings.
Other model provider adapters implementing only `chat_json(messages)` continue
to receive the same prompting information and undergo strict validation.

The server always validates the complete returned JSON and invokes the unchanged
`SkillRegistry.prepare()` preconditions. The schema **does not replace server
validation** and no AI decision is taken as proof of a Minecraft action.

On the second model attempt, the planner supplies a limited, code-generated
reason (e.g. `unadvertised_skill`, `invalid_json`,
`invalid_skill_arguments: unsupported skill arguments`) instead of merely saying
that a previous decision was invalid. The backend logs codes and attempt numbers,
not raw model messages or the Snapshot.

## HTTP errors and persistence

| Condition | Response | `agent_goals.last_error` |
|---|---|---|
| Provider HTTP/timeout/connection failure | 502 `provider_unavailable` | `model_unavailable` |
| Qwen responds but both decisions fail validation | 502 `planner_invalid_decision` | `invalid_model_decision` |
| Qwen returns a valid blocked decision | Normal decision result (`blocked`) | Model's bounded reason |
| Valid skill decision | Normal decision with pending action | Empty |

Planner failures retain the **existing budget of three failed decide calls**;
no rejected response creates an action or increments `decisions_made`. The
existing SQLite v4 structure, idempotency, and evidence-based verification are
unchanged. Existing goals with one invalid failure remain in their database and
can be retried after restarting the upgraded backend; do not edit database rows.

## Windows QA

From a clean replacement of the repo directory and with the Python backend
stopped during replacement:

```powershell
.\scripts\test-c0-planner.ps1 -Repeat 3
.\scripts\test-c0-regression.ps1 -Repeat 3
.\scripts\dev-backend.ps1
# open second PowerShell:
.\scripts\test-c0-headless.ps1
```

`test-c0-headless.ps1` requires **real Ollama/Qwen** but sends simulated
Minecraft World Snapshots. It expects two `break_block` decisions, rejects a
fake success, then completes on observed air. After restarting Backend:

```powershell
.\scripts\test-c0-headless.ps1 -VerifyGoalId 'GOAL_UUID'
```

If the Qwen test still fails, check Backend logs for
`C0 decision validation rejected ... reason=<code>` and report that code.
The test cannot establish in-game Forge behavior. Do not mark C0 accepted
until the real Windows/Qwen checks pass.
