# NPCortex In-Game Manager — v0.4.2-alpha

## Why skins previously did not show up

NPCortex renders `appearance.skin` from the **current Minecraft client's** folder:

`<gameDirectory>/config/npcortex/skins/<skinId>.png`

This is not the `character-packs/<id>/skin.png` file read by the Python backend. The original renderer also cached a missing PNG until client restart. The updated cache automatically retries missing files and now supports an explicit hot reload.

## In-game workflow (Minecraft Forge 1.20.1)

1. Run Minecraft using the updated Forge mod. Enter a **test world**.
2. Press **Esc → NPCortex Manager**. A button is added at the top right of the pause menu via Forge `ScreenEvent.Init.Post`, without replacing the pause screen.
3. Use **Select nearby NPC** to cycle through NPCortex companions within 12 blocks. The manager fills in the selected companion's current skin ID and wide/slim model.
4. Enter a lowercase skin ID, e.g. `yui` or `forest_guard`.
5. Click **Import PNG...** to choose a local file, or drag a single PNG from File Explorer onto the manager screen.
6. A 64×64 Minecraft Java Edition PNG, up to 1 MiB, is copied to the *correct active client's* `config/npcortex/skins/<id>.png` path. File validity is checked first. Only explicit actions you take copy a file. No image file is sent to AI or server.
7. Click **Reload Skin** to immediately discard the old GPU texture and reload the PNG. You do **not** have to restart the Minecraft client.
8. Click **Apply to selected NPC** to set the chosen skin ID / wide-or-slim arm model. The Forge server validates ownership and distance before accepting it. The override is saved with the companion's world save.
9. **Open Skins Folder** opens the exact folder used by the active client. **Open Character Packs** can open `<repo>/character-packs/` from the *development client* only; a CurseForge or remote client has no safe way to infer where the Python backend stores these files.

### Important distinctions

- **Skin import is local only**. It does not update the Python `character.json` or automatically install the skin for other players. For an NPC to use a given skin by default on spawn, keep `appearance.skin` and `appearance.model` in its backend Character Pack aligned with that skin. `/npcortex sync` re-applies that backend profile and **overwrites an in-game appearance override**.
- **Edit personality/name** in backend `character-packs/<id>/character.json` for now. This manager does not yet expose a backend-authorized editor or character creation; such features need safe server-side write permissions.
- In dedicated multiplayer, each client still needs a matching PNG installed locally. The server accepts only a skin ID and arm-type boolean, not arbitrary client file paths or PNG bytes.
- The manager shows a 64×64 **skin atlas preview**, not a posed 3D avatar. A default skin is used if the PNG is missing or invalid.
- The native AWT file chooser may appear behind Minecraft on some fullscreen systems. Use windowed mode, or the built-in **drag and drop** fallback.
- Back up worlds before testing source upgrades. Protocol version incremented to 3; client and server must use matching NPCortex versions.

## Windows PowerShell: update only

Download `NPCortex_InGameManager_Update_Only_v0.4.2-alpha.zip` to Downloads. Close Minecraft before updating:

```powershell
cd F:\AI\NPCortex
git status
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_InGameManager_Update_Only_v0.4.2-alpha.zip" -DestinationPath "F:\AI\NPCortex" -Force
```

For execution-policy restrictions, set it once for the current account (if allowed):

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

Then run:

```powershell
.\scripts\manager-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

You do not need to rebuild or restart the Python backend for this version because the HTTP API has not changed. If you're using a separate CurseForge profile, put the newly built JAR from `forge-mod/build/libs/` in that instance's `mods/` folder and remove the previous NPCortex JAR before starting Minecraft. **Do not install both JARs simultaneously.**

## Troubleshooting

- Skin missing: press **Open Skins Folder** and verify that `<skinId>.png` really exists in this exact directory, and is 64×64. Check `logs/latest.log` for `NPCortex: skin ...` and `NPCortex: loaded skin`.
- Skin loaded but appears generic: bundled Yui and Guide skins are placeholders. Import your own approved 64×64 skin.
- Skin changes but `/npcortex sync` reverts it: update `appearance.skin` / `appearance.model` in the backend JSON; sync reapplies those authoritative values.
- Button absent: rebuild and launch the patched Forge client. Check the Mods screen for NPCortex and confirm you're in the in-game Pause Screen, not the Main Menu.
- Apply not changing the NPC: it must be **your** NPC and you must be within 12 blocks; custom skin is on *your client*, not on the server.
- PNG picker hidden in fullscreen: press Alt+Tab or switch to Windowed mode, or drag PNG onto the Manager screen.
- Small game window/GUI scale: adjust GUI Scale so the entire manager fits in the display.

## Verification

The pure Java skin validation and path traversal unit checks run with JDK 17+ using:

```powershell
.\scripts\test-skin-manager.ps1
```

The Python tests still run separately (`python -m unittest discover -s backend/tests`). **Full Forge compilation and in-game rendering must be verified on a Windows machine with ForgeGradle dependencies.**
