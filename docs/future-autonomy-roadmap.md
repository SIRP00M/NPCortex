# NPCortex — Future C Extension Roadmap (design only)

**Current scope:** B — Multi-NPC Social Runtime (B1, then B2, then B3).
**Not authorized in B1:** C — Full Autonomous World Simulation.

| Future C capability | B foundation that can support it | Status |
|---|---|---|
| Autonomous goals & personal agendas | Actor UUID and persistent identity | PLANNED — no goal engine |
| Dynamic social relationships | Stable actor references & conversations | PLANNED — no relationship scoring |
| World perception / spatial truth | Namespaced world and dimension identity | PLANNED — no sensors/events |
| AI planning & action execution | Provider boundary and service separation | PLANNED — no action API |
| Daily routines / background thoughts | Actor lifecycle boundaries | PLANNED — no autonomous loop |
| NPC-to-NPC autonomous social initiation | Future B2 dialogue orchestrator | BLOCKED by B2/B3 |
| Scalable population policies | A2 capacity bounds; future B scheduler | BLOCKED by performance tests |
| Forge locomotion and world interactions | Future Minecraft entity adapter | DEFERRED |

Do not add stub classes or fake implemented skills solely for future-proofing.
In B1, only `WorldStore` + `ChatService` extensions required for stable identity,
persistence and a simple player-to-actor transcript exist. No C code is enabled.
Any scope change requires a new repository audit, options and user approval.
