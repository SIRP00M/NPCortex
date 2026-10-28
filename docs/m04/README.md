# M04 D-Lite + E — Gate 0–2 Foundation Candidate

**Status: PARTIAL IMPLEMENTATION, NOT READY FOR WORLD MUTATION.**

This candidate is derived from the last full source ZIP available in the conversation,
`NPCortex_M03_AllTests_FunctionLoadFix_FullRepository_CANDIDATE.zip`, plus the
M03 Live Evidence watcher overlay. The Codex 2026-10-26 source audit described
an evolving *local* workspace not mounted here. Do not claim that the generated
ZIP replaces newer local edits in `F:\AI\NPCortex` without a merge review.

## Implemented in this candidate

- A small typed sequential M04 contract in Java and Python, including exact
  world/actor/session/task/attempt correlation, immutable state/witness order,
  HOLD for partial/unknown effects, and metadata-only retry (NO world re-exec).
  These are pure contracts, not a persistent coordinator or permission grant.
- C1 `unequip` to an explicitly empty storage slot, preserving exact item NBT,
  rejecting full/nonempty destination, and idempotent UUID replay through the
  existing capped C1 journal. Equipment position can be `mainhand`, `offhand`,
  `head`, `chest`, `legs`, `feet`. For `mainhand` the selected hotbar slot is
  cleared while the unequipped stack is transferred to a distinct empty slot.
- Separation of inventory commit and viewer sync exception handling. Once
  SavedData commit succeeds, a viewer sync error can no longer roll back the
  authoritative live inventory; it is reported as a sync failure and pending
  world save. A hard crash before normal world save is STILL an unverified case.
- Read-only `/npcortex m04 status` and `/npcortex m04 preview <name> <x> <y> <z>`.
  The preview uses the NPC's eye position (not the operator's), loaded chunk,
  dimension, a conservative reach/LOS check, current block and local movement
  locks. Permission and backend accepted claim remain UNKNOWN/UNVERIFIED and
  the command ALWAYS reports HOLD for mutation. No force loading.
- Python contract tests and pure-Java contract self-tests, Windows runner.
- M03 historical records, transport code and watcher preserved; no SQLite
  data or worlds are included, overwritten or reset.

## Deferred / blockers

- **Gate 0:** The M03 command provenance mismatch from the 2026-10-26 Codex
  audit is not certified fixed. Checking that `CommandSourceStack.getPlayer()`
  resolves to a live OP does NOT by itself distinguish direct player chat
  from commands executed using `execute as` or via a datapack/command block.
  M04 mutating commands are not registered until authenticated origin is proven.
  No shared backend actor/target durable lease has been implemented.
- **Gate 1:** The read-only F2 mutation permission policy stays UNKNOWN.
  Preview is not an ALLOW decision; protection integrations remain unverified.
- **Gate 2:** C1 unequip and commit/sync changes require an actual Windows Forge
  compile and in-game inventory equip/unequip/save-rejoin regression. No C+
  parent action is allowed to call them autonomously in this candidate.
- **Gates 3–6:** Native timed mining, cancel quiescence, genuine pickup,
  persistent effect journal, backend claim/terminal storage and end-to-end
  world gameplay are NOT implemented. Absolutely no mutation attempt is
  exposed. Cancelling delayed native block destruction remains unproven.
- M03 has deferred tests 6B–10 and an unresolved C2_ACCEPTED/G3-terminal gap.
  Do not release old actor fences or change their historic records.

## Windows instructions

1. Merge-review the files in this ZIP against the current local working tree;
   never overwrite a newer local repo or world/config/SQLite directory blindly.
2. Use Temurin JDK17 on Windows and run `scripts/test-m04.ps1` from repo root.
3. Compile Forge and run backend regression suites; actual Forge compile is
   PENDING for this candidate because Gradle 8.8 was unreachable in authoring.
4. Only in an isolated test world with an eligible NPC and OP player, test:
   `/npcortex m04 status`
   `/npcortex m04 preview testnpc ~ ~ ~` (relative coordinates resolve from
   operator's command source; the checks measure from NPC and won't allow
   mutation even if reachable). For absolute target coordinates use the target
   block coordinates. Preview must report HOLD for mutation.
5. With a disposable inventory fixture containing an item in NPC equipment and
   an empty storage slot, run:
   `/npcortex c1 unequip testnpc offhand 5 <unique-uuid>`
   then `/npcortex c1 inventory testnpc` and `/npcortex c1 equipment testnpc`.
   Repeat the same UUID: should report REPLAY and not duplicate items.
   Try a different operation with the same UUID: should report CONFLICT.
   Check normal Save/Quit/Rejoin. Use a copy of the world.

Do not exercise `clear` to simulate unequip and do not give or remove test items
as an alleged live-world action result. Existing C1 fixture `give`/`copyhand`
commands remain test-only utilities, not M04 autonomous skills.

## Next acceptance gates

**Gate 0** safe command provenance, shared actor/target claims and preserved
old G2 HOLD rows -> **Gate 1** concrete supported permission profile -> **Gate 2**
real equip/unequip/persistence acceptance -> **Gate 3** native timed mining and
provable quiescence -> **Gate 4** physical pickup and inventory custody -> **Gate 5**
forced effect intent and effect-aware durable reconciliation -> **Gate 6**
end-to-end Gameplay in a copied world. If any prerequisite is HOLD, downstream
mutating steps must remain disabled. Do not claim M04 is complete.
