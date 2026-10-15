# NPCortex C0 — Modular General-Purpose Headless Agent Runtime

**Status: implementation candidate; Windows/Qwen/Forge user quality gates pending.**

C0 adds only the BACKEND of an AI agent. It **does not spawn, move, mine, build,
shoot or interact with the Minecraft world**. All examples use a **mock Forge
sensor/executor**; actual C1 (Custom ServerPlayer/FakePlayer-based NPC) must
implement and verify the bridge later. The server is localhost-only and has **no
caller authentication**; do not expose it to untrusted clients.

## Architecture and ownership

- `agent_contracts.py`: bounded, validated observations and success predicates.
- `agent_skills.py`: modular SkillRegistry and pre-/postconditions. These are
  action **descriptions**, not Forge implementations.
- `agent_runtime.py`: persistent goal/observation/decision/action state machine;
  Qwen chooses only a skill advertised in the snapshot; a new reported sensor
  snapshot verifies results. There is **no timer/background loop**.
- `world_store.py`: additive schema **v4** (four new tables), without rewriting
  B1/B2/B3 rows. Existing `/v1/chat`, `/v1/actor-chat`, `/v1/dialogues`,
  `/v1/social-runs` remain available.
- `service.py` and `server.py`: new C0 HTTP boundary with existing rate/model
  limits and the original **8192-byte POST body limit**.

### State machine

`pending` (created, no observation) → `active` (sensor data received) →
`pending action` (one immutable action request) → `active` (failed/unverified) or
`completed` (independently observed success criteria) or `failed` (budget used).
Qwen may select `blocked` instead of a skill; a new observation can reactivate
it. Three consecutive model failures/invalid decisions also block until a new
observation. Pending action remains pending **across restart**: never re-run it
blindly; resolve the same action UUID using a *new* observed snapshot.

**Important:** `succeeded` is an **executor claim**, *not verification*. The
selected skill's postcondition must also be observed to set
`action_status=succeeded`. If not, it is `unverified`. A goal's success criteria
are independently evaluated against the new sensor snapshot. An action may be
reported failed yet the goal independently observed complete because another
actor/environment has already achieved it.

## HTTP API (additive)

All UUIDs are canonical lowercase nonzero UUIDs. Use existing
`POST /v1/worlds` and `POST /v1/actors` first.

### 1. Create goal — `POST /v1/agent/goals` → 201

```json
{
  "world_id": "WORLD_UUID",
  "dimension_id": "minecraft:overworld",
  "actor_id": "ACTOR_UUID",
  "objective": "Break the log at 1,64,1 and confirm it is gone",
  "success_criteria": [
    {"kind": "block_is", "position": [1,64,1], "block_id": "minecraft:air"}
  ],
  "max_decisions": 8
}
```

One to eight predicates, all must be satisfied. Supported predicates:
`block_is(position, block_id)`, `inventory_at_least(item_id, count)`,
`position_near(position, distance)` (distance 0.1–32). Optional `goal_id`
allows idempotent registration. Goals and actions are always scoped to their
registered actor + world + dimension. `max_decisions` is 1–24.

`GET /v1/agent/goals/{goal_id}` retrieves goal, last snapshot, status,
`pending_action_id`, decision/failure counters, and all action ledger entries.

### 2. Report trusted sensor facts — `POST /v1/agent/goals/{id}/observe`

```json
{
  "observation_id": "FRESH_UUID",
  "snapshot": {
    "world_id": "WORLD_UUID",
    "dimension_id": "minecraft:overworld",
    "actor_id": "ACTOR_UUID",
    "position": [0,64,0],
    "inventory": {"minecraft:oak_log": 0},
    "blocks": {"1,64,1": "minecraft:oak_log"},
    "available_skills": ["break_block"],
    "nearby_entities": [],
    "nearby_drops": []
  }
}
```

Mandatory snapshot fields: identity, position, **full** item-count inventory,
**sparse observed** blocks, `available_skills`. Optional `nearby_entities` with
`{entity_id,type_id,position}` and `nearby_drops` with
`{item_id,count,position}`. Sparse unknown blocks **do not count as air**.
`available_skills` must reflect what the current C1 executor can perform in
this situation; it is intersected with Backend SkillRegistry. Maximum 64 item
types, 128 observed block positions, 24 nearby entities, 24 drops and 6000
UTF-8 bytes in a canonical snapshot. Values are validated before persistence.

New observation updates a goal. If all success criteria are *already observed*,
the goal is immediately `completed`; Qwen cannot override that evidence. Same
observation UUID + same snapshot is idempotent, different content returns 409.
An action that is still pending must be resolved through its **result** route,
not through `observe`.

