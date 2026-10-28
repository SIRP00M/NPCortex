# Character Profiles and the NPCortex Manager

Status: implemented and verified offline (see "Evidence"). **Minecraft gameplay and dedicated-server runs are PENDING Owner evidence.**

## 1. Vocabulary (three different things)

| Term | What it is | Where it lives |
|---|---|---|
| **GameProfile** | The Minecraft fake-player identity of a C1 NPC (`profile_uuid`, `N<name>_<uuid6>`). | C1 saved data (overworld `npcortex_c1_p0`) |
| **Character Profile** | The NPC's personality (display name, language, system prompt), one per NPC, in immutable revisions. | Backend SQLite, table `character_profiles` (schema v5) |
| **Character Pack** | A folder `character-packs/<id>/character.json`. Now an *optional preset / legacy source*, never required for a new NPC. | Disk, read-only for the profile system |

An NPC is identified by `actor_id` (C1 -> backend). The Character Profile hangs off that `actor_id`; it has nothing to do with the NPC's name.

## 2. Creating NPCs

* **In game (recommended):** ESC -> **NPCortex Manager** -> *Create NPC*. Enter a name (1-16 letters/digits/underscore), a display name, and Personality / Speech Style / Backstory (or switch to *Raw prompt*). The NPC is spawned locally, then its profile is registered with the backend. If the backend is down the NPC still exists and shows **Profile NOT registered**; press *Register* (the draft is kept while the screen is open).
* **Commands (operators, level 2):** `/npcortex c1 spawn <name>` (generic default profile at first C2 use - the NPC name no longer selects a pack; before this milestone a new NPC named like an existing pack, e.g. `alex`, got that pack, now use `... preset alex`), `... custom` (profile must be registered by hand first), `... preset <packid>` (copy of that pack at first C2 use). The reply always says whether the Character Profile exists yet.
* **Script:** `scripts/register-npc-profile.ps1` (see section 6).

Existing NPCs saved by earlier versions are **legacy**: no profile mode is stored, they keep sending `character_id = lower(name)` exactly as before, and the Manager shows them read-only.

## 3. Backend API

All new profile routes - and every `POST /v1/actors` selector except the legacy `character_id` - require the local token header `X-NPCortex-CPlus-Token` **even when `NPCORTEX_REQUIRE_TOKEN` is off**; the client must be loopback and the `Host` header must name loopback. Body limit for `/v1/actors` and the revisions route is 32768 bytes (other routes keep 8192; a `/v1/actors` body without `profile` is still capped at 8192).

### `POST /v1/actors` (201)

`world_id`, `dimension_id`, optional `actor_id` plus **exactly one** selector:

| Selector | Meaning | Auth |
|---|---|---|
| `character_id` | Legacy. Behaves exactly as before (pack must exist). | legacy rule |
| `profile` | Inline manual Character Profile (below). | **token always** |
| `preset` | Copy pack `<id>` once as revision 1 (missing pack -> 400). | **token always** |
| `default_profile: {display_name}` | Built-in default, name `[A-Za-z0-9_]{1,16}`. | **token always** |
| `require_existing_profile: true` | Bind only; 404 `profile_not_registered` if none. Needs `actor_id`. | **token always** |

Profile-bound actors store `actors.character_id = "@profile"` (NOT NULL stays satisfied; `@profile` can never match a pack id, so any code path that wrongly passed it to `load_character` fails closed with 400 `invalid_character`). Actor row and revision 1 are written in one transaction.

Conflict matrix (same world and dimension are always required, else 409):

| Existing actor | Request | Result |
|---|---|---|
| none | legacy / profile / preset / default | 201 (profile kinds create actor + revision 1) |
| none | require_existing_profile | 404 `profile_not_registered` |
| legacy, same `character_id` | legacy | 201 idempotent |
| legacy, other `character_id` | legacy | 409 |
| legacy | any profile selector | 409 (adoption required, see section 8) |
| profile-bound | legacy | 409 |
| profile-bound | identical `profile` / same `default_profile` | 201 idempotent, `created:false` |
| profile-bound | different `profile` / `default_profile` | 409 |
| profile-bound | `preset` with same recorded source | 201 idempotent (pack not re-read) |
| profile-bound | `preset` with other source | 409 |
| profile-bound | require_existing_profile | 201, current binding |

Profile responses add `"profile": {revision, display_name, composition, source, content_sha256}` and `created`. Legacy responses are unchanged.

### Profile object (also the import/export format)

