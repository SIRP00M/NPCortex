# C-F0-G — Unified Player Command and Public API Contract

**Contract-only in C-F0. None of the unified commands below are registered in Minecraft yet.** Existing `/npcortex c1 ...` and `/npcortex c2 ...` are unchanged.

## Proposed player-facing routes

| Proposed route | Responsible module | Initial permission proposal | Existing equivalent (partial) |
|---|---|---:|---|
| `/npcortex spawn <npc>` | C1 | operator 2 | `/npcortex c1 spawn <npc>` |
| `/npcortex follow <npc>` | C2 | operator 2 | `/npcortex c2 follow <npc> <player>`; requires target player resolution |
| `/npcortex stop <npc>` | C+ coordinator | operator 2 | `/npcortex c2 unfollow <npc>` for Follow only; not a safe universal alias without task resolution |
| `/npcortex status <npc>` | status aggregator | operator 2 | multiple legacy status commands with different meanings |
| `/npcortex debug c1 ...` | C1 diagnostics | operator 2, hidden from nonoperators | existing C1 commands |
| `/npcortex debug c2 ...` | C2 diagnostics | operator 2, hidden from nonoperators | existing C2 commands |
| `/npcortex debug action ...` | C+ diagnostics | operator 2, hidden from nonoperators | new future route |

The routing table resides in `CPlusCommands` but is **not** a registered Brigadier tree. `stop` has intentionally centralized ownership because future action cancellation must coordinate and acknowledge C1/C2 handoff. Mapping `c2 unfollow` to `stop` is a design *candidate*, not unconditional equivalence when other tasks exist. Keep old commands operational during any migration until explicit parity tests are complete. Player permission reductions need separate review and are not authorized by F0.

## Contract requirements for a later router (C-F3)

- Route by action semantics, not by internal implementation names. Reject ambiguous active task/Follow ownership rather than cancelling the wrong operation.
- Execute permission and identity checks on server, including who controls NPC and what a nonoperator may observe.
- Legacy calls remain explicit aliases with their old arguments and behaviors while migration is active. No silent changing of `c2 follow` player parameter.
- Commands, API methods and natural-language AI requests ultimately invoke the *same server-authoritative Action ID and permission policy*. No separate bypass path for Qwen.
- Tab completion: public routes only where permitted, diagnostics only to operators; require actual Forge/Brigadier testing.
- Do not create new Command trees until C-F3 scope is separately approved and current scripts/commands have parity tests.
