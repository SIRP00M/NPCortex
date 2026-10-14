## October 14, 2026 — Universal Access Sprint 2 Fix 9 (Harvest Dead-End Recovery)

- **Live Fix 8 observation:** `stored=7/6; mined=19; treeRemaining=4; accessBlocks=10; usableBlocks=7; search=NO_PATH; expanded=3`. Goal ledger correctly kept HARVEST active, but `descend` was unavailable until `treeRemaining=0`, so no safe access-reset option could be offered.
- Offer `descend` DURING HARVEST only when an actual committed-log `navigate_3d` search has failed (`NO_PATH`, `SEARCH_BUDGET`, or `INVALID_START`), task-owned access exists, and fewer than two retreats have occurred since the last verified mined log. **Qwen chooses whether to descend**; Forge's existing inverse journal strictly enforces ownership and physical reach. The committed tree is retained throughout recovery.
- After the second retreat without a newly mined task log, preserve the task and pause rather than building/dismantling in a loop. A successful verified mined log resets the retreat counter.
- Immediately suppress deterministic failed `navigate_3d` attempts for the unchanged pose/world/inventory fingerprint; other logs, different geometry, and alternate skills remain eligible. Transient failures still allow retries.
- **Fix8 resume compatibility:** an existing paused task with a recorded failed `navigate_3d(...)` search can recover the dead-end marker when `/npcortex task resume yui` is used after updating, instead of requiring a fresh tree. Task progress and mutation journal are preserved.
- Improve failed 3D route diagnostics with `search`, `expanded` and available material count. Add `harvestNavBlocked` and `retreatsWithoutMine` to task status.
- Update Qwen runtime guidance to distinguish strategic mid-harvest descent from post-harvest cleanup, and add Backend/Java regression checks. This is a development snapshot; ForgeGradle and actual Tall Oak acceptance are not verified by syntax/simulation tests.

## Sprint 2 Fix 6 — AI Recovery Guidance (Development Snapshot)

- Updated only the **runtime-v3 Qwen brain prompt** to recognize a valid recovery strategy: when a committed tree has no remaining logs and temporary access exists, `descend` may be more useful than repeatedly planning bridges to difficult drops. Cleanup returns borrowed log blocks to the NPC bag and may bring the NPC to a position where physical pickup is possible.
- This is guidance, **not hard-coded task ordering**; Qwen still chooses among server-offered Skills, and Forge verifies all moves, inventory changes and restoration.
- Added a backend regression test for this prompt/contract alignment. No Java code or Forge build changes. No guarantee that every suspended pickup route becomes traversable; preserve task trace for subsequent Navigation3D work.

## October 14, 2026 — Universal Access Sprint 2 Fix 5 (Drop Route Recovery)

- **Live test symptom:** after harvesting part of a Tall Oak, Yui repeatedly selected `navigate_3d_drop:d0`, received `no safe 3D pickup route found within limits`, and paused before attempting a different route or the remaining logs.
- Failed **bounded drop-route searches** are now remembered as exhausted immediately for the *current physical fingerprint*. The same drop remains eligible after actual movement, a material/inventory change or terrain change; unrelated targets and skills stay available.
- A failed physical executor attempt consumes the existing failure budget, not the separate no-progress budget too; this preserves a replanning opportunity while retaining the caps on failures, stalled no-ops and total calls. Successful but ineffective actions still increment the no-progress watchdog.
- Qwen's compact runtime-v3 guidance now explicitly considers remaining logs and alternate motion before retrying an inaccessible drop. **Qwen still selects skills; Java never teleports items into the bag or silently harvests a tree.**
- Failed drop searches now expose `accessSearch` (`INVALID_START_CONDITION`, `INVALID_START`, `NO_PATH`, or `SEARCH_BUDGET`) and expanded nodes in the task result; `trace` identifies `Runtime Sprint2 Fix5`. Added Java regressions for immediate impossible-route suppression, unchanged-vs-changed-world fingerprints, transient-route retry and watchdog accounting. The exact tall-tree Minecraft scenario and safe drop-descent solution are still **not verified** and may need further navigation work.
- Development snapshot only, not a public release bump.

## October 14, 2026 — Universal Access Sprint 2 Fix 4 (Frontier Validation)

