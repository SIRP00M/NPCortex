# NPCortex v0.8.5.5-alpha — Ground Pickup / Navigation Repair

Updated from v0.8.5.4-alpha after first real NPC test (mined=1 but stored=0, pathfinder could not reach 1 candidate position, stalls=5/5).

- Treat no-collision tall grass/flowers as traversable while choosing path destinations; still require supported, dry and safe locations, owner authority and actual vanilla route.
- Pickup eligibility now requires physical item proximity (≤1.5 blocks). Both observation and execution share the same predicate; Qwen uses `approach_drop` to walk closer.
- Context-driven tool affordances prevent useless block navigation after the mining quota is reached. This is action validation, NOT a prescribed task sequence.
- `/npcortex task status` exposes `observedDrops`/`pickupReady` from the most recent server observation to reveal whether drops are actually visible to the agent.
- Server remains authoritative for inventory, safe Minecraft world edits and job completion. No changes to SQLite, character packs, or legacy actions.

See `docs/agentic-brain-v0.8.5.5.md` for installation, tested scope and in-game reproduction. **Forge build and in-game tests are not claimed**.
