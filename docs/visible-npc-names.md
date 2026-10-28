# Visible NPC names

NPCs are Player entities, so Minecraft draws their *GameProfile* name (for example `NReika_237778`: `N` + first 8 characters of the C1 name + `_` + 6 hex digits of the profile UUID). Since this change the name tag shows the logical C1 name (`Reika`).

## How (display only)

* The GameProfile name and UUID, the saved C1 identity, `@e` selectors, `/data`, logs and C2 are **unchanged**. `C1P0Runtime` still creates `new GameProfile(entry.profileId(), entry.profileName())`.
* Vanilla draws `Player#getDisplayName()`. Forge computes it through `PlayerEvent.NameFormat` and caches it (`refreshDisplayName()` recomputes). The client answers that event for UUIDs it was told are NPCs; nothing else is ever renamed.
* A **separate clientbound channel** `npcortex:names` (`names-1`) carries `UUID -> logical name` maps (`NpcNameCodec`: version byte, replace flag, up to 240 entries, names `[A-Za-z0-9_]{1,16}`, any malformed packet is dropped whole). The C1 sync channel `npcortex:c1_sync` (`c1-r1-death-1`) is untouched, and the client can never send a name to the server (the channel is `PLAY_TO_CLIENT` only).
* Lifecycle: full `replace` map on login, respawn and dimension change (all saved NPCs of the world, spawned or not); a one-entry `add` is announced when an NPC is spawned or restored, **before** its player-info/spawn packets; the client clears the map on logging in and out (no mapping leaks between worlds/servers) and refreshes the display name of tracked entities whose mapping changed.
* Applies to every NPC (legacy and new, any number), existing worlds included: the map is built from the saved C1 identities.

## Limits (honest)

* Only clients running the mod see the logical name (the mod is already required on both sides).
* `/data`, command output naming the entity, selectors and logs still use the internal name by design.
* A separate server-side `PlayerEvent.TabListNameFormat` handler attempts to display the logical C1 name in player-info/TAB entries without changing the internal GameProfile identity. The in-game TAB presentation for the latest source is **not yet visually accepted**; it may still show internal GameProfile names. Team prefixes/colours may still apply to the above-head display name.
* The owner confirmed above-head names in the tested Windows single-player cases, including rejoin, tracking range and dimension round trips. That is scenario-limited evidence, not multiplayer or dedicated-server acceptance. TAB naming in the latest source still requires gameplay verification. If a client ever draws the old name, the fallback is a scoped client name-tag render override fed by the same map.

## Tests

`gradlew verifyNpcNames` (codec limits, malformed input, map lifecycle), `Rev6DiagSelfTest` (pure name resolution and diagnostic formatting), `backend/tests/test_milestone_static_contracts.py` (identity code untouched, client-only scoping, announce-before-spawn ordering), `python scripts/check-dist-separation.py` (no client class reachable from a dedicated server).

## Revision 6 compatibility note

The server-side Forge `TabListNameFormat` handler for C1 avatars is included. It only changes logical display-name presentation; internal `GameProfile` identity and real player entries are unchanged. Minecraft visual acceptance of this TAB behavior is still pending; standalone unit tests cannot prove it is rendered correctly.
