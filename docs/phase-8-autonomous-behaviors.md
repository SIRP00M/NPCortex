# Phase 8 — Autonomous Behaviors (v0.7.1-alpha)

## Supported behavior

This release adds **opt-in** autonomous sensing (server side) to each companion.
Nothing is triggered for an NPC until its owner runs `/npcortex auto on`.

- Once every 40 game ticks (default), NPCortex checks **loaded** owned companions within 32 blocks of their online owner.
- Looks for living `Monster` entities within 10 blocks and with line of sight.
- Rate-limits detection announcements per NPC to once every 600 ticks (30 seconds). On success, only the AI character reaction is shown (no duplicate [NPCortex] debug warning).
- If Follow is enabled, the monster is within 4 blocks, and no user action plan is running, the NPC **attempts** a bounded vanilla-pathfinding retreat away from the monster.
- If Stay is enabled, it warns without moving. No combat, block edits, item transfers, teleportation, or unchecked LLM actions.
- A Qwen reaction via `/v1/autonomy-reply` supplies one in-character warning *instead of* the raw system warning. This call does not modify conversation memory or select actions. If Ollama fails (or AI speech is disabled), the plain system warning appears **only** when `fallback_warning_when_ai_unavailable = true`.

The NPC does not yet understand complex threats, evaluate a safe route, identify creeper explosion states, or make free-form plans. Pathfinding may fail in tight terrain. A warning is based on line of sight and proximity rather than a guarantee that the mob is attacking the NPC.

## Commands (test world; cheats required)

```text
/npcortex spawn yui
/npcortex auto on
/npcortex auto status
/npcortex auto off
/npcortex follow
/npcortex stay
```

The commands operate on the nearest **owned** NPC within 12 blocks. The toggle is saved in the Minecraft world save and remains off by default for older saves.

### Suggested test

1. Make a backup of the world and turn mobGriefing off if desired.
2. Start the Backend, run the Forge development client, and spawn Yui.
3. Set `/npcortex auto on`, then `/npcortex follow`.
4. In a safe creative test world, summon a zombie or creeper within 10 blocks using vanilla commands and observe chat. At very close range, the NPC may pathfind away (if a valid path is available).
5. Run `/npcortex stay` and approach another hostile; the NPC should **not** initiate an autonomous retreat while staying, but may warn.
6. Run `/npcortex auto off`; no further autonomous warnings or movement should begin. Repeat after rejoining to verify persistence.
7. Temporarily stop Ollama. With the default fallback setting, a plain system warning appears once instead of a Yui reply. With `fallback_warning_when_ai_unavailable = false`, there will be no chat warning; detection and retreat still work.

## Configuration

Forge COMMON config `config/npcortex-common.toml`:

```toml
[autonomy]
    poll_ticks = 40
    alert_cooldown_ticks = 600
    alert_radius = 10.0
    retreat_radius = 4.0
    enable_retreat = true
    enable_ai_reactions = true
    fallback_warning_when_ai_unavailable = true
```

NPCortex uses no unconditional `Ollama` polling: inference only happens after a rate-limited danger warning. Lowering the cooldown aggressively can increase GPU use.

## Security and server limitations

- All world actions are on the server thread, with server-owned NPC/owner verification.
- The backend listens on localhost without authentication; never expose it publicly.
- NPC skin files and SQLite notes stay where they were. The update does not need a DB migration.
- Multiplayer owners must be near the NPC; no client-triggered autonomy commands are accepted by network packets.
- Only original `Monster` type detection is supported. Other mod entities may not extend that class and therefore may not be detected.
- This is reactive autonomous behavior, not general autonomous AI planning. It is deliberately conservative.

## AI-only chat display

With Ollama online and `enable_ai_reactions = true`, a threat produces exactly one owner-facing line, e.g. `Yui: A zombie is creeping near!`, instead of two lines. The sensor/cooldown/retreat behavior does not depend on Ollama. To suppress **all** system warning text, even during a backend outage, set `fallback_warning_when_ai_unavailable = false` (after closing the game and editing the active instance config). Then a backend outage results in no chat warning.
