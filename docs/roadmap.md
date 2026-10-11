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
