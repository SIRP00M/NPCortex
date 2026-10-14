# NPCortex Runtime Sprint 1 — Safe Retreat / Replan hotfix

## Observed problem

With Dirt in Yui's bag, `climb` built a temporary root-anchored pillar and
`mine` harvested more upper logs. The next Qwen observation offered
`pause,descend` while committed tree logs remained; Qwen selected `pause`,
stranding Yui on the scaffold. This is not proof the tree was harvested.

## Scope

- The server keeps Qwen's original `brain_pause` reason and offered choices.
- If a **temporary task-owned structure** exists, the brain chose `pause`, and
  no productive skills are offered except `descend` (with optional `wait` and
  `restore`), the server dispatches the already-registered safe `descend`
  executor. This is safety recovery, not arbitrary scripted harvesting.
- On successful dismantling, the runtime re-observes from the ground; ordinary
  Qwen tool selection resumes. Each newly **verified mined log** resets the
  counter of unproductive retreat cycles.
- After two retreats with *no new verified log between them*, descend first,
  then pause on the ground for owner assistance. Never build/destroy an
  unrecorded block, teleport, or bypass existing cleanup preconditions.
- Paused legacy-scaffold tasks now Resume into guarded cleanup (previously
  only modern Navigation3D history triggered this).
- `wait` no longer wastes a decision while legacy/3D scaffold remains and no
  tree skill is available.

## Test in Minecraft

1. Backup world and replace sources; restart Backend and **rebuild Forge**.
2. `/npcortex inventory` shows e.g. `Dirt x16`.
3. `/npcortex task resume` (if currently paused atop the pillar); cleanup
   should run first. Or cancel/cleanup and start a fresh Oak gather.
4. Observe `/npcortex task trace`: `brain_pause` (model reason),
   `safe_retreat_started`, `safe_retreat_completed`, then a fresh `THINKING`.
5. If the same dead end repeats with zero additional mined logs, task should
   pause *after* the scaffold is dismantled.

## Still NOT solved

This hotfix does **not** guarantee access to all branches of Fancy Oak.
`ScaffoldPolicy.MAX_HEIGHT` remains 6; Navigation3D has bounded route,
material and node budgets. Physically inaccessible logs must still result in
an explicit pause rather than fake completion. Full ForgeGradle compilation
and live Minecraft acceptance remain required.
