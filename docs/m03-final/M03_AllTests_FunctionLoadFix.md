# M03 All-Tests: `Unknown function` fix (Minecraft 1.20.1 Forge)

### What happened
`/function npcortex_m03_suite:menu` displayed clickable text, but `/function npcortex_m03_suite:enable` failed with Unknown Function. The menu has only vanilla `tellraw`. The enable/test functions call `npcortex debug cplus m03 ...`, which was invisible to Minecraft's datapack loader because the TEMP M03 Brigadier node required a live Player at parse time. Minecraft loads functions using a synthetic level-2 source without a Player.

### Narrow fix
`CPlusM03Runtime.commands()` now permits level-2 *parsing* without Player, while `enable` and `session()` enforce a real in-game OP player at *execution*. Non-player console/command block cannot establish a session. This does not change Transport, navigation, databases, recovery, or world mutation.

### Windows instructions
1. Back up the full repository and work on a COPY of the test world. Replace the previous code with this full Candidate while preserving local `.env`, SQLite, world saves, logs and config. Do not delete HOLD/Outbox records.
2. `cd F:\AI\NPCortex` then `python .\scripts\test_m03_alltests.py` and `.\scripts\dev-forge.ps1 -Task build` (JDK 17).
3. Restart Minecraft/runClient so it uses the freshly built mod (build alone doesn't hot-reload Java). Datapack content is unchanged; reinstall only if the world does not contain it.
4. Join as an OP, run `/reload` and check `forge-mod/run/logs/latest.log` for function parse errors.
5. Run `/function npcortex_m03_suite:enable`, then `/function npcortex_m03_suite:status` and `/function npcortex_m03_suite:menu`. Expected: bridge enabled message + menu, and no Unknown Function. If enable says `HOLD: already enabled`, the session is already enabled; verify status.
6. Only then execute Test 6A–10 phases from existing M03 All-Tests Guide. This is not a test PASS for those scenarios.

### Diagnostics if still failing
Try direct `/npcortex debug cplus m03 status` as the in-game OP. Verify `/datapack list enabled` and copy the first `Failed to load function` error from `forge-mod/run/logs/latest.log`. Do not reset SQLite or replay unfinished movement to force a PASS.
