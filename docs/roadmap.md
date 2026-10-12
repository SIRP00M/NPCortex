# Roadmap and project status

Legend: [x] starter source exists; [ ] not implemented/verified.

## 0.1 — Local AI dialogue baseline

- [x] Environment-configurable Ollama endpoint (`qwen3:8b` default)
- [x] Python HTTP chat endpoint with JSON validation
- [x] Example character packs (Guide, Yui)
- [x] In-memory per-player / per-character chat context
- [x] Forge `/npcortex status` and `/npcortex ask <character> <message>` command source
- [x] Python unit tests and GitHub Actions source for Python and Forge builds (Forge job awaits first run)
- [ ] ForgeGradle build and in-game connection verified on a developer workstation
- [ ] Reliable timeout/recovery and logging tests under game load

## 0.2 — Actual NPC

- [ ] Custom NPC entity, registration and spawn mechanics
- [ ] Conversation UI attached to entity
- [ ] Player/NPC identity mapping, saved NPC data
- [ ] NPC skin and rendering

## 0.3 — Character state

- [ ] Persisted conversational memory
- [ ] Personalities, relationships and event memory
- [ ] Localization / Thai support

## 0.4 — Validated actions

- [ ] Explicit action schema with game-side authorization
- [ ] Follow / stop / navigate and failure recovery
- [ ] Inventory, interactions, routines

## 0.5 — Optional mod integrations

- [ ] TaCZ compatibility adapter and tests
- [ ] Recruits conflict investigation (no assumptions)
- [ ] Combat / friendly-fire / permissions safeguards

## Later

- [ ] Multiple LLM providers via plugin interface
- [ ] Modpack installation docs, multiplayer auth/security, benchmarks
- [ ] Stable public character pack format


## October 2026: Phase 4 source delivered

Phase 3 has passed live in-game chat testing. Phase 4 adds source for spawn, player-shaped rendering, click-to-chat, follow/stay, NBT persistence. This update still requires a ForgeGradle build and in-game testing before these features may be marked done. See `docs/phase-4-npc.md`.

## Phase 5 source update — v0.3.0-alpha

- Character Pack metadata (display name / skin ID / wide or slim model)
- Local PNG skin loading via `config/npcortex/skins` (64x64)
- `/npcortex list` and `/npcortex sync`
- Per-instance NPC session separation (RAM only)
- Pending: Forge compile/runClient verification and custom skin runtime test

## Phase 6 — v0.4.0-alpha source update

- [x] Minecraft server-side one-shot world observation when NPC dialogue is sent
- [x] Backend `/v1/agent` JSON-schema response + output allowlist validation
- [x] Minecraft owner/range/entity checks for `follow`, `stay`, `look_at_player`
- [x] Debug commands `/npcortex observe`, `/npcortex actions`
- [x] Python HTTP/unit regression tests
- [ ] Phase 6 Forge build and Minecraft gameplay verified by developer
- [ ] Continuous perception, autonomy, item gathering, combat (not included)

- **v0.4.2-alpha:** ESC/Pause In-Game Manager for client-local skin import, hot reload, and companion appearance selection. Backend personality editing remains manual until a permissioned management API is designed.


## Phase 7 — v0.5.0-alpha

- Implemented in source: SQLite persistent NPC-scoped chat history, explicit notes,
  friendship activity score, ESC Memory Management UI, nine-slot NPC inventory,
  item take/give/pickup/equip/drop, jump, approach and short wander.
- Python tests pass. **Forge build and real-game tests remain pending**.
- Future: generalized behavior registry, path/arrival status, armor and tool usage,
  combat, TaCZ, mining/crafting, multiplayer authorization and sandboxing.


## v0.8.0-alpha — experimental world interaction

- Added limited singleplayer, opt-in block mining/placement, with item- and block-ID allowlists and server-side verification.
- Added four registered bag-only crafting recipes. No arbitrary workbench recipes yet.
- General action planner can chain mine/pickup/craft/place up to eight steps.
- Future work: actual RecipeManager support, claim/protection integration, tool-dependent block mining, navigation to targets, task precondition re-planning, and more useful world interactions.
