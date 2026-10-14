# Runtime Sprint 1 — Pickup stand and Qwen pause diagnostics

This is a focused experimental hotfix layered on the OwnedDropBrainContractFix full repository.

## Changes

- The deterministic `TaskDropNavigator` now evaluates safe grounded stands using the
  *existing* CompanionItemPickup attraction range (2.25 block radius, with a conservative
  2.1 block stand acceptance margin), rather than requiring item contact from the stand.
  Line-of-sight and world-border/home-distance/solid floor/hazard checks remain intact.
  The NPC still must physically move and attract/collect each item into its bag.
- Save exact v2 skill `choices` sent to Qwen (`lastOffered`) for task debugging.
- When Qwen chooses `pause`, emit a `brain_pause` runtime event with its stated reason
  and options offered. This distinguishes a hard physical impasse from a premature
  model decision. The text is a model hypothesis, not verified world evidence.
- Clarify the Qwen policy that `loot_deferred` is not proof the remaining tree cannot
  be mined: defer the drop and continue the committed tree when safe skills exist.

## How to test

1. Close the game and backend, back up the prior install, unpack the full repository.
2. Restart backend and rebuild Forge (Java files **were** modified in this hotfix).
3. In Creative/test world with world-edits opt in and materials in NPC bag:
   `/npcortex spawn yui`; `/npcortex task gather minecraft:oak_log 6`.
4. Use `/npcortex task status`, `/npcortex task trace`, `/npcortex inventory`.
5. Observe whether previously deferred item stacks are physically collected.
6. If Qwen still pauses, inspect the `brain_pause` event: `reason=...; offered=...`.
   If offered has only `pause`, the authoritative runtime did not find an eligible
   action; investigate logs/scaffold materials/geometry and Navigation3D limitations.
   If offered contains `mine`, `climb`, `navigate_3d`, etc., the model opted to pause
   despite listed alternatives; investigate its decision/prompt.

## Not implemented

- No new climbing behavior or guaranteed access to floating canopy logs.
- No teleportation of items and no automatic collecting of unrelated player drops.
- No server guarantee that Qwen will never pause prematurely.
- ForgeGradle build and in-game acceptance are **not** verified here.
