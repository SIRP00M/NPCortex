# NPCortex — Long-term Autonomous Companion Roadmap

**Current implementation in this ZIP:** C0-B provides a **headless general-purpose agent protocol** (Goals, Snapshots, advertised Skill contracts, Qwen planning, persisted action requests and evidence-based verification). It does **not** read a live Minecraft world or directly actuate a player/NPC. See [C0 protocol](c0-headless-agent-runtime.md), [roadmap](roadmap.md) and [single Changelog](../CHANGELOG.md).

| Capability | Current foundation | What's still missing |
|---|---|---|
| Durable actor identity | B1 UUID registry and SQLite | Binding to a real player-based Minecraft NPC lifecycle |
| Multiple conversational actors | B2 explicit dialogues and B3 caller-stepped social runs | Autonomous initiation, relationships, scalable population policies |
| World perception | C0 typed, validated observation snapshots | Real server-authoritative Forge sensors and freshness handling |
| AI skill planning | C0 Qwen decisions over advertised verified SkillRegistry contracts | Actual movement, breaking/placing, inventory integration on game server |
| Evidence-based execution | C0 idempotent requests/results, conservative postcondition checks | Real-world action executor, crash-safe side effects and gameplay verification |
| Navigation & world survival | C0 **contracts**, not locomotion | Player-like 3D navigation, recovery, resource gathering, building and survival |
| C1 True Player-based NPC | Approved architectural direction | Prove network, rendering, spawn/tracking and lifecycle first |
| Optional mod interoperability | Integrations folder contains plans | Compatibility testing and explicit opt-in integrations (e.g. TaCZ) |

## Development sequence

1. **C1-P0 feasibility:** Prove `ServerPlayer`/`FakePlayer`-based player-like NPC identity, spawn, network tracking, rendering, save/load lifecycle. No assumptions that Forge `FakePlayer` is a visible player avatar.
2. **Later C1 milestones:** Build safe server-authoritative movement, inventory and block interactions with real-world observations and correlated action results, only after independent approval.
3. **Perfect vertical slices:** Complete one real task reliably with verified world outcomes, collection and cleanup before expanding into unrelated gameplay capabilities.
4. **Future broader autonomy:** Scheduling, social life, goal selection, long-running plans and mod compatibility once lower-level execution is stable.

The existing backend is not a fully autonomous agent in Minecraft. Avoid implementing mock classes solely to suggest missing integrations exist. Keep runtime, Forge world adapter and integrations modular and validate every real effect server-side.