- **Root cause of live HTTP 400 before Qwen:** Java `Navigation3DAccess.terrainRoutes()` offers walking frontiers with X/Z offsets up to ±12, but the Python Runtime v3 validator previously allowed only ±8. A reachable plains waypoint 9–12 blocks out was rejected as `Invalid task counter` before Qwen could pick `mine` or any other skill.
- Python Runtime v3 now accepts X/Z frontier offsets within ±12, while still rejecting offsets outside the *actual* Java navigation-search radius, invalid types, extra keys, and unsafe data.
- Counter validation errors now name the rejected field and expected numeric range (e.g. `Invalid frontiers[0].dx: expected integer in [-12, 12]`) to make the next issue easier to diagnose.
- Added first-decision, radius-bound, and Java/Python contract regression tests; **no Forge gameplay or Qwen skill logic was changed**. Restarting the **Backend** is required. Existing paused work may be resumed; a fresh `/npcortex task gather minecraft:oak_log 6 yui` after cancel gives a clean test.
- Backend regression tests pass; Forge build and successful whole Tall Oak harvesting remain **unverified in-game**.

# Universal Access Sprint 2 — experimental

Development snapshot only; not a public release bump or a complete survival-agent implementation. Update the matching Backend and Forge sources together; Forge build and in-game acceptance are still required.

## Sprint 2 Fix 2 — Reachable Exploration Frontiers (experimental)

- Navigation3D now uses collision-based walking checks so short grass and flowers without collision no longer block traversable terrain. Hazard and fluid checks remain in effect.
- The exploration skill offers only frontier waypoints connected by a traversable terrain route, and revalidates the route when executing. Each search segment is bounded for safety; there is no global 12-block travel limit.
- Walking clearance and block placement are checked separately; temporary pillars and bridges are placed only in valid air cells to avoid undocumented terrain changes.
- Qwen's tool-selection guidance now favors actionable `mine:bN` options before unnecessary exploration, while Qwen still selects the skill.
- Added regression coverage for route connectivity and hazards. Trace marker: `Runtime Sprint2 Fix2`. Backend and Java tests passed for this snapshot, but Forge gameplay behavior has not yet been validated.


## Runtime Sprint 1 — Safe Retreat / Replan hotfix (experimental)

If Qwen requests pause when only safe descent is available atop a task-owned
pillar/bridge, server recovery dismantles the recorded scaffold and replans.
Two unproductive retreat cycles still pause safely, avoiding endless loops.
Resuming while on legacy scaffold now initiates guarded cleanup. See
`docs/runtime-sprint1/SAFE_RETREAT_HOTFIX.md`. No Forge/in-game acceptance yet.

---

## Runtime Sprint 1 — Player-like Auto Pickup (experimental source hotfix)

The NPC now collects nearby item entities automatically (2.25-block visible attraction,
1.5-block pickup, server-side, no Qwen call). Owner-directed drops are excluded
from re-collection; legacy Gather and Agentic navigation-to-drop handle passive bag
changes. Read `docs/PLAYER_LIKE_PICKUP_HOTFIX.md`. No Forge/in-game acceptance yet.

---

# Architecture Stabilization Sprint 1 (development snapshot)

See [implementation notes](docs/runtime-sprint1/README_TH.md) and [Minecraft acceptance checklist](docs/runtime-sprint1/FANCY_OAK_ACCEPTANCE.md). Update backend and mod together. This is not a new published release.

**Vanilla Trees Foundation 1 (experimental):** See `docs/releases/v0.8.2-alpha-general-trees-foundation1.md`. Adds species/branch/2x2 tree recognition groundwork; does not certify universal tree navigation or Nether fungi.

## v0.8.2-alpha — Agent Recovery Fix 2 (leaf affordance validation)

Continues from AgentRecoveryFix1. Java now classifies actionable leaf obstruction
only on the NPC's current sight ray; backend provides Qwen a per-skill list of
allowed observed targets and rejects invalid tool/target combinations.
No new hardcoded task plan. No Forge build or in-game validation yet.
Details: `docs/releases/v0.8.2-alpha-leaf-recovery-fix2.md`.

---

# NPCortex v0.8.2-alpha — Autonomous World Interaction & Whole-Tree Harvesting

**Upgrade base:** public v0.8.1-alpha, not the historical v0.8.5.x
internal engineering snapshot numbers. Minecraft 1.20.1 / Forge 47.4.10 / Java 17.

## Highlights

- Qwen chooses grounded skills while Java owns tree detection, navigation,
  world-edit permissions, physics, watchdogs, inventory and final verification.