### 3. Ask Qwen to choose — `POST /v1/agent/goals/{id}/decide`

```json
{"request_id": "DECISION_UUID"}
```

Qwen must return structured JSON matching one of:

```json
{"action":"use_skill","skill_id":"break_block","args":{"position":[1,64,1]}}
```

```json
{"action":"blocked","reason":"Need safer access"}
```

The response returns `decision`, `action_id` (UUID or null), `request_id`,
`goal_id`, `replayed`. Qwen **does not execute** Minecraft actions. Successful
skill selection persists exactly one pending action. Reusing the same request
UUID replays it without another Qwen call; a new request is forbidden until the
pending action has a correlated result. The model is retried at most twice for
invalid structured decisions; no fallback action is silently selected.

Skill contracts presently registered:

| Skill | Arguments | Minimum precondition | Postcondition |
|---|---|---|---|
| `move_to` | `position` | Target not already reached | Observed position within 1.5 blocks |
| `break_block` | integer `position` | Non-air block is actually observed | Same position observed as `minecraft:air` |
| `place_block` | `position`, `block_id` | Observed air + block item in inventory | Same position observed as selected block |
| `pickup_item` | `item_id` | Matching nearby drop observed | Full inventory count increases by ≥1 |

These are provisional minimal interfaces for C1 bridge validation, not hardcoded
Tree/Mining/Building planners. Forge must enforce real reach, collision,
permission, tools, durability, inventory and item use; C0 cannot guarantee any
of them. Skill arguments never act as direct server commands without C1 checks.

### 4. Submit action result — `POST /v1/agent/actions/{action_id}/result`

```json
{
  "result_id": "RESULT_UUID",
  "status": "succeeded",
  "observation_id": "NEW_OBSERVATION_UUID",
  "snapshot": {
    "world_id": "WORLD_UUID", "dimension_id": "minecraft:overworld", "actor_id": "ACTOR_UUID",
    "position": [0,64,0],
    "inventory": {"minecraft:oak_log": 1},
    "blocks": {"1,64,1": "minecraft:air"},
    "available_skills": ["break_block"],
    "nearby_entities": [], "nearby_drops": []
  }
}
```

`status` is `succeeded` or `failed` (the bridge's report). A **fresh**
`observation_id` is mandatory. The backend evaluates the expected effect
against this new snapshot and returns `verified`, `action_status` and updated
`goal`. The action UUID is the idempotency key the C1 Forge executor must use;
never execute the same UUID twice on retry/restart. An identical second result
UUID/body replays, a conflicting second result is rejected with 409.

Errors retain `{"error": "...", "code": "..."}`. Typical: 400 bad contract,
404 unknown goal/action, 409 stale/pending/identity conflict, 429 session
busy, 502 model error, 503 storage/model capacity issue.

## C1 Handoff — required contract (design only)

C1 Player-based NPC implementation belongs to the **separate Forge chat**:

1. Persist `world_id` in the Minecraft save; persist `actor_id` with NPC identity.
   Never regenerate those IDs on restart. Track dimension transitions through an
   **explicit future migration protocol**; v4 actor dimension is immutable.
2. Implement a server-authoritative sensor converting actual loaded-world data
   to the exact snapshot schema above. Never send guessed/unloaded block states
   as `minecraft:air`. Ensure item counts match *the actual NPC inventory*.
3. Advertise only actually available skills. Validate each requested action on
   the server's game thread (reach, world boundary, permissions, inventory),
   dispatch to the player-based executor, and preserve action UUID so retries
   cannot duplicate block breaks, placements or item consumption.
4. After execution, observe new actual world state and submit the correlated
   result. There must never be `completed` just because Qwen says so.
5. Network/Rendering/Movement/Player Lifecycle remain C1 responsibilities and
   must pass Forge 1.20.1 feasibility tests. Do not assume `FakePlayer` is a
   login-capable network player or that any physical skill is already implemented.
6. Localhost HTTP currently lacks authorization. Before exposing beyond a
   trusted single-player host, add mutually authenticated transport and a
   trusted bridge identity; caller-supplied snapshots are not cryptographic
   proof of game truth.

## Explicitly deferred

No Minecraft entity, no Navigation3D, no fully autonomous world loop, no
self-initiated agenda, no realistic tool-use timing, no tree harvesting,
mining/building/survival simulation, no Forge world actions, no C-world feature
implementation. C0 provides the interface and headless verification loop only.

See `docs/c0-windows-verification.md` for tests and rollback/backup steps.
