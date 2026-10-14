# Owned mined-drop retrieval (experimental)

## What changed

- Mining through `WorldBlockActions.mine/mineAt` now wraps the vanilla `ServerLevel.destroyBlock` call with a server-thread-scoped origin. Newly spawned `ItemEntity` objects from that break are marked with NPC UUID, current Agentic task UUID (when present), and source block. Existing items on the floor are **not** retroactively claimed.
- `TaskDropNavigator` moves the NPC to collision-safe **grounded** standing positions near its own active-task drops, within an actual physical pickup radius. It uses the vanilla pathfinder, then `CompanionItemPickup` attracts/stores the item as before. Qwen no longer needs to select `approach_drop` for its own mined task drops.
- Pickup is verified by **both** a positive NPC-bag delta and evidence that the tracked drop stack shrank/disappeared. Task state and events survive save/reload.
- When no physical ground path exists, navigation is bounded and the drop is deferred for 200 ticks; the AI can continue work. No infinite movement, teleport, flight, forced chunk load or automatic placing/destroying blocks.
- Items deliberately dropped by the NPC are not tracked as mining returns, and remain excluded from passive reacquisition. Existing unrelated items are still passively picked up when close, like the previous build.

## Important limitations

- Only **new** block-break drops made after installing this patch can be associated with the NPC and the task. Drops created in an older build are not retroactively tagged. Minecraft item-stack merging can also obscure the tag on some stacks.
- The retrieval path is grounded. Drops trapped in an elevated canopy, on inaccessible ledges, or behind a wall may remain unreachable and must be handled by a future generalized `Navigation3D` item-target mode. This patch does **not** fix `BLOCKED: 3D primitive timed out` during tree climbing, or replace existing tree-mining navigation.
- Task verification still expects collected logs >= mined logs. If a player collects the NPC's mined drops or they despawn, the task may need explicit user assistance.
- Model / ForgeGradle compilation and in-game results have not been validated by automated syntax checks alone. Run `scripts/test-runtime.py`, then `scripts/dev-forge.ps1 -Task build` and `-Task runClient` on an actual Forge dev environment.

## In-game manual checks

1. Start a **new task** with Yui 2-4 blocks from a small oak (newly broken items will be tagged): `/npcortex task gather minecraft:oak_log 6`.
2. Observe mine/drop: NPC should approach the nearest safe stand for its own mined logs, then attract/store them without issuing separate Qwen pickup calls. Run `/npcortex task trace` for `loot_started`, `loot_collected` and/or `loot_deferred`.
3. Run `/npcortex task status` to see `ownDropsNearby`; monitor `stored` until it catches up with `mined` (after full tree completion).
4. Test behind a 1-block step, nearby cliff, and unreachable raised ledge in separate worlds. It must never teleport, clip through walls, or run infinitely when an item cannot be reached.
5. Test NPC deliberately dropping an item. It must not immediately reacquire that same item.
6. Test an item dropped by the player nearby. Passive local auto pickup continues, but the task-ground-navigation subsystem must **not** initiate travel toward that player's distant drop.
7. `task cancel`/`task resume` must leave the task inventory intact; cancel any unfinished test before the next test.
