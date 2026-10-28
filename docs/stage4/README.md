# Stage 4 read-only foundation (offline milestone, 2026-10-28)

No mutation, no ledger runtime, no schema change, no gameplay. Everything below is OFFLINE verified; live behaviour is PENDING.

| Document | Content |
|---|---|
| `IDENTITY_AND_DEPENDENCY_MAP.md` | G1: identities, M04 provenance against the manifests, skill declarations vs executors, dependency map |
| `COMMAND_ORIGIN_INVESTIGATION.md` | G2: how origin signals behave (bytecode + offline experiments), verdict UNKNOWN, experiment protocol, fail-closed design |
| `WORLD_PERCEPTION.md` | G3: the read-only perception foundation and its contract (`docs/contracts/world_observation_v1.json`) |
| `PERMISSION_AND_WORLD_EFFECT_LEDGER_DESIGN.md` | G4: deny-by-default authority, order of operations, six-row crash/failure matrix, Owner decisions |

## Status

| Item | State |
|---|---|
| Perception core and Minecraft binding, `/npcortex m04 observe` | IMPLEMENTED, fixture-tested; live world PENDING |
| Command-origin attestation | UNKNOWN (classifier is reporting-only) |
| Permission adapter, allow-list, world-effect ledger, executor | DESIGN ONLY |
| `break_block`, `place_block`, `pickup_item`, `use_item`, `equip_item` | NOT implemented, not authorized |

## Readiness

- Design of a guarded first action: ready. Read-only perception: implemented. Native mutation: not ready (see the P0 list).
- P0 before any mutation: attested origin (gameplay experiment), permission adapter and Owner allow-list, ledger with intent
  before effect, executor with cancel quiescence, server-side receipt, save barrier and start-up re-verification.
- P1: observation consumers must re-check freshness; live perception cost measurement; tag/entity/line-of-sight observation.
- P2: resumable multi-tick scans, observation caching, backend exposure of observations.

## Update 2026-10-28: NO-OP world action foundation (Owner-approved Option D+)

See `WORLD_EFFECT_NOOP.md`. Authority (Manager packet sender + Owner allow-list), durable world-effect ledger (`cplus_we_*`), cross-store
actor fence (E3 G2 / M04 / WE, both directions) and a no-op executor are IMPLEMENTED and offline-tested; the only action is `noop`.
Native `break_block` / `place_block` / `pickup_item` / `use_item` / `equip_item` remain NOT implemented and NOT authorized. Live GUI behaviour: PENDING Owner acceptance.

## Update 2026-10-28: native break + pickup (Owner-approved Option D / Architecture B+)

See `NATIVE_BREAK_PICKUP.md` and `docs/contracts/native_effect_receipt_v1.json`. Offline gates G0-G6 PASS; live GUI/gameplay PENDING; `break_block` and `pickup_item` are now implemented behind the Owner allow-list. `place_block`, `use_item`, `equip_item` remain NOT implemented.
