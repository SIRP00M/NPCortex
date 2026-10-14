# NPCortex Roadmap — Baseline Reset

- [x] A2 — Backend concurrency, resource limits and Forge chat bridge stability.
- [x] B1 — World/Actor Identity and durable conversation history. User reports
  B1 HTTP/Qwen acceptance passed; Forge B1 integration/build is not separately evidenced.
- [x] B2 — **Implementation candidate, pending Windows/Qwen/Minecraft quality gate.**
  Explicit NPC↔NPC and Player↔NPC multi-actor shared transcripts, SQLite v2
  migration, speaker UUID, per-conversation serial turns, idempotent retry,
  turn limits and manual close. No autonomous initiation.
- [ ] B3 — Headless social simulation, recovery/performance/scale tests.
- [ ] After B quality gates — Forge NPC entity integration/skin/UI and game events.
- [ ] C — Full autonomous world simulation. **FUTURE ONLY**, not implemented or
  approved. See [C Roadmap](future-autonomy-roadmap.md).

No Test, No Push. User performs GitHub operations only.

## B3 candidate (Oct 2026): AI-Driven Headless Social Runtime

- Planner asks Qwen to select one allowed NPC speaker or to stop naturally.
- Owner advances each step explicitly via localhost API; no background world loop.
- Durable run/step journal, retry idempotency, short failure budget, SQLite v3 migration.
- Future C: world perception, goals, relationships, autonomy/world actions and Forge
  entity binding remain **ROADMAP ONLY**, not implemented or authorized in B3.
- B3 requires Windows/Qwen real-world acceptance and Forge regression results.
