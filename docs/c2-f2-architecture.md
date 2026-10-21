# C2-F2 — Natural Player-like Locomotion (Architecture C)

## Source evidence / Baseline

Baseline: C2-F1 Full Repository Candidate. F1 already follows player by UUID but the 3D graph only admitted cardinal neighbors; Follow paused at horizontal >8 blocks. C2 Waypoint Bridge snapped yaw at each movement segment and C1 motion ran per waypoint. No backend changes are needed for operator-only follow.

## Scope and ownership invariants

- Four-way legacy graph and waypoint tests remain valid; live 3D graph adds flat-only diagonals; slope changes continue via existing **cardinal** Vanilla motion primitives.
- A diagonal WALK requires verified reciprocal orthogonal corridors on both sides and verified direct swept-player AABB; a coalesced segment separately rechecks **every** intermediate edge and actual avatar-to-endpoint AABB on the Forge server thread.
- Follow-only route coalescing is ≤3 blocks and same exact footY and heading, with one C1 ticket per coalesced segment. Static C0 goal/navigation shares the verified 3D graph but never accepts long coalesced segments. C1 Vanilla `travel`/`jumpFromGround` untouched.
- Follow-only gradual heading update occurs only when C1 is idle/grounded, <=18° per tick; next ticket begins after heading alignment. This is **not** per-tick change of heading during active travel, preserving motion plan physics and combat priority.
- Rolling follow only uses fresh radius-6 loaded graph, local frontier toward target, at most 4 B3 candidate routes/3000 expansions each with 20 tick cooldown; re-capture at new current location. The target's chunk must be loaded and in same dimension; no force loading, no teleport.
- C0 navigation and F2 Follow maintain exclusive ownership. Stale tickets/foreign movement interrupt; Unfollow/Cancel cannot revive an old Follow session. Journals continue to fail closed after Save & Quit.

## Cautions / acceptance boundaries

Yaw interpolation before each ticket can still cause a brief pivot on sharp turns; realistic continuous mid-motion steering, animation and acceleration are **not** claimed. Very narrow diagonal hallways may conservatively refuse valid routes because the swept rectangular player AABB is larger than the ideal rotated capsule; do not loosen collision safety without gameplay evidence. Far player moving faster than NPC, unloaded terrain or impossible paths may still result in `PAUSED_UNREACHABLE`; rolling is not global navigation or teleport catch-up.

Review `docs/c2-f2-windows-checklist.md` and `PENDING_TESTS.txt` before treating this Candidate as accepted.
