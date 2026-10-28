# Stage 4 read-only foundation: identity and dependency map (G1)

Audited 2026-10-28 against the working tree. Source beats older docs where they disagree.

## 1. Identities

| Identity | Defined in | Meaning | Used by |
|---|---|---|---|
| `world_id` | `C1IdentityData.worldId()` (SavedData of the overworld) | One per saved world | backend actors, F2 sampler, E3 admission, M04 identity, perception JSON |
| `dimension_id` | `level.dimension().location()`; `C1IdentityData.Entry.dimension` | `minecraft:overworld`, ... | E3, M04 store (`reserve(..., dimension_id)`), perception request |
| `actor_id` | `C1IdentityData.Entry.actorId` | Persistent NPC identity, the backend's actor | C2/C0 journals (keyed by actor), E3 G2/G3 rows, M04 identity |
| `profile_id` | `Entry.profileId` = the `GameProfile` UUID = `C1PlayerAvatar.getUUID()` | The Minecraft player identity of the NPC | C1 lifecycle/motion receipts, tab/name maps, `activeAvatar` identity check |
| `profile_name` | `Entry.profileName` (`N<character>_<uuid6>`) | GameProfile name, never shown to players | selectors in harness, packets |
| `character` | `Entry.character` | The logical name typed in commands (`yui`) | every `/npcortex ... <name>` command |
| `session_id` | per-server-session random UUID (`CPlusF2WorldSampler.SESSIONS`) | One Forge/backend session | E3 admission, M04 identity |
| `requester_id` | the operator's player UUID (`source.getPlayer().getUUID()`) | Who asked | E3 admission, M04 identity |
| `goal_id` / `action_id` | C2 run / C0 action | One C2 navigation goal and its action | E3/G3 terminal correlation |
| `task_id` / `attempt_id` | random per E3 request | Durable task and its single attempt | E3 rows, M04 steps |
| `observation_id` | random per observation (F2 sampler, perception) | One read-only observation | E3 admission, perception JSON |

Rules that matter for world actions: `actor_id` (backend/journals) and `profile_id` (Minecraft) are different UUIDs and every
runtime check compares them (`avatar.getUUID().equals(identity.profileId())`). World actions must key durable records by
`actor_id`, resolve the live body by `profile_id`, and never accept a name alone. Perception follows this: it resolves the
live avatar exactly as the F2 sampler does and reports `actor_id`, not the profile name.

## 2. M04 contracts versus the local tree (provenance)

- The repository root carries four manifests of historical candidates (`M04_MANIFEST.json`, `M04_OPTION_D_MANIFEST.json`,
  `CANDIDATE_MANIFEST.json`, `M04_AUTO_ACCEPT_MANIFEST.json`). Hashing the working tree against them (LF-normalised, read-only):
  675 files listed in the newest, 643 identical, 32 different, none missing. The only M04-related difference is
  `backend/tests/test_m04_acceptance_review.py` (a local edit); `CPlusM04Runtime.java` differs from the OLD `M04_MANIFEST.json`
  but is identical to the newest candidate. Conclusion: the newest candidate (M04 Option D integrated) is already merged into the
  working tree; no further merge of historical code is needed or was performed. The other differing files are later local edits.
- M04 in the tree: pure `cplus/CPlusM04Contract.java` (steps OBSERVE .. TERMINAL, no dispatcher), Python `cplus_m04_contracts.py`
  and `cplus_m04_store.py` (reserve / prepare_effect / record_step / hold / metadata_retry, no HTTP route), read-only
  `/npcortex m04 status|capture|preview|preview-auto` and the new `observe`. No mutation entry point exists.
- Mutation-capable code that pre-exists and was left untouched: C1 inventory commands (`give`, `clear`, `equip`, `unequip`,
  `copyhand`; operator commands on C1 inventory, not world blocks). The candidate contains no block-mutating code.

## 3. Backend skill declarations versus Forge executors

| Skill | Declared in `agent_skills.py` | Forge executor | State |
|---|---|---|---|
| `move_to` | yes | C2 (`C2SkillRegistry`, `guardedNavigate`), E3 TaskMove | implemented and accepted |
| `break_block` | yes | none | NOT implemented |
| `place_block` | yes | none | NOT implemented |
| `pickup_item` | yes | none | NOT implemented |
| `use_item`, `equip_item` | no | C1 `equip` exists only as an operator command | no skill, no bridge |

`C2SkillRegistry` advertises only `move_to` and the C2 goal text says "use move_to exactly once", so a model that emits another
skill finds no executor: unauthorized mutation is prevented today by absence. `CPlusWorldPerception` is not a skill and is not
reachable from the backend or the model.

## 4. Existing read-only world access and its limits

`CPlusF2WorldSampler` (diagnostic schema 2): 3x3x3 blocks around the actor, at most 8 entities and 8 item drops, never loads a
chunk. It is a precedent, not a base to enlarge: its radius is fixed and it has no budget, filter, ordering or outcome model.
The perception foundation replaces it for target discovery and leaves it untouched for E3 admission.

The same sampler embeds a `permission` block produced by `cplus/CPlusF2ReadOnlyPolicy.decide(status, "break_block")`, which is the
existing deny-by-default precedent: it returns `DENY` for out-of-world positions, `UNKNOWN` for an unloaded chunk, and for every
loaded mutation `UNKNOWN / mutation_claim_adapter_unverified`, never `ALLOW`. `usableEvidence(...)` already binds evidence to
world, dimension, actor and session and to an age limit. The permission adapter of the ledger design extends this class rather than
inventing a second one. These two files are the only Forge sources that mention the mutating skill names, and neither can act.

## 5. Dependency map of the new capability

```
/npcortex m04 observe  (permission 2, read-only, origin only REPORTED)
   -> CPlusWorldPerceptionRuntime  (server thread guard, actor liveness, dimension)
        -> C1IdentityData, C1P0Runtime.activeAvatar, C1P1CLifecycleRuntime.mayRestore, ServerThread
        -> CPlusWorldPerception     (pure core: limits, scan, outcomes, JSON)
             -> View/Probe          (read-only; Minecraft binding uses ServerChunkCache.getChunkNow + LevelChunk.getBlockState)
```
No dependency on C2, Follow, E3/G3, the GWI journal, SQLite, the backend, or the outbox. It does not take a motion fence and does not
touch C1 motion state; it can therefore run for any number of NPCs without coupling to navigation.

## 6. Command authorization today

Every `/npcortex` command tree is `requires(hasPermission(2))` (`NPCortex.java`, `CPlusM03Runtime`, `CPlusM04Runtime`,
`CPlusF1DiagnosticRuntime`). Data-pack functions and command blocks run at level 2, so this is a permission check, not proof of a
human. See `COMMAND_ORIGIN_INVESTIGATION.md`. The Manager GUI uses a custom network packet with a server-side
`hasPermissions(2)`/single-player-owner check (`CharacterManagerService`), which is a stronger origin than a command.
