# NPCortex — Airborne Target Projection / Windows Gameplay Checklist

**Precondition:** Use candidate full repository built with Temurin JDK17, Forge 1.20.1. Before each test, confirm the NPC is on solid safe ground and a player is available in the same dimension. Do not use a previously started/stale Follow session; use `c2 unfollow` then `c2 follow` for a fresh trial if necessary.

Commands (substitute the actual Minecraft name):

```mcfunction
/npcortex c1 spawn yui
/npcortex c2 follow yui YourName
/npcortex c2 followstatus yui
/npcortex c1 motion yui
/npcortex c2 unfollow yui
```

- [ ] **FLY-01 Grounded baseline:** Walk 30-50 blocks straight and diagonally while grounded. `targetMode=GROUNDED`; ordinary terrain-native follow, Jump/Step/Descend and stop-distance behavior unchanged. Record zigzag/video as baseline.
- [ ] **FLY-02 Flying stationary above NPC:** In Creative, hover 3-5 blocks vertically above approximately the same XZ as Yui. After mode debounce, `targetMode=AIRBORNE`. NPC must stop inside horizontal follow-stop distance, not orbit to reduce an impossible vertical difference.
- [ ] **FLY-03 Flying straight XZ:** Fly 2-5 blocks above safe flat ground for 30-50 blocks in a straight line. `targetProjection=AIRBORNE_REACHABLE_SUPPORT` when in local scanned range or `AIRBORNE_REMOTE_NEUTRAL` when far away. NPC should follow on safe ground with markedly less zigzag; record any pauses and `followstatus`.
- [ ] **FLY-04 Flying diagonal:** Fly steadily along X+Z at a non-45 degree bearing. Follow should retain 360-degree steering and should not switch left/right just because the player Y changes.
- [ ] **FLY-05 Vertical-only flight:** Hover at fixed XZ and move Y up/down several blocks. While inside horizontal stop distance, the NPC must remain still. Changing Y by itself must not force a new unsafe ground route.
- [ ] **FLY-06 Takeoff/landing:** Toggle flight, start flying, briefly touch ground, land fully, then resume walking. `targetMode` should debounce instead of flickering; after landing, it should return to `GROUNDED` using actual terrain Y.
- [ ] **FLY-07 Normal jump:** Walk/jump in Survival or Creative without active flight. Normal jumps should NOT switch to airborne projection; 3D navigation follows real grounded target levels.
- [ ] **FLY-08 Flying above a ravine/unsupported floor:** Fly across a void, ledge or water without reachable safe standing support. NPC must not walk into the void or treat the player's midair location as a standing surface; use safe detour if possible, otherwise pause safely.
- [ ] **FLY-09 Different elevations:** Fly above a reachable one-block higher platform, then descend to it and walk. NPC should use safe validated 3D Step/Jump/Safe Descend, not a fabricated ground-level teleport.
- [ ] **FLY-10 Hazards + unloaded chunks:** Verify no new through-wall/berry/lava/cobweb/unloaded terrain bypass caused by projected goals.
- [ ] **FLY-11 Cancellation / Combat:** Unfollow mid-walk and while airborne player is being tracked; also test NPC knockback/combat. No stale C1 motion resumes, no unexpected owner change.
- [ ] **FLY-12 Lifecycle:** Save/Quit, rejoin, player death/dimension change. No persisted Follow motion auto-resumes.
- [ ] **FLY-13 Navigate regression:** Verify `/npcortex c2 navigate` remains unaffected; test safe flat and supported 3D paths separately.
- [ ] **FLY-14 1/3/5/10 NPC:** Measure Forge MSPT and FPS, track motion token isolation and detect any CPU spike from bounded reachable-surface lookup. Record conditions, distances and mods.

### Evidence to send back

For failed cases, capture 10-20 seconds of video, the position of player/NPC, and this pair of commands while the symptom is visible:

```mcfunction
/npcortex c2 followstatus yui
/npcortex c1 motion yui
```

Include whether `targetMode` is `GROUNDED` or `AIRBORNE`, `targetProjection`, `projectedY`, `terrainDecision`, `generation`, `failures`, and `lastFailure` where present. Distinguish a deliberate safe pause from a route-choice oscillation. Mark each test PASS/FAIL/NOT TESTED. Do not infer untested cases passed.
