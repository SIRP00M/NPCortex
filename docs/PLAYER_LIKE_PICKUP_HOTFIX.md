# NPCortex Runtime Sprint 1 — Player-like Auto Pickup hotfix (source patch)

This is an experimental source patch based on the attached v0.8.2-alpha Runtime Sprint 1 repository. It is **not a verified Minecraft release**.

## Behavior

- Each server-side CompanionEntity scans nearby ground ItemEntity stacks every two ticks.
- Within 2.25 blocks, visible items accelerate gently toward the NPC. At 1.5 blocks, they enter the NPC's nine-slot persistent bag automatically.
- Item type is unrestricted (logs, seeds, tools, mob drops, etc.), subject to slot capacity; no Qwen calls are required to physically collect.
- No teleportation, no pickup through solid walls, no duplicate stack copying; partial pickups leave the remainder on the ground.
- Dropped items honor vanilla pickup delay. NPC intentionally dropped/ejected items are marked so its passive pickup will never reabsorb those same ItemEntity stacks. Players can take them; explicit `/npcortex item pickup <id>` is an override.
- Existing world/task commands remain. A navigation-to-drop skill now treats an actual inventory delta from passive pickup as a successful physical interaction.
- Legacy gather task reconciles its ledger from the actual bag quantity.
- Does **not** add player-like 36-slot inventory, XP levels, armor equip logic or long-distance drop pathfinding.

## Test (fresh copy of a creative testing world)

1. Start backend: `./scripts/dev-backend.ps1`
2. Build: `./scripts/dev-forge.ps1 -Task build`
3. Run client: `./scripts/dev-forge.ps1 -Task runClient`
4. `/npcortex spawn yui` and `/npcortex inventory`
5. Throw an oak log or emerald ~1-2 blocks near Yui; after pickup delay it should be pulled into Yui's bag with an item pickup sound and disappear from ground.
6. `/npcortex inventory` must reflect it; item must not appear in player's inventory.
7. `/npcortex item drop minecraft:oak_log` must leave it on the ground (NPC will not auto-recollect deliberate drops).
8. With a small Oak tree nearby: `/npcortex task gather minecraft:oak_log 6` then `task status`/`task trace`. Passive pickup should not consume Qwen calls.
9. Fill all nine NPC bag slots and confirm additional item entities stay on the ground.
10. Verify no pickup through solid walls and that partial stack capacity leaves the correct ground remainder.

## Limitations / build status

Run `python scripts/test-runtime.py` for backend + Java core + Java syntax checks. Full ForgeGradle compile and in-game behavior must still be verified on JDK 17 and Minecraft 1.20.1 Forge.
