# NPCortex C0 — Complete Headless Agent Runtime (Candidate)

Based on `NPCortex_v0.1.1-alpha_B3B_HTTPLifecycleHardening_FullRepository.zip`.

- New `agent_contracts.py`: validated world/dimension/actor-bound snapshots,
  observed blocks, inventory, nearby entities/drops, and typed success criteria.
- New `agent_skills.py`: modular capability definitions and strict arguments,
  preconditions and independently checkable postconditions (`move_to`,
  `break_block`, `place_block`, `pickup_item`). No real-world skills are executed.
- New `agent_runtime.py`: bounded goal/decision/action orchestration using
  Qwen JSON, no hardcoded tree-specific policies, request/observation/result
  idempotency, pending action recovery, conservative verification, model
  failure budget and per-goal in-process serialization.
- Schema v4 adds `agent_goals`, `agent_observations`, `agent_decisions`,
  `agent_actions` without modifying old B1–B3 records or API contracts.
- Additional HTTP routes under `/v1/agent/*` via existing HTTP server.
- Windows scripts: `test-c0-regression.ps1` and `test-c0-headless.ps1`.
- Documentation: `docs/c0-headless-agent-runtime.md`,
  `docs/c0-windows-verification.md`, API/README/Future Roadmap updates.
- All Forge Java sources unchanged. No GitHub operations.

**Explicitly not included:** Forge player-based NPC Entity/Movement/Rendering,
real Minecraft perception/actuation, crafting, mining, tree harvesting,
autonomous world loop, relationships and daily schedules.
