# NPCortex C-F0 Windows Verification Checklist

Baseline: Airborne Follow Target Projection Fix Full Repository. Candidate: C-F0. Contract-only: no new commands or interaction executors.

## Windows JDK17/Backend

- [ ] `java -version` and `javac -version` show Temurin 17.
- [ ] `powershell -ExecutionPolicy Bypass -File .\scripts\test-cplus-f0-contracts.ps1` passes Java contract checks and Python backend tests.
- [ ] `powershell -ExecutionPolicy Bypass -File .\scripts\test-c2-airborne-target-projection.ps1` passes all original isolated 22 follow suites.
- [ ] `powershell -ExecutionPolicy Bypass -File .\scripts\dev-forge.ps1 -Task build` succeeds on JDK17. **Not verified in the container.**

## Minecraft 1.20.1 Forge regression (no new commands expected)

- [ ] `/npcortex c1 spawn yui` works as in baseline.
- [ ] `/npcortex c2 follow yui <player>` works; ordinary grounded Follow remains smooth.
- [ ] Flying target (+3–5 Y) has no recurrence of zigzag in previously confirmed case.
- [ ] `/npcortex c2 unfollow yui` cancels as before, no stale C1 movement.
- [ ] Static C2 navigate/cancel and 3D jump/descend still work in verified fixtures.
- [ ] C1 give/equip/ledger and inventory replay unchanged.
- [ ] Server Save & Quit / rejoin does not auto-run an old C+ action (there is **no live C+ action** in F0).
- [ ] Proposed `/npcortex spawn yui` **not** registered yet; `/npcortex debug ...` also **not** registered yet. These are C-F3 future routes.
- [ ] No new world mutation, inventory write, forced chunk load, or backend skill advertised as a result of this update.
- [ ] 1/3/5/10 NPC MSPT and server stability independently measured in Forge (not certified by isolated tests).

## Report results

Capture build command output, relevant `latest.log`, C1 motion/ledger, C2 followstatus before/after, and MSPT sample. Mark only tested items PASS; keep all others pending.