```json
{ "schema_version": 1, "display_name": "Reika", "language": "en",
  "sections": { "personality": "...", "speech_style": "...", "backstory": "..." } }
```
or, advanced, `"system_prompt": "..."` instead of `sections` (exactly one of the two). `language` is optional (default `en`); a revision that omits it keeps the stored language, and the in-game Manager never sends one. Limits (Unicode code points): display_name 1-80, language 1-16, personality 1-1500, speech_style 0-500, backstory 0-1500, system_prompt 1-5000; control characters rejected (newline/tab allowed in text), unknown keys rejected.
*Structured* profiles are composed by the backend (composer v1): `You are <name>, an NPC companion living in a Minecraft world.` + `Personality:` / `Speech style:` / `Backstory:` lines + a fixed guardrail (never claim to have acted in the world unless a game system confirmed it). User text is appended as data, never formatted. The composed prompt is stored in the revision, so history is byte-exact.

### Other routes

* `GET /v1/actors/{id}/profile` -> `{actor_id, revision, source, content_sha256, composition, effective_system_prompt, profile}`; `profile` can be POSTed back unchanged. Legacy actor -> 404 `profile_not_registered` (the pack is not read).
* `POST /v1/actors/{id}/profile/revisions` `{expected_revision, profile, note?}` -> 201 new revision; 200 `applied:false` for a no-op or an idempotent retry; 409 `revision_conflict` with `current_revision`. History is immutable (SQLite triggers abort UPDATE/DELETE).
* `GET /v1/worlds/{world_id}/profiles` -> every actor of the world with `character_id` and its profile summary (never the prompt).

Error codes: `invalid_profile` 400, `profile_not_registered` 404, `revision_conflict` 409, `forbidden` 403.

## 4. Resolution and isolation

`ChatService.resolve_actor_character(actor)` is the only place that chooses a personality: the actor's latest profile revision, else (legacy actors) its Character Pack. Used by player chat, dialogue creation, dialogue turns and social runs. A legacy actor whose pack is missing fails with 400 `invalid_character`; it is **never** given a default profile.

## 5. Database v5, backup, rehearsal, restore

`user_version` 5 adds only the table `character_profiles` (+ two immutability triggers). Nothing existing is rewritten.

* A **fresh** database is created directly at v5.
* An **existing v1-v4** database is **refused** (file untouched) unless `NPCORTEX_ALLOW_SCHEMA_V5=1`. With the flag, startup (1) writes `<db>.v<N>-<UTC>.bak` with SQLite's online backup API (never a raw copy), (2) verifies it (`integrity_check`, `user_version`, row count of every table), (3) only then migrates, (4) verifies `user_version`, `foreign_key_check` and unchanged row counts **inside the same transaction and commits only if they pass**. A failed backup aborts before any change; a failed post-check rolls the upgrade back (the database stays at its old version) and names the backup. Backups are never deleted by code.
* Inspect a file without opening it for writing: `python -m npcortex_backend.dbtools check <db>`.

### Rehearsal on a COPY (do this before ever upgrading the live database)

1. Stop the backend. 2. Copy `npcortex.sqlite3` (and `-wal`/`-shm` if present) into a scratch folder. 3. In PowerShell:
```
$env:NPCORTEX_DB_PATH = 'D:\scratch\npcortex.sqlite3'
$env:NPCORTEX_ALLOW_SCHEMA_V5 = '1'
cd backend; $env:PYTHONPATH = 'src'; python -m npcortex_backend
```
4. Confirm the log line `Schema v4 backup verified: ...bak`, then `python -m npcortex_backend.dbtools check` on both the database (expect `user_version` 5) and the `.bak` (expect 4, `integrity` ok, same counts as before). 5. Restore drill: stop the backend, rename the upgraded file (do not delete), copy the `.bak` over the original name, run `dbtools check` (expect 4, ok), start the **previous** backend build and call `/health`.

The live upgrade is a separate operational decision; this implementation never runs it for you.

## 6. `scripts/register-npc-profile.ps1`

