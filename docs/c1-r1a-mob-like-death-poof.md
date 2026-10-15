# C1-R1-A — Mob-Like Death Despawn Particles (Candidate)

Minecraft 1.20.1 / Forge 47.4.10 / Java 17. Presentation-only change to the accepted R1 Verified Vanilla Player Death Animation.

## Behavior

- At lethal damage, NPCortex preserves the normal R1 26-tick fallover. The death state, drops and inventory snapshot are committed **once** as before.
- At the natural end of the animation, while the dead NPC avatar is still attached, the server emits a **one-shot** Vanilla burst: 26 `ParticleTypes.POOF` plus 10 `ParticleTypes.CLOUD` at the NPC body, then detaches the avatar.
- No particles on early remove/retrack failure, invalidated actor/lifecycle, or server stop; no sound, no new networking, no new status flag, no world/chunk scans. `POOF` is visual only; nearby same-dimension viewers need particles enabled to see it.
- This is intentionally **mob-like presentation** added to an NPC using Vanilla Player death animation. Ordinary Vanilla players do not normally create this burst on death.

## Windows build and acceptance

From repository root with JDK 17:

```powershell
.\scripts\test-c1-r1-death.ps1
.\scripts\test-c1-r0-foundation.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

Use a **copied Test World**. Set Options → Video Settings → Particles to `All`; stay about 3-5 blocks from Yui (viewers more than ~32 blocks away may not receive these normal-distance particles).

```mcfunction
/gamerule keepInventory true
/npcortex c1 spawn yui
/npcortex c1 give yui minecraft:bread 3
/npcortex c1 kill yui
```

Observe: Vanilla Player fallover for ~1.3 seconds → **one obvious short white smoke/poof burst** → avatar vanishes. Logs include `DEATH_PRESENTATION_BEGIN`, `DEATH_DESPAWN_POOF count=36`, and `DEATH_PRESENTATION_END` in that order. Server log confirms the broadcast request **not** that viewers saw particles. Before completing the presentation window, particles must not spawn.

Then:

```mcfunction
/npcortex c1 respawn yui
/npcortex c1 lifecycle yui
/npcortex c1 inventory yui
/npcortex c1 sync yui
```

Repeat with `/gamerule keepInventory false` on a **separate prepared fixture** and check that bread drops once and does not duplicate or appear again after respawn. Test 2-3 NPCs dying at distinct times, Save & Quit mid-fallover, and leave/re-enter render distance. These cases must not make repeated poofs or resurrect dead entities. If you test a world shutdown during presentation, expect the persisted `DEAD` state with no replayed visual effect after restart.

## Evidence and scope limits

- Pure Java 17-compatible death policy and existing P0/P1/R0 regression are runnable independently of Forge; test runs/exit codes are reported separately.
- Forge `compileJava`, game client rendering, mob-like particle appearance, mod compatibility, multiplayer viewers, item-duplication crash injection and 1/3/5/10-NPC performance **require additional Windows testing**. A successful state test does not establish these outcomes.
- No GitHub operations. `CHANGELOG.md` is the project's only changelog.
