# NPCortex C0 → C1 Forge Player-based NPC Handoff

Use this file to start the **separate C1 Forge chat**. C1 design target is
**True Player-based NPC / Custom ServerPlayer or FakePlayer-based**, never
`PathfinderMob` as the NPC core. This is a *target pending feasibility tests*,
not an implemented or validated Forge NPC runtime.

## Baseline and division of responsibility

**Source baseline:** NPCortex C0 Full Repository ZIP (see the deliverable).

Backend chat owns:
- `WorldStore` v4 persistence, Actor UUID, world/dimension identity;
- Qwen Provider, prompts, bounded decision loop, goals and social runtime;
- `SkillRegistry` contracts and validation, result verification;
- `/v1/agent/*` and existing A2/B1/B2/B3 local API compatibility.

C1 Forge chat owns:
- proving ServerPlayer/FakePlayer network, rendering, tracking and lifecycle;
- persistent player-based NPC identity and avatar movement;
- reading real Minecraft server world observations;
- implementing action execution and checking Vanilla + mod compatibility;
- sending safe, correlated action results to Backend.

## Required integration path

1. Assign stable world/actor UUIDs (save in the Minecraft save, not based on
   display name). Register with existing `/v1/worlds` and `/v1/actors`.
2. Send server-authoritative data using
   `POST /v1/agent/goals/{id}/observe` and the snapshot schema documented in
   `docs/c0-headless-agent-runtime.md`.
3. Request `POST /v1/agent/goals/{id}/decide`; validate returned `action_id`,
   `skill_id`, `args`, `expected_effect`. **Qwen output must never directly
   mutate a Minecraft block without server validation.**
4. Execute the action ONCE per persisted `action_id` (idempotent execution),
   then report correlated `result_id`, `observation_id`, `status` plus a *new*
   measured world snapshot to `POST /v1/agent/actions/{id}/result`.
5. Check `verified` and goal state; if incomplete and not terminal, the
   controller may explicitly request the next decision. No background world
   scheduler exists in C0.

### What C1 MUST prove before claiming full integration

- Player-based NPC spawns/tracks/renders and despawns correctly in Single
  Player/Integrated Server, and survives save/load and dimension transitions.
- Movement, inventory, equipment, player-like block interactions actually work
  in Forge 1.20.1; do not assume standard Forge FakePlayer provides all this.
- Every physical skill enforces reach, resources, permissions, collision and
  correct player identity; side effects occur on the Minecraft server thread.
- Real sensor data is accurate, stale/unknown blocks do not count as air, and
  inventory represents the NPC's true state.
- A crash/restart while Action is pending cannot duplicate world mutations.
- Network/trust: the localhost C0 endpoint is unauthenticated, so the server
  must not be exposed remotely until an authenticated bridge is built.
- Tests for actual mod compatibility (e.g. TaCZ) are separate, **not** assumed.

## Development workflow

Audit real Repository and Forge 1.20.1 APIs → provide implementation options and
scope → request approval → implement only approved work → verify Windows JDK17
Forge Build + Gameplay + regression → Full Repository ZIP and tests; user
handles all GitHub actions. No Test, No Push.

No C1 Source Code or world simulation is implemented in the C0 ZIP.

## C0-B Planner error contract (implemented in this headless snapshot)

- `provider_unavailable` (HTTP 502): Ollama/model transport error, or model unavailable.
- `planner_invalid_decision` (HTTP 502): model responded but decision failed
  Schema/Skill Preconditions twice; **Forge must not execute an action**.
- Forge should handle neither error by inventing a fallback action; it should
  surface the failure and obey the existing goal failure budget.
- C0-specific Ollama schema changes are not part of Forge's protocol.
- See `docs/c0-planner-reliability.md`. The maintainer reported headless real-Qwen and Windows acceptance-script success; **C1 Forge gameplay is still unverified**.
