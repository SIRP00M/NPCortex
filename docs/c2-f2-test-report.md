# C2-F2 — Automated Test Report (2026-10-20)

Baseline C2-F1 Full Repository Candidate. Candidate tests executed in the container:

- **Pure Java `javac --release 17`**: **27 self-test classes, 4,985 assertions PASS**, includes F2 `C2F2NaturalLocomotionSelfTest` (62 assertions). Test execution used Java 21 runtime with Java 17 compilation target; final Windows Java 17/Forge build is pending.
- **F2 coverage**: flat diagonal Graph cost sqrt2; B2/B3 optimum consistency; no diagonal corner cut when either orthogonal edge is blocked; direct sweep refusal; baseline 4-way behavior; original strict Waypoint contract and F2-only verified coalescing; omitted-edge live graph revalidation; ticket correlation; heading yaw ±180 wrap; far rolling local follow and near follow ring; bounded candidate work and ten isolated policy instances.
- **Backend unchanged**: `PYTHONPATH=backend/src python -m unittest discover -s backend/tests -q` → **Ran 201 tests in 36.518s; OK**. A number of expected negative tests print model/SQLite exceptions; terminal result is OK.
- **Forge-facing mock Minecraft API**: compiled actual F2 `C2FollowRuntime`, `C2WaypointBridge`, `C2FollowJournal` with self-contained dummy API stubs; **PASS**, not real Forge mapping or runtime.
- **Not verifiable here**: full Gradle 8.8 Forge dependencies, actual Minecraft 1.20.1 server/client gameplay, real animation appearance, diagonal corner collision, follow >8 blocks, MSPT 1/3/5/10, offline/restart/crash.

The previous C2-F1 Follow Basic Gameplay PASS was user-confirmed, but it does not establish F2 Gameplay PASS. See `PENDING_TESTS.txt` for the remaining Quality Gates. No GitHub operations performed.
