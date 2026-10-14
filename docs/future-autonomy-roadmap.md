# NPCortex — Future C Extension Roadmap (design only)

**Current scope:** B — Multi-NPC Social Runtime (B1, then B2, then B3).
**Not authorized in B1/B2/B3:** C — Full Autonomous World Simulation.

| Future C capability | B foundation that can support it | Status |
|---|---|---|
| Autonomous goals & personal agendas | Actor UUID and persistent identity | PLANNED — no goal engine |
| Dynamic social relationships | Stable actor references & conversations | PLANNED — no relationship scoring |
| World perception / spatial truth | Namespaced world and dimension identity | PLANNED — no sensors/events |
| AI planning & action execution | Provider boundary and service separation | PLANNED — no action API |
| Daily routines / background thoughts | Actor lifecycle boundaries | PLANNED — no autonomous loop |
| NPC-to-NPC autonomous social initiation | B2 dialogue + B3 step-driven AI-selected speaker | PLANNED for C: no background self-initiation |
| Scalable population policies | A2 capacity bounds; future B scheduler | BLOCKED by performance tests |
| Forge locomotion and world interactions | Future Minecraft entity adapter | DEFERRED |

Do not add stub classes or fake implemented skills solely for future-proofing.
In B1/B2, identity, persistent transcripts and explicit dialogue turns exist;
there is NO background world loop, goal planner, social self-initiation,
relationship updater or world-action adapter. No C code is enabled.
Any scope change requires a new repository audit, options and user approval.

### B3 foundation boundary

B3 implements AI-assisted choice of speaker and model-driven stop **only when a
trusted localhost caller explicitly advances a headless social step**. The
persisted `social_runs`/`social_steps` journal provides a future orchestration
boundary, but there is NO background timer, no sensor feed, no game event
reader, no autonomous goals, no social relationship modeling, and no game
execution. Do not mistake B3 headless AI turn choice for C world autonomy.
