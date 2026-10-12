# NPCortex v0.7.2-alpha — Select NPC Autonomy by Character ID, UUID, or GUI

NPCortex autonomy remains **opt-in per individual companion** and is persisted in each entity's Minecraft world save. No Python/Ollama API changes are required for this update.

## In-game command interface

In a test world with cheats enabled (or as an operator on a server):

```mcfunction
/npcortex auto list
/npcortex auto on yui
/npcortex auto off ai
/npcortex auto status yui
```

The character IDs above match the JSON Character Pack `id`, not the display name. The existing no-argument commands still control the **nearest owned companion within 12 blocks**:

```mcfunction
/npcortex auto on
/npcortex auto off
/npcortex auto status
```

`/npcortex auto list` shows each loaded owned NPC's character ID, **full entity UUID**, display name, dimension and on/off status. If multiple spawned companions share the same Character ID, use the full entity UUID from the list to choose exactly which one:

```mcfunction
/npcortex auto on 00000000-0000-0000-0000-000000000001
/npcortex auto status 00000000-0000-0000-0000-000000000001
/npcortex auto off 00000000-0000-0000-0000-000000000001
```

These UUIDs are examples, **not real NPC IDs**. Abbreviated eight-character UUID prefixes are displayed in the UI for recognition but commands require the full UUID for disambiguation.

An ambiguous ID fails safely and does **not** change any NPC. Commands and the GUI only manage **currently loaded** companions owned by the requesting player, including loaded companions in other dimensions. An NPC in an unloaded chunk is not shown until its chunk loads; this update does not force-load chunks.

## In-game GUI

Open **ESC → NPCortex Manager → Manage NPC Autonomy**. The GUI requests the owned NPC roster from the Minecraft server and displays up to six entries per page. Select an entry, then click **Enable Autonomy** or **Disable Autonomy**; **Refresh** re-requests the list. The server verifies ownership and entity existence before every change. Changes are confirmed in chat and reflected in the GUI after the server responds.

The GUI intentionally does not offer "Enable All" in this alpha release. Turning on autonomy for multiple NPCs simultaneously may increase detection and Ollama reaction requests. Owner proximity and other safety gates from Phase 8 are **unchanged**: toggling autonomy ON does not mean a far-away NPC continuously senses threats.

## Windows PowerShell — install from update-only ZIP

Close Minecraft first and back up your working repository and world save. If downloaded to Downloads:

```powershell
cd F:\AI\NPCortex
.\scripts\backup-memory.ps1
git status
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_AutonomyManager_Update_Only_v0.7.2-alpha.zip" -DestinationPath "F:\AI\NPCortex" -Force
.\scripts\autonomy-manager-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

This update changes Forge Java code (commands, GUI and networking) but does not change the Python backend. Keep the existing backend running when testing AI speech. To allow locally written/trusted `.ps1` scripts, you may use `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` if no higher-priority policy blocks it. Review scripts before executing them.

## Test checklist

1. Spawn `yui`, `ai`, and another `yui` in a test world.
2. `/npcortex auto list` should show all three with unique UUIDs.
3. `/npcortex auto on ai` toggles Ai only.
4. `/npcortex auto off yui` refuses ambiguous IDs when there are two loaded Yuis.
5. `/npcortex auto on <FULL-YUI-UUID>` changes only the selected Yui.
6. In ESC → NPCortex Manager, select a Yui by UUID prefix, disable it, and verify the roster changes to OFF.
7. Re-enter the world and confirm the toggle persists.
8. Ensure AI threat warnings, Follow/Stay and character skins still function normally.

> Validation: the pure-Java selector can be regression-tested without Forge; full ForgeGradle compilation and live Minecraft/GUI operation must still be confirmed on a machine with Forge dependencies.
