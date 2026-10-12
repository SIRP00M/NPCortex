# v0.8.4-alpha — Locked Oak Tree & Temporary Pillar

This is an experimental *server-authoritative* follow-up to v0.8.3-alpha.

## How it works

- Dialogue: `Yui, chop 6 oak logs.`
- Gathering locks the first selected **straight 1x1 oak trunk** by X/Z, saved in the existing entity NBT task. The controller only chooses another tree once the locked trunk column has no logs remaining, or the requested item count has been reached.
- It counts cut logs whose drops have not yet been picked up separately from inventory-confirmed collection, so it cannot claim six collected after only one cut. It cuts visible logs, navigates to safe standing points, and clears up to eight *naturally generated* oak leaves if needed.
- If upper logs are out of reach, it chooses an adjacent **ground-supported pillar location** and jumps, placing one full solid block underneath its feet at the jump apex. It may use dirt, stone, cobblestone, oak planks or oak logs already in **the NPC's own nine-slot bag**. There is no creative spawning or teleporting.
- Height is limited to **six scaffold blocks**. Logs beyond this limit, unsafe terrain, blocked jumps and exhausted materials **pause the task** with a reason. It does not switch trees to hide an inaccessible remaining log.
- NPCs avoid abandoning a pillar just to collect logs that fell to the ground. After clearing the upper trunk they dismantle their scaffold and collect those drops at ground level. The pillar blocks are recorded in the NPC task's persistent NBT as exact positions and item IDs. On task completion, cancellation, or before choosing another tree, the NPC breaks its own pillar **from the top down** while descending one block at a time. Materials go back into its bag, or drop nearby if full. If a recorded block was changed, cleanup pauses without destroying it.
- `collect` / `chop` keeps gathered items in the NPC bag; `give me all the oak logs` remains a **separate** request.

## How to test

1. Use a backed-up singleplayer Creative test world with cheats and `enable_world_edits = true`.
2. Give Yui at least 6 dirt or cobblestone blocks in her **NPC bag** via the in-game inventory UI, or let her collect an oak log that may be used as a pillar material.
3. Spawn two separate oak trees, about four blocks apart. Stand near Yui.
4. `Yui, chop 6 oak logs.` Observe her stay at the same tree until its straight trunk is exhausted, rising using a pillar if required.
5. `/npcortex task status yui` shows progress and any pause reason.
6. `/npcortex task cancel yui` should trigger safe dismantling, if a pillar was placed. Check that temporary blocks have been recovered and natural terrain remains.
7. `Yui, give me all the oak logs.` delivers the bag contents only when asked.

## Scope and known limitations

- Supports **straight 1x1 oak trunks only**; not 2x2 trunks, branches, giant oaks, trees with bent logs, or modded wood yet.
- The quantity goal still takes priority: requesting six logs means **stop at six**, even if an unusually large tree contains more logs. In that case she does not move to another tree.
- No digging or scaffolding into unsupported air; no liquid, falling blocks (sand/gravel), arbitrary item or TNT scaffolds.
- Unsafe/blocked/missing scaffold materials pause instead of faking success.
- Cleanup waits for the NPC to stand correctly atop the pillar. If the NPC is moved or a placed block replaced, manual intervention may be needed. A replaced block of *identical type at the identical location* cannot be distinguished from the original without block ownership metadata.
- The Minecraft jump/placement/descent sequence **requires live Forge testing**; pure Java policy tests cannot validate entity physics/pathfinding.
