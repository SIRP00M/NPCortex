# NPCortex v0.7.0-alpha — Autonomous Behaviors (Phase 8)

Based on the tested **v0.5.7-alpha** code. Includes existing Custom Characters, In-Game Manager, Inventory, SQLite Memory, Natural Language Intent, Multi-Step Plans, and Action Dialogue.

### Added
- Owned NPC autonomous sensing, opt-in by default
- Hostile line-of-sight warnings with per-NPC cooldown
- Bounded retreat goal with Follow/Stay and action-plan priority protection
- `/npcortex auto on|off|status` plus persistence in world saves
- Optional `/v1/autonomy-reply` Qwen dialogue (non-executable, no memory writes)
- Forge COMMON configuration options for radius, interval, and reaction settings
- Python HTTP regression tests and pure Java decision-policy tests

### Not added
Autonomous attacks, mining, crafting, building, emergency teleportation, multi-NPC coordination, sophisticated pathfinding, or automatic autonomous multi-step LLM planning.

### Validation
Backend tests + isolated pure Java policy tests are run before packaging. **ForgeGradle build and in-game behavior must still be verified on Windows with JDK 17**.