- Commit to one conservatively recognized natural Oak tree at first successful
  mine; harvest its known logs even if the player's minimum quantity is met.
- Continue with another natural tree only if the minimum remains unmet.
- Auto-collect nearby matching mined logs into NPC bag, without a model call.
- Add reversible, maximum-two dirt/grass obstruction clearing skill and cleanup;
  retain guarded natural oak leaf clearing and temporary scaffold support.
- Preserve existing SQLite, Character Packs, GUI, existing actions and owner permissions.
- Keep no-progress, model request, failure, timeout and task cancellation limits.

## Limitations

- Live Forge build and Minecraft world test have NOT been run in this packaging environment.
- Pathfinding and line-of-sight in dense forests are still experimental.
- Tree identification is conservative, not a guarantee of natural provenance.
- Inventory matching uses item ID and count within radius; it is not a
  provenance tracking system for individual drop entity ownership.
- Standalone manually placed logs no longer qualify as a complete natural tree
  for the new pilot task; old world-action commands remain available.
- Budget exhaustion or unsafe cleanup leaves the task paused, NOT complete.

## Upgrade

Back up the world and memory. Run `scripts/install-v082.ps1` from the extracted
Update Only ZIP with `-UpdateZip` and `-ProjectRoot`. Then run
`scripts/test-v082.ps1`, `scripts/build-agentic-v085.ps1 -SkipTests`,
`scripts/dev-backend.ps1`, and `scripts/dev-forge.ps1 -Task runClient`.

Details: `docs/releases/v0.8.2-alpha.md`.

---

## Historical notes (v0.8.1-alpha)

# NPCortex v0.8.1-alpha — Agentic AI Brain (Public Milestone)

The public version of the current development snapshot is **0.8.1-alpha**, based on the existing `SIRP00M/NPCortex` GitHub repository (previously 0.8.0-alpha).

The engineering snapshots named v0.8.4 through v0.8.5.5 contributed bounded Qwen skill selection, server-validated execution, a task ledger, watchdogs, navigation improvements and inventory-verified oak-log collection. Those historical development document names were retained to avoid breaking references.

## What has been observed in Minecraft

- One accessible `minecraft:oak_log` mined and its drop verified as **1/1 stored in NPC bag**.
- When leaves/terrain obstructed approach, some tasks paused with no safe visible standing position.
- After manual foliage removal, the nearby 1-log task completed again.
- Full tree harvesting, reliable obstacle clearing, arbitrary autonomous jobs, automatic player-like magnet pickup, and robust long-distance navigation remain unverified / incomplete.

## Source & safety

- Model: Qwen 3 8B via local Ollama, structured skill calls; Minecraft server retains ultimate authority.
- Retains Character Packs, existing action handlers, ownership validation, SQLite memory and inventory data.
- No Java or arbitrary Minecraft commands from AI; request, failure and no-progress budgets remain bounded.
- Changes in this package are source/metadata/README updates, **not** a new claim of running Forge in this packaging environment.

Technical notes: `docs/agentic-brain-v0.8.5.5.md` and `docs/releases/v0.8.1-alpha.md`.

## Vanilla Trees Foundation 1 — Build Fix 1

- Fixed `WholeTreeTracker.verifiedRoot()` Java compiler generic type inference on the root comparator.
- No intended gameplay/AI behavior changes. Forge build and in-game validation still required.

## Experimental patch: owned mined-drop retrieval

- `CompanionMinedDrops`: mark newly spawned block-break drops with NPC/task identity without claiming unrelated dropped items.
- `TaskDropNavigator`: bounded deterministic server movement to grounded pickup positions for its own current-task drops. Qwen decisions remain responsible for mining/tree access, not pickup reflexes.
- `BrainObservation`: `npc_mined` provenance flag for drops; Task status adds `ownDropsNearby`.
- See `docs/OWN_MINED_DROP_RECOVERY.md` for test steps and limitations. No claim of in-game verification or a fix for Navigation3D climbing timeouts.

## Runtime Sprint 1 – Owned Drop Brain Contract Hotfix

- Fixed backend HTTP 400 `Unexpected drop observation fields` after Forge began sending
  `npc_mined` in mined-drop observations. This rejection happened before Qwen.
- Backend now accepts `npc_mined` only as an optional boolean while continuing to
  reject any unknown drop fields. Legacy observations remain compatible.
