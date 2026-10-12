# NPCortex v0.8.5.4-alpha — No-progress watchdog

## Evidence from first real gameplay run

- `/v1/brain/step` HTTP 200 repeated: Ollama connection and schema validation working.
- Qwen repeatedly chose `approach(b0)` while target was already visible and reachable.
- Java returned `Action confirmed: block is already visible and in reach`, then cleared failure streak, despite no change to world or NPC position.
- Task exhausted 40/40 inference budget with mined=0, collected=0, failures=0.

## Repair

- New server-owned `stalls` counter, capped at **5** per task; any successful, verified state-changing skill (mine, pickup, clear_leaf, climb, dismantle) resets it.
- Navigation counts as progress only when the NPC moved at least 0.3 blocks (positions read from Minecraft on server thread).
- Already-reachable `approach` and `approach_drop` now return `Action unchanged` rather than confirmed world progress.
- A `wait` step does not falsely imply task progress. Failure and stall budgets are independent.
- The current stalled count and previous result are sent as world observation. Qwen remains free to select the next tool; no `approach -> mine` rule is added.
- Budget enforcement is in Java; Qwen cannot override the task completion or recovery budget.
- `stalls=.../5` appears in `/npcortex task status`. Pausing on a stall cap preserves bag/task; to restart after the budget is exhausted, cancel then issue a fresh task.

## Compatibility and installation

Apply after **v0.8.5.3-alpha** on the existing project, with Minecraft client and Backend closed. Use `scripts/install-v0854.ps1 -UpdateZip <path> -ProjectRoot F:\AI\NPCortex` from the extracted update directory. Script verifies the ZIP whitelist and version markers, backs up replaced files, and never touches saves, `.env`, Character Packs or SQLite.

**Both Forge and backend changed.** Run `scripts/test-agentic-v085.ps1`, then `scripts/build-agentic-v085.ps1` (requires local Forge dependencies and JDK 17). Run Backend and `scripts/dev-forge.ps1 -Task runClient`. In-game: cancel the previous paused task, start with one oak log, inspect `chosen`, `last`, `stalls`, `stored` and `mined`.

Optional read-only Qwen probes: `scripts/diagnose-brain-v0853.ps1` and `scripts/diagnose-brain-v0854.ps1`. A probe is only a **single** model decision; it cannot demonstrate real in-world mining, movement or pickup.

## Explicit test limits

- Python Unit/API tests and standalone pure-Java policy tests can run without Minecraft.
- These tests **do not compile Forge-linked Java** (e.g. `AgenticTaskController`) or verify pathfinding.
- Forge Build and In-game Tests require a real Forge dev environment. Do not claim they passed until they actually do.

## Known limitations

This is a watchdog, not a guarantee Qwen selects good tools. If Qwen keeps proposing no-op skills, the server will now pause early, not automatically choose `mine`. If it selects `mine` but execution rejects a block, inspect Java/Forge and backend logs. Low-level pathfinding and item pickup still require game testing.
