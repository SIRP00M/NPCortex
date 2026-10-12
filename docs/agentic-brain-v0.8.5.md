# NPCortex v0.8.5-alpha — Agentic Brain (incremental oak-log pilot)

## Summary: what actually changed

This is an **incremental extension of v0.8.4**, NOT a rewritten mod. Forge 1.20.1 (47.4.10), Java 17, the old one-shot `/v1/agent`, the existing UI, SQLite memory, character packs, action IDs, ownership checks and the legacy GatherTaskController remain intact. The **new `/v1/brain/step`** API produces **one structured skill choice** after each server observation. This prototype routes *counted oak-log gather requests (1–16)* to it. Other counted resources continue to use the legacy collector; ordinary dialogue/actions continue to use the existing agent endpoint.

### Agent workflow

1. Server-owned task **goal**, baseline inventory count, budget and last result are stored in each NPC's persistent entity data (`NPCortexAgenticV085`). These are separate from SQLite chat memory and other NPC tasks.
2. `BrainObservation` returns up to 16 matching task blocks (ephemeral `b0`…`b15` refs) and up to 16 matching dropped items (`d0`…`d15` UUID-bound refs). Each observation includes target distance/reach, visibility, offsets and task counters. It does **not** let the model choose arbitrary world coordinates.
3. `ChatService.brain_step()` calls local **Ollama/Qwen 3 8B**, with the current character's personality and notes, a bounded tool JSON schema, fresh server observation and last authoritative action result.
4. The model chooses **one** tool: `approach`, `mine`, `clear_leaf`, `climb`, `descend`, `pickup`, `approach_drop`, `wait`, `finish` or `pause`. Java revalidates target refs, owner, item, loaded chunk, home radius, world-edit configuration, physics and server permissions. No Qwen code, command or coordinate is executed.
5. Deferred navigation/jump/cleanup must complete under Minecraft physics before feedback becomes a confirmed result. The server supplies updated observations to Qwen, which **can change its strategy**.
6. `finish` only succeeds if **at least N new requested items are in the NPC bag relative to its baseline** AND no task-owned scaffold remains. Mined blocks and AI-written prose are *not* completion evidence. Collected items remain with the NPC until a separate give/drop order.

### Safety boundary

| Qwen chooses | Java/Minecraft alone enforces |
|---|---|
| What to attempt next and why; changing approach after failure | Which target refs exist in THIS observation; ownership/dimension; safe standing locations; reach and line-of-sight |
| Whether to try a safe scaffold | Six-level height limit, trunk ownership, allowed materials, singleplayer+cheats+explicit opt-in; task-owned top-down cleanup |
| When to ask for finish | New bag quantity verified from original baseline; zero scaffold blocks |
| When to pause | Max 40 inference requests/task, 8 failed skills, 3 repeats of same failing tool/ref; 280 generated tokens/request, 45-second Ollama timeout, 4096 context tokens; 12000 ticks/task; 160-tick navigation timeout |

**Important limits:** only *oak logs* use the new loop in this release. `GatherTaskIntent` still parses quantity/resource intent at the edge, but **does not select the route, mine sequence, leaf-clearing or scaffold plan**. The existing legacy collector still controls other resources. The model's current `reason` is a short hypothesis and is never trusted as world state. No general free-form inventory transfers, arbitrary crafting, combat, cross-chunk travel, target re-identification, automatic replanning after exhausted budgets or globally optimized multi-goal planner is claimed for v0.8.5. New skill families can later use the same single-tool API and Java skill registry approach.

### Install and test on Windows

**Update-only ZIP**: keep your existing `F:\AI\NPCortex` repository. Extract the update ZIP to a separate temporary folder, then run its:

```powershell
.\scripts\install-agentic-v085.ps1 -UpdateZip 'C:\Downloads\NPCortex_v0.8.5-alpha_UpdateOnly.zip' -ProjectRoot 'F:\AI\NPCortex'
cd F:\AI\NPCortex
.\scripts\test-agentic-v085.ps1
.\scripts\build-agentic-v085.ps1
```

**Full ZIP**: extract it into a new local checkout/copy (or inspect against your existing repository). **Do not overwrite** `backend/data`, personal `.env`, saved worlds or custom character packs without backing up first. The ZIP contains repository source, **not** compiled binaries or SQLite data.

Existing launch scripts: `scripts/dev-backend.ps1`, `scripts/dev-forge.ps1`, `scripts/enable-world-edits.ps1`. Start Ollama with `qwen3:8b` already pulled; start the backend before opening the Minecraft world. The model remains fully local—no paid APIs.

### In-game acceptance checklist — manual, NOT yet executed

1. Back up the Minecraft save. In a **singleplayer world with cheats**, enable `npcortex` world edits with the existing script/config. Spawn Yui; verify the NPC has space for at least six oak logs and can safely reach an oak tree. If testing scaffold, pre-load safe blocks (dirt/cobblestone/stone/oak planks) into the **NPC bag**. Do not assume blocks are spawned for free.
2. Say **`Yui, chop 6 oak logs.`** or run `/npcortex task gather minecraft:oak_log 6`. Confirm `/npcortex task status` shows a new agentic task.
3. Verify each chosen action is server-authoritative. Introduce a hard-to-reach log or an occluding leaf; observe re-evaluation instead of a fixed rule sequence. Confirm failures increment budget; no teleporting, instant scaffold or unvalidated world edits.
4. Collect exactly six additional oak logs in Yui's bag (compared to task start). Check using `/npcortex inventory`; do not treat six broken blocks as success before pickups. Completion must not be reported while scaffold remains.
5. Use `/npcortex task cancel`, `/npcortex task resume`, disconnect/rejoin, and try another companion. Verify independent NPC progress; no memory resets; stale asynchronous decisions cannot execute after cancellation.
6. Test **world edits disabled, no cheats, lost owner, blocked path, insufficient materials, full bag and altered task-owned scaffold**. Fail safely/pause; never delete unexpected blocks. Then test a separate explicit give/drop order only after the gather task is no longer active.

### Test report at packaging time

- Python backend unit/API tests: **131 passed**, including existing compatibility tests and new structured-tool/feedback/API tests in `backend/tests/test_agentic_brain_v085.py`.
- Standalone pure-Java `AgenticTaskPolicy` self-test: **12 assertions passed** using `javac --release 17` under the available JDK 21. This is **not a Forge compile**.
- Forge Gradle build: **NOT RUN** here (project ZIP contains no Gradle wrapper, Forge dependencies or configured JDK 17 installation in this environment).
- Minecraft in-game tests: **NOT RUN** here. Agentic navigation, scaffold interaction and practical tree harvest require testing in Forge 1.20.1.

### Recovery / rollback

Use the update installer's timestamped `backups/pre-v085-...` folder to restore replaced source files. Stop the game before swapping the mod JAR. To return to v0.8.4 behavior, restore the original source/build and world backup; keep any separately made memory/data backup. Do not delete temporary scaffold blocks manually while an active NPC cleanup task has them recorded.
