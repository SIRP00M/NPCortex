# C1-R2 — Vanilla Combat Physics, Architecture C (Implementation Candidate)

Scope: Minecraft 1.20.1 / Forge 47.4.10 / Java 17. R1-A Mob-Like Death Poof is the user-accepted base.

## Design: Vanilla damage and impulse, one authoritative physics owner

**Architecture C, staged and connection-safe:** The custom `C1PlayerAvatar extends ServerPlayer` runs its existing Vanilla world-side `ServerPlayer.tick()` for player-state bookkeeping. Vanilla `hurt`, `LivingEntity.knockback`, damage invulnerability frames and the `KNOCKBACK_RESISTANCE` attribute remain authoritative. R2 connects their resulting **actual `Vec3` impulse** to the single P1-A `Player.travel` loop on server tick END. While a combat impulse is active, an operator movement command is temporarily suspended, without generating an extra physics tick or resetting horizontal velocity. After a bounded impulse settles, the original movement command may continue. The existing `ServerPlayer.doCheckFallDamage` remains the sole fall check on an applied movement step.

R2 adds a `COMBAT` owner to the R0 lifecycle policy and hooks `C1PlayerAvatar.knockback` **after `super.knockback`**. Null/unchanged impulses are not treated as hits, including successful Knockback Resistance. An additional passive world-tick check can capture **Vanilla-flagged** velocity injected outside the regular knockback callback (e.g., some explosions). This fallback is opportunistic; not all modded velocity sources have been proven to mark `hasImpulse`. No custom knockback force equation is added.

New motion synchronization uses Vanilla `ClientboundSetEntityMotionPacket` sent **only to P0-verified real viewers** on a detected impulse, including verified viewers that join during an active impulse. Existing vanilla ChunkMap entity position tracking remains intact. No NPC entity or network protocol replacement, no Qwen backend changes, no `ServerPlayer.doTick()` activation and **no full PlayerList login emulation**.

### Why this is not yet unrestricted full Player Tick parity

`ServerPlayer.doTick()` is still intentionally disabled because it assumes a connected human player and may duplicate P1-A travel. This R2 candidate integrates the relevant **combat tick responsibility**, not every Vanilla player system. Mod hooks relying on real logged-in players, every explosion/magic velocity path, vanilla sprint attacks, and autonomous monster player-target acquisition are **not** certified. The physics integration is intended to be verified through gameplay before expanding further.

### Runtime behavior and safeguards

- Never runs a second `travel()` in a tick. Per-NPC map includes either a controlled movement intent, gravity settling, or a combat impulse; a combat impulse can temporarily override intent in the same session.
- Vanilla controls original damage, knockback magnitude, resistance, status effects and cooldown; NPCortex integrates movement in loaded chunks only.
- Combat window: up to 100 ticks; minimum six ticks, then finishes early when grounded and velocity is small. For airborne motion falling/gravity continues using P1-A existing safety bounds.
- Incompatible/dead/detached/transition actors cannot begin new combat sessions. Death tears down their active motion; R1 death animation and particles are unchanged.
- Existing R1-A 26-tick Death Animation and one-shot Poof, Inventory/keepInventory, P0 network repair, persistent UUID and original backend unchanged. No SavedData schema migration.
- No Portal functionality (R3) and no target-acquisition or Qwen combat AI in this R2 scope.

## Windows instructions

Extract Full Repository ZIP into a clean directory. Use JDK 17, test in a **copy of a Minecraft world** and run from repository root:

