# Phase 4: Spawnable companions & local dialogue (v0.2.0-alpha)

This is an **unverified Forge source update** extending the previously tested Phase 3 chat bridge. It must still be built and exercised in Minecraft Forge 1.20.1 with Java 17. Back up your save and use a **separate clean Forge development instance** before putting this build into a large modpack.

## Windows installation over an existing NPCortex repo

Download the `NPCortex_Phase4_Update_Only.zip` archive and extract **its contents** into the same repository root as `forge-mod/`, `backend/`, and `scripts/`. Overwrite files when prompted. Do not extract it into `forge-mod/` directly. A full-repo ZIP is provided too.

The Python API is unchanged; your existing `scripts/dev-backend.ps1` should continue to work. Do not delete existing character packs.

### Windows PowerShell commands

```powershell
# Enable locally authored scripts for your user, once, if your system policy allows:
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned

cd F:\AI\NPCortex
java -version
javac -version

# Terminal 1: leave running (Python backend + Ollama)
.\scripts\dev-backend.ps1

# Terminal 2:
.\scripts\phase4-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

If your PowerShell is restricted by machine/group policy, do not attempt to override your organization's security policy. For your own PC, the one-process alternative is:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\dev-forge.ps1 -Task build
```

## In-game smoke test (Allow Cheats enabled)

1. Enter a **new test world**.
2. Run `/npcortex status` to check the Python backend is running.
3. Run `/npcortex spawn yui`. A **generic player-shaped companion** should appear beside you.
4. Right-click the NPC to open the NPCortex dialogue screen.
5. Ask `Hey Yui, can you hear me?` and click Send. After inference, the reply should display in the screen.
6. Close the screen, walk away; NPC should try to follow using pathfinding while you stay within about 32 blocks. No teleport recovery yet.
7. Run `/npcortex stay` while within 12 blocks, walk away, and then `/npcortex follow` to resume.
8. Stop and reopen the test world. The NPC's character ID, ownership and following mode should persist in world data.
9. Confirm the old `/npcortex ask yui Hi!` developer bridge still works.

### Available commands

| Command | Description |
|---|---|
| `/npcortex status` | Backend health (does not prove Ollama model readiness). |
| `/npcortex ask yui Hello!` | Original direct command-to-LLM integration test. |
| `/npcortex spawn yui` | Spawn a character-linked NPC near the player. |
| `/npcortex follow` | Nearest owned companion within 12 blocks follows you. |
| `/npcortex stay` | Nearest owned companion within 12 blocks stops moving. |

All commands currently require permission level 2 / cheats. On a multiplayer server, the backend is executed on the server machine; install the mod on client and server and keep the backend private.

## Design decisions

- The `character_id` is stored and synchronized on the entity. `NPCortex Companion` is not hard-coded to any one character.
- Right-click is server-authoritative. The server tells the client to open the screen via a Forge packet.
- Conversation packets carry **entity ID + message**, not a trusted character ID. The server checks the entity type and a maximum 8-block interaction range.
- The HTTP request runs off the Minecraft server tick thread. Replies are sent via another packet. Only one in-flight request is allowed per player; chat input is limited to 400 characters.
- Owner UUID is saved in the entity and used only by the follow/stay logic; other players currently can talk to the NPC if nearby.
- NPC movement is ordinary Minecraft navigation, **not an LLM action**. Generated text is not interpreted as a command. The AI may fictionalize actions in dialogue, but it cannot actually carry them out yet.

## Not implemented in v0.2.0-alpha

- JSON-driven custom player skin, items/inventory, combat, TaCZ, relationship state, persistent LLM conversation history, robust re-identification, long-distance teleport recovery, per-entity LLM session IDs, GUI history scrolling, server security/authentication, or character display-name/skin loading from the Python packs.
- NPC uses Minecraft default player skin as a placeholder. Character pack decides the chat personality only.
- NPC follows its owner only while in the same dimension and not farther than about 32 blocks. Complex terrain can defeat the pathfinder. The model is shared between characters; each player's chat history is separated by player ID + character ID in the backend, not by entity UUID.

## If compilation fails

Run:

```powershell
cd F:\AI\NPCortex\forge-mod
.\gradlew.bat clean build --stacktrace
```

Copy the **first** `error:` block, the associated source-file/line, and the bottom `BUILD FAILED` section. This Java source has been reviewed against Forge 1.20.1 API references but has **not been compiled in this environment** because ForgeGradle dependencies are not available here. If the Gradle wrapper download fails, retry the same `scripts/dev-forge.ps1` workflow after checking networking and `java -version`.

## Rollback

Restore the previous `NPCortex_Phase3_v0.1.1-alpha.zip` source and rebuild. **Do not open test worlds that contain new companions without backing them up first.** Existing new NPCs may be omitted from the world if the entity registry is absent in the older mod.