- Added validation and HTTP endpoint regression tests for both success and rejection.
- **Backend restart required** after replacing files; no Forge Java changes in
  this hotfix. See `docs/runtime-sprint1/OWNED_DROP_BRAIN_CONTRACT_HOTFIX.md`.
- No change to 3D navigation/climbing behavior; in-game acceptance is still required.

## Runtime Sprint 1 — Pickup stand / Qwen pause diagnostics hotfix
- Extend owned-drop grounded stand checks to match physical 2.25-block pickup attraction (conservative 2.1-block stand acceptance radius), retaining obstacle/LOS/safety tests.
- Persist offered v2 skills, emit `brain_pause` with the Qwen reason and actual offered choices, and display offered choices in task status.
- Clarify to Qwen that `loot_deferred` is a temporary ground-path limitation and does not imply all remaining tree work is impossible.
- See `docs/runtime-sprint1/PICKUP_NAV_PAUSE_DIAGNOSTICS.md`. This does not resolve the full Navigation3D high-canopy access problem.

## October 13, 2026 — Experimental General Skills (combined with Safe Retreat)

New shared modules: `BuildMaterials`, `MaterialSource`; new AI skill choices: `approach_material:mN`, `mine_material:mN`, `navigate_3d_drop:dN`. Compatible strict Backend observation validation extended for bounded material targets. Original-tree completion continues to require all committed logs removed and collected, plus temporary-build cleanup.


### Development patch — Sprint 2 Fix 3 (Brain Decision Diagnostics)

- Runtime v3 selects a compact, still model-directed tool policy instead of appending old v1 notes to every Qwen call.
- Runtime v3 no longer duplicates candidate references in the prompt; the server-validated catalog and enum remain the authority.
- Ollama brain calls use an 8192-token context with a 320-token response budget (chat defaults remain unchanged).
- Forge now distinguishes backend 400 observation rejections, 502 Ollama/model failures, timeouts and connection errors in task status/trace.
- **Not yet an in-game verification of successful Tall Oak harvesting.** Check the backend terminal for the exact error if another pause occurs.


### Development patch — Sprint 2 Fix 7 (Drop Elevation Perception)

- Forge observes `drops[].dy` as the signed height difference between each observed item entity and the NPC's feet; negative means below. It is *observation only*, never a movement coordinate or permission to teleport.
- Python backend accepts and validates bounded integer `dy` while preserving backward compatibility with previous Forge observations. Invalid or extra data still fails closed.
- Qwen Runtime v3 guidance uses relative drop height plus recorded temporary blocks to weigh a safe `descend` before revisiting ground drops; the model still selects the skill. No forced skill order.
- Existing `descend` remains a recorded, reversible scaffold/path cleanup operation, not proof of reaching natural ground or collecting the drops. Verify in Minecraft, including return to surface and inventory restoration.
- Requires **both backend restart and Forge rebuild**, as the observation contract changed on both sides. Experimental snapshot, not a public release.

### Development patch — Sprint 2 Fix 8 (Goal Completion & Intelligent Recovery)

- Server-side `GoalCompletionLedger` distinguishes HARVEST, RECLAIM_ACCESS,
  RECOVER_DROPS, FIND_TREE, VERIFY_TREE and READY_TO_FINISH. The authoritative
  completion rule **still requires all mined logs accounted for in the NPC bag,
  every committed tree verified and all temporary path/terrain edits restored**.
- New bounded `recover_worksite` skill: Qwen can request connected, grounded
  walk segments toward the verified root of the last committed tree. It is not
  teleportation, wide-area chunk searching or automatic item granting.
- When the tree is cleared and a log is missing, Forge stops advertising
  unrelated resource excavation and random exploration. Qwen sees the exact
  outstanding mined-log count in `task_context.goal_completion`.
- Temporary-support cleanup is now offered even if a finished small tree
  yielded fewer logs than the overall requested minimum. Cleanup **does not**
  falsely finish the mission.
- A bounded (18 no-goal-progress AI choices) recovery watchdog pauses honestly
  with the deficit rather than looping indefinitely. Existing saved tasks
  with `wholeTreeRoot*` can reuse that original root as the recovery anchor.
- Backend strictly validates the new completion evidence, still supports older
  runtime-v3 contexts and uses a compact v3 Qwen prompt.
- Tests: Python backend, pure Java ledger, runtime/simulation and Java 17 syntax;
  **ForgeGradle build and physical Minecraft behavior still require local validation.**
