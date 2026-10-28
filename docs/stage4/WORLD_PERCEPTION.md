# Bounded read-only world perception (G3)

Code: `CPlusWorldPerception` (pure core), `CPlusWorldPerceptionRuntime` (Minecraft binding), `/npcortex m04 observe`.
Contract: `docs/contracts/world_observation_v1.json`. Status: implemented, FIXTURE-tested offline; no live-world run yet.

## Behaviour

`observe <npc> <radius 0..16> <block_id>` scans a box centred on the NPC's own block position for blocks with the given id and
returns the nearest matches. Default vertical range 4 below / 12 above (trees), up to 16 results, budget 8,192 positions.

| Limit | Value | Enforced as |
|---|---|---|
| radius | 0..16 | `RADIUS_TOO_LARGE` |
| below / above | 0..12 each, clipped to the build height (reported) | `VERTICAL_TOO_LARGE` |
| results | 1..64 | `TOO_MANY_RESULTS` |
| block ids | 1..8, `namespace:path`, must be registered blocks | `BAD_FILTER` |
| positions probed | 1..32,768 hard cap (default 8,192) | `BUDGET_OUT_OF_RANGE`; running out gives `PARTIAL_BUDGET` |
| centre | inside the build height and in a loaded chunk | `CENTER_OUT_OF_BUILD_HEIGHT`, `CENTER_NOT_LOADED` |
| dimension | must equal the observed world's | `DIMENSION_MISMATCH` |

The largest legal box (33 x 33 x 25 = 27,225 positions) fits in the hard cap. Nothing runs per tick: a scan happens only when a
request arrives, on the server thread, once per request. No polling loop exists, so cost scales with requests, not with the number
of NPCs.

Scan order is ring by ring outwards (Chebyshev), columns in a fixed order; matches are ranked by `(distance squared, y, x, z)`, so
equal inputs always give byte-identical JSON regardless of how the world was populated. The result list keeps the true nearest
matches within the scanned area; `truncated` says more existed.

Outcomes are explicit. `OK` means the whole box was examined and every chunk was loaded: only then is an empty list a verified
"none found" (`proven_empty`). `PARTIAL_UNLOADED` (part of the box in unloaded chunks, listed), `PARTIAL_BUDGET`, `CANCELLED`
(results withheld) and `REJECTED` never claim emptiness and carry `complete=false`. Chunks are never loaded or generated: the
binding reads through `ServerChunkCache.getChunkNow` (null when not loaded) and `LevelChunk.getBlockState`, never through
`Level.getBlockState`/`getChunk`, and the scan core cannot ask for a chunk at all.

Freshness: every observation carries `capture_tick` and `fresh_until_tick` (+40 ticks by default). Consumers re-check
`CPlusWorldPerception.isFresh(capture, nowTick, maxAge)` against the CURRENT tick before acting; a clock that went backwards
(world reload) is stale. An observation is evidence at a moment, not a permit and not a decision.

## Separation

Observation is read-only and separate from deciding and acting. It takes no motion fence, touches no C1/C2/E3/GWI state, is not a
backend skill, has no HTTP route and writes nothing (the typed JSON goes to the server log; the chat line is a summary). The
command is permission level 2 like every npcortex command; the origin is logged for the pending command-origin experiment and
never used to decide.

## Not provided (by design, for later stages)

Block tags (ids only), entities and item drops (F2 reports a few), line of sight, reachability, tool/hardness data, multi-tick
resumable scans, per-NPC caching, and any push of observations to the backend.

## Unverified without Minecraft gameplay

Behaviour against real chunks (including chunk unload during a scan: the binding then rejects with `INVALID_REQUEST`, never
fabricating air), real tick cost of 8k-27k positions on the Owner's machine, 1/3/5/10 NPC request patterns, dedicated server, and
registry id resolution for modded blocks. Fixture tests prove the rules; they do not prove the live adapter's world access.