```
-Action Check|Export|Register  -ActorId <uuid> [-WorldId <uuid>] [-Dimension minecraft:overworld]
-ProfileFile p.json  -OutFile out.json  [-Apply] [-Force]  [-BaseUrl http://127.0.0.1:8765]
```
`Register` is a dry run until `-Apply`; it registers the world (`NPCortex-C1-<world_id>`, the name the mod uses), the actor and the profile, then reads the profile back and compares hash and revision. IDs come from the reply of `/npcortex c1 spawn <name> custom`. The credential (env `NPCORTEX_CPLUS_F1_TOKEN` or the backend's secret file) is only sent as a header to a loopback backend and is never printed or written. `Check` exits 0 (registered) / 2 (not registered) / 1 (error).

## 7. In-game Manager design

* ESC menu button (top-left, only on the real game menu, failures swallowed) -> list screen -> edit screen. Strings are translation keys (`assets/npcortex/lang/en_us.json`); server messages are English.
* Separate channel `npcortex:manager` (version `manager-1`), one capped `byte[]` per message (`ManagerProtocol`, <= 30000 bytes, strict UTF-8). The C1 channel `npcortex:c1_sync` is untouched. Packets never carry a URL, token, world id or actor id: the client names an NPC and the server derives identities from its own saved state.
* Authorization on every request: permission level >= 2 or single-player owner. All operators can edit all NPCs (same trust as `/npcortex`).
* One operation per player and per NPC at a time; replies carry the client's operation id and stale ones are ignored; a timeout is shown as **UNKNOWN**, not failed.
* Create order: validate -> local spawn in `custom` mode -> register world -> register actor+profile -> read back and compare. Backend trouble leaves a visible NPC with **Profile NOT registered**.
* While the screen is open the single-player game is paused. `IntegratedServer.tickServer` only skips the vanilla tick while paused; queued server tasks (packet handlers, HTTP completions) still run. This was read from the bytecode, not observed at runtime: if Owner testing shows a stall, set `ManagerScreen.PAUSE_WHILE_OPEN = false`.

## 8. Deferred: adoption of legacy actors (contract only, no code)

`POST /v1/actors/{actor_id}/profile/adopt` with `confirm_actor_id` and either `"from": "pack"` (copy the pack's current content) or an inline `profile` (when the pack is gone), optional `dry_run`. Allowed only for a legacy actor without a profile (else 409); writes revision 1 with source `adopted:pack:<id>` / `adopted:inline`; `actors.character_id` is **not** rewritten, so the mod's legacy registration keeps matching. After adoption the system prompt must equal the pack's byte for byte. Never automatic; Yui and Guide are not adopted by this milestone.

## 9. Evidence and tests

Backend: `cd backend/tests; $env:PYTHONPATH='../src'; python -m unittest discover -s .` (includes `test_profiles_v5.py` G1-G7 and `test_register_profile_script.py`). Java: `cd forge-mod; gradlew verifyManagerProtocol verifyManagerCore verifyProfileRegistration build`, `scripts/test-c1-profile-identity.ps1` (NBT, mocked), `python scripts/check-dist-separation.py` (static client/server separation). The v4 fixture `backend/tests/fixtures/v4_baseline.sqlite3` was generated by the pre-v5 code (`make_v4_fixture.py`).

## 10. Owner acceptance checklist (Windows, real Minecraft) - all items are PENDING until you report them

Setup: build/run as usual (`scripts/dev-forge.ps1`), start the backend on a **fresh** database so the live one is never touched:
`$env:NPCORTEX_DB_PATH='D:\scratch\profiles-test.sqlite3'; cd backend; $env:PYTHONPATH='src'; python -m npcortex_backend` (start Ollama only if you want chat replies). Use a new disposable single-player world with cheats on.

1. **Button**: press ESC. "NPCortex Manager" appears top-left; the vanilla buttons are unmoved and still work. Check with a second mod if you use one (overlap = cosmetic only).
2. **List**: open it. Empty world says "No NPCs ... Create NPC".
3. **Create**: Create NPC -> name `Reika`, display name `Reika`, Personality text, Speech Style, Backstory -> *Create NPC*. Expect green "NPC created and Character Profile registered (revision 1)" and an NPC beside you. Repeat for `Mika` using *Mode: Raw prompt*.
4. **List again**: both show "Profile OK - revision 1". A legacy NPC (`/npcortex c1 spawn Yui` in an old world) shows "Legacy Character Pack (read-only)".
5. **Edit**: Edit -> change Personality -> Save -> "Saved as revision 2". Reload shows revision 2. Edit with unsaved text then Cancel -> a discard confirmation appears.
6. **Conflict** (two operators editing the same NPC, or two game clients): save from a stale screen -> yellow "revision ... Reload".
7. **Backend offline**: stop the backend, create `Sakura` -> NPC appears, status "Profile NOT registered ... offline", the typed text is still in the form; start the backend, press *Retry registration* -> success. Also list while offline: rows say "Backend offline - status unknown".
8. **UTF-8**: put Thai/Japanese/emoji text in Backstory, save, reload: identical.
9. **Pause behaviour (single-player)**: with the Manager open, a save/create completes without waiting for you to close it. If it hangs until you close the screen, report it (switch `ManagerScreen.PAUSE_WHILE_OPEN` to false).
10. **Isolation**: `/npcortex c2 navigate Reika ...` then `/npcortex c2 status Reika` shows `profile=source=inline rev=N`; a second NPC with another personality is unaffected. (C2 behaviour itself is unchanged; this only checks registration.)
11. **Custom without profile**: `/npcortex c1 spawn Zed custom`, then `/npcortex c2 navigate Zed ...` fails with "Character Profile NOT registered" (and, as before for any registration failure, the NPC needs `/npcortex c2 reset Zed` afterwards - run `scripts/register-npc-profile.ps1 -Action Check` first to avoid that).
12. **Dedicated server**: run `gradlew runServer` against a scratch world, join with a client: the Manager works for an operator and says "Not authorized" for a non-operator. Server log must not show client-class errors.
13. **Restart**: quit and reload the world: NPCs and statuses are the same; Yui/Guide packs unchanged.
