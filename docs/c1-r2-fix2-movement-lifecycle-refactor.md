# C1-R2 Fix #2 — Movement Lifecycle Refactor (Architecture C)

Date: 2026-10-15. **IMPLEMENTATION CANDIDATE — Windows Forge build/gameplay still unverified.**

## Why Fix #1 did not work

Observed Windows log after a real Player Knockback II sword hit:

```text
C1-R2 COMBAT_IMPULSE ... velocity=(0.92536, 0.4, 0.76298) motionPlanned=false
C1-R2-FIX1 COMBAT_STEP ... age=1 incoming=(0.0,0.0,0.0)
    actualDelta=(0.0,0.0,0.0) velocityAfter=(0.0,-0.0784,0.0)
```

Vanilla **did** calculate horizontal/vertical knockback. The synthetic
`ServerPlayer` does not receive client movement packets, and between the
attack callback and the authoritative END-tick physics, the live motion vector
had been cleared/replaced. The Fix #1 collision fallback could not work with
zero incoming motion. The precise engine call that erased the vector was not
identified by this log; this refactor bridges the demonstrated tick-boundary
problem without claiming that a particular Vanilla or mod caller is the culprit.

## Changes within the approved C scope

- `C1PlayerMotionPhase`: explicit single movement owner **COMBAT > COMMANDED > SETTLING**. Only one branch issues the authoritative `travel()` in any server END tick. Does not enable `ServerPlayer.doTick()` or add a fake client connection.
- `C1VanillaMotionOwnership`: per-avatar, unpersisted state tracking the **last actual Vanilla-calculated** knockback velocity from `super.knockback()`. Multiple knockback callbacks before the physics step replace the snapshot; they are never summed.
- The Combat phase checks live velocity; if the tick boundary removed all meaningful horizontal/launch motion, it restores the captured motion once into the *existing* movement owner. Subsequent frames carry Vanilla's **post-`travel()` damped** velocity, never the original attack force. Meaningful live Vanilla velocity wins over the saved snapshot.
- Existing `Player.travel()` stays first, with Fix #1's `Entity.move(MoverType.SELF, ...)` **only for components `travel()` did not move**, using Minecraft collision. No teleport, no new knockback formula, no extra player/mob attack rules.
- Collision clamps motion owned for the next frame, preventing reuse of a vector after collision; death/despawn/stop/settle complete/Server Stop drop transient owned state.
- Grounded normal negative gravity (~-0.0784) no longer artificially holds an idle Combat Session open until the 100-tick timeout.
- `C1-R2-FIX2 COMBAT_STEP` reports `ownership`, `liveBefore`, `incoming`, `travelDelta`, `fallback`, `actualDelta`, `velocityAfter` and collision flags for first three frames.
- No Backend changes, SavedData schema change, AI logic, automatic portal or Github operation.

**Compatibility limitation:** NPCortex still is not an actual logged-in network player. This fixes the attack/world/motion phase ownership model, but does not assert complete Vanilla/Forge mod parity. An external mod that intentionally replaces velocity between callbacks and movement may require a documented compatibility hook; avoid forcing the capture over a meaningful newer live velocity.

## Run on Windows (Java 17 / Forge 1.20.1)

```powershell
cd F:\AI\NPCortex
.\scripts\test-c1-r2-fix2.ps1
.\scripts\test-c1-r2-combat.ps1
.\scripts\test-c1-r0-foundation.ps1
.\scripts\test-c1-r1-death.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Use a backup/Test World, open flat unobstructed ground, **reset/respawn Yui if DEAD**. Keep `Options > Video Settings > Particles > All` for the regression.

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 sync yui
/npcortex c1 motion yui
/npcortex c1 combat yui
```

Use a real Player attack (normal diamond sword, then this enchanted sword):

```mcfunction
/give @s minecraft:diamond_sword{Enchantments:[{id:"minecraft:knockback",lvl:2s}]} 1
```

Hit Yui **once**, inspect visual displacement and compare `/npcortex c1 motion yui` before/after. Do **not** count `COMBAT_IMPULSE` alone as success.

```mcfunction
/npcortex c1 motion yui
/npcortex c1 combat yui
```

For the first post-attack frames run:

```powershell
Get-Content F:\AI\NPCortex\forge-mod\run\logs\latest.log |
  Select-String 'C1-R2 COMBAT_IMPULSE|C1-R2-FIX2 COMBAT_STEP|C1-P1 motion_done' |
  Select-Object -Last 40
```

Expected: **`ownership=VANILLA_HANDOFF`** when the Vanilla vector was cleared before the physics step; `incoming` must contain nonzero horizontal components, and `actualDelta`/server `motion pos` must change. If `travelDelta=(0,0,0)` then collision fallback must have an actual, non-duplicated displacement except when blocked by a real obstacle. `VANILLA_LIVE` and `OWNED_CONTINUATION` are also valid under their documented conditions.

### Remaining Minecraft gates — none automatically passed by Java tests

1. **Player attack:** unenchanted hit and Knockback II move the NPC in the correct direction. Both damage/iframes and knockback resistance should be checked; confirm no extra repeated impulses.
2. **Mob attack:** Zombie knockback continues to work, without doubled force.
3. **Moving impact:** start `/npcortex c1 move yui forward 8` then hit; movement pauses for combat and resumes/cancels correctly.
4. **Collision:** hit towards a wall/ceiling and on safe ledges; no phasing, double displacement, floating or persistent movement after rest; fall damage still works.
5. **Death regression:** kill Yui, verify existing Vanilla-style death animation + single mob-like POOF, then Respawn with stable UUID.
6. **Inventory:** both keepInventory true/false semantics, no duplicate loot or leaked transaction; restart/re-tracking/rotation preserved.
7. **Multi-NPC:** Yui, Hina, Ai stay independent. Restart, rejoin and tracking should not replay combat velocity or respawn duplicated NPCs.
8. **Performance:** compare 1/3/5/10 NPC, idle/moving/combat TPS/MSPT; no supported claim without real measurements. Multiplayer/Mod compatibility still open.

**Stop/return logs if:** nonzero `incoming` but `actualDelta=(0,0,0)` on open ground, hit repeats after release, velocity increases during idle, entity phases through blocks, death/drop duplicates, or any Forge build failure.

## Local verification

- `javac --release 17` isolated Java regression suites: **1227 assertions passed** (after adding movement phase ownership tests), run on JDK 21 host. The tests do **not** compile Forge classes or simulate Minecraft physical entities.
- Backend C0 focused `unittest discover` (`test_c0_*.py`): **69 tests, exit 0**.
- Forge Gradle wrapper: could not download Gradle 8.8 from services.gradle.org (failure **before** compile). Windows `dev-forge.ps1 -Task build` mandatory.
- No Gameplay/Network/10-NPC acceptance has been conducted on this candidate. No Github operations performed.
