# NPCortex Development Roadmap

This roadmap describes the **code present in the local C0-B snapshot**, not an assertion that every change has already been pushed to [GitHub Development](https://github.com/SIRP00M/NPCortex/tree/Development). Sprint labels do not mean separately published releases. See [single Changelog](../CHANGELOG.md) and [main README](../ReadMe.md).

## Implemented backend foundations (headless unless stated)

| Stage | Implementation in this snapshot | Verification boundary |
|---|---|---|
| A2 | Forge `/npcortex ask` chat bridge and bounded backend session handling | Chat command, not an NPC entity |
| B1 | Durable world/actor identity and private player–actor transcripts (SQLite v1 origin) | Headless API, separate from legacy Forge bridge |
| B2 | Explicit multi-actor dialogue turns (SQLite v2 origin) | Caller-directed; no scheduler |
| B2-C | SQLite units of work and persistence stability | Preserves B2 schema at that stage |
| B3 | Caller-stepped, Qwen-selected headless NPC-to-NPC social runs (SQLite v3 origin) | No automated social world or Forge NPC |
| B3-B | Bounded request-body drain / Windows HTTP stability | No social behavior change |
| C0 | Durable Goals, observation contracts, skill requests, Qwen decision & postcondition check (SQLite v4) | **Mock/trusted world caller only**, no Minecraft action executor |
| C0-B | Planner schema/retry reliability and Windows acceptance-script fix | Owner-reported Windows script acceptance; Minecraft not tested by that script |

Python test reports and platform-specific checklists are under `docs/`. Acceptance of a backend-only feature does not automatically satisfy the Forge gameplay quality gate.

## Next: C1 True Player-based NPC — phased feasibility

**P0: Feasibility proof.** Evaluate custom `ServerPlayer`/`FakePlayer`-based identity, spawn, network tracking, rendering and lifecycle with Forge 1.20.1 and real tests. The standard `FakePlayer` alone must not be assumed to be a visible, fully functioning player. `PathfinderMob` is not the proposed primary architecture.

**P1/P2 and later: Separate proposals, not shipped here.** NPC movement, inventory, equipment, player-like interaction, reliable server world perception/execution and backend agent contract binding follow **only** after P0 evidence and explicit scope approval.

**Long-term:** General-Purpose Agent Runtime and fully autonomous companion behavior, with carefully verified vertical slices (e.g. complete tree harvesting) before additional mining, building, survival or optional TaCZ integration. There is **no** completed autonomous gameplay feature in the C0-B source.

## Development quality gate

Audit actual source → propose options and scope → maintainer approval → implement → Python and applicable Java/Forge tests → owner-run Windows and Minecraft testing → Full Repository ZIP. The maintainer handles GitHub commits/pushes; **No Test, No Push**.

See [C0 → C1 handoff](c0-c1-handoff.md), [architecture](architecture.md) and [long-term autonomy notes](future-autonomy-roadmap.md).