```powershell
.\scripts\test-c1-r2-combat.ps1
.\scripts\test-c1-r0-foundation.ps1
.\scripts\test-c1-r1-death.ps1
.\scripts\test-c1-p1-motion.ps1
.\scripts\test-c1-p1-rotation.ps1
.\scripts\test-c1-p0-sync.ps1
.\scripts\test-c1-p1-inventory.ps1
.\scripts\test-c1-p1-inventory-saveddata.ps1
.\scripts\test-c1-p1-lifecycle.ps1
.\scripts\test-c1-p1c-vanilla-tick.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

If Build fails, do not proceed with gameplay. Capture compiler output and report it. The local Linux authoring environment could not fetch Gradle 8.8 from `services.gradle.org` and has **not** compiled the Forge sources.

### Test 1 — idle Vanilla knockback

Spawn Yui on level ground with clear space in front/behind. Stand 2–4 blocks away and run:

```mcfunction
/npcortex c1 spawn yui
/npcortex c1 sync yui
/npcortex c1 combat yui
/npcortex c1 knockback yui 0.4
/npcortex c1 combat yui
/npcortex c1 motion yui
```

**Expected:** One new server-side impulse, Yui slides backward and settles without floating or teleporting; `COMBAT_IMPULSE` log occurs and viewer sees motion. `knockback` is a developer-only deterministic **Vanilla knockback callback fixture**, not a real attack/damage event. Because of Vanilla random resistance, try again after settling if no vector is applied. Never equate fixture success with full combat acceptance.

### Test 2 — interrupted operator movement

After settling, send:

```mcfunction
/npcortex c1 move yui forward 8
/npcortex c1 knockback yui 0.6
/npcortex c1 motion yui
```

Apply the second command before the first completes. **Expected:** the NPC is pushed by the real impulse, no sudden horizontal-velocity reset; movement may resume after the impulse ends. Run `/npcortex c1 stop yui` during a second knockback to check cancellation does not delete active physical momentum.

### Test 3 — real combat sources

With Yui in Survival and spawn protection elapsed, physically strike Yui with a normal sword, then with a Knockback II sword. Compare visible displacement (same relative geometry, unarmored/no potion effects), damage and cooldown. If needed give yourself the enchanted sword in Minecraft 1.20.1:

```mcfunction
/give @s minecraft:diamond_sword{Enchantments:[{id:"minecraft:knockback",lvl:2s}]} 1
```

Then observe real melee damage (do not substitute `/npcortex c1 damage yui 3`, because its generic DamageSource is not a melee knockback test). Test repeated hits to observe Vanilla invulnerability frames; if changing knockback resistance with a game fixture, compare before/after. Verify collision against a wall and knockback off a safe short platform (Fall Damage). For Zombie tests, note that Vanilla monster AI may not automatically acquire NPCortex synthetic players because they are not in PlayerList; this is a **separate unverified targeting gate**, not proof that combat physics failed.

### Test 4 — death, tracking, inventory regression

```mcfunction
/gamerule keepInventory true
/npcortex c1 give yui minecraft:bread 3
/npcortex c1 kill yui
/npcortex c1 lifecycle yui
```

Expected: R1 player fallover → exactly one mob-like Poof → dead. After waiting for animation:

```mcfunction
/npcortex c1 respawn yui
/npcortex c1 inventory yui
/npcortex c1 sync yui
```

Also test `keepInventory=false` with countable, nonvaluable items in a **separate copied world**, plus save/quit, re-tracking, rotation, inventory replay and independent Yui/Hina/Ai. No item duplication or changed UUID.

### Test 5 — multi-NPC / performance / multiplayer

Run idle+hit at 1, 3, 5 and 10 active NPCs in a safe loaded area; measure FPS and MSPT/TPS where tools expose metrics. Specifically test a viewer who joins or re-enters render distance during the impulse and confirm the motion packet/position remain consistent; test multiplayer with two real viewers if available. No performance or multiplayer pass can be inferred from isolated Java tests.

## Evidence & open gates

- R2 isolated Java 17 policy and regression tests: **928 assertions passed** (includes 114 new C1-R2 assertions and two updated R0 mock/ownership assertions; JDK 21 host with `javac --release 17`). These verify policy/wiring/test doubles, **not** actual Forge combat integration.
- Backend C0 focused test unchanged: `56 passed, 8 subtests passed`, clean exit 0.
- Forge Gradle wrapper blocked before Java compilation by `UnknownHostException: services.gradle.org` (Gradle 8.8 cannot download). **No Forge Build / Minecraft Gameplay / dedicated server / multiplayer / mod compatibility acceptance claimed.**
- P0/P1-A/P1-B/R1-A user-confirmed previous gameplay acceptance is historical and not newly certified by this R2 candidate.

If Minecraft shows no knockback despite nonzero server velocity, or compilations fail, stop and return source/log evidence for an R2 fix audit before proceeding to R3.
