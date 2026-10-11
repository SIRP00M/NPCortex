# Contributing to NPCortex

Thank you for improving NPCortex! Please keep the framework reusable by others.

1. Open an issue or discuss substantial changes before implementing them.
2. Put generic AI logic in `backend/`, Forge-specific code in `forge-mod/`, and mod integrations under `integrations/`.
3. Keep character definition data outside core code, in `character-packs/`.
4. Avoid mandatory paid APIs or additional Minecraft mods. Optional integrations must degrade gracefully.
5. Add tests for changed backend logic. Run: `python -m unittest discover -s backend/tests -v` (from repo root).
6. Never commit model weights, user chat histories, keys, tokens, or Minecraft world files.
7. Use English for source comments, documentation and initial character prompts. Localization can be added later.

Please note that `README.md` describes current capabilities separately from planned features. Contributions should maintain that distinction.

### Skin Manager extension points

The Forge client menu hook lives in `client/NPCortexScreenEvents.java`. Client-local
file import/validation is in `client/SkinFileManager.java` and hot GPU texture reload
is in `client/SkinTextureCache.java`. Appearance changes go through the
`network/UpdateSkinPacket.java` packet, verified against ownership and distance
on the Minecraft server thread. Avoid sending files, file paths or untrusted
backend-edit requests directly from the client. Test with
`./scripts/test-skin-manager.ps1` on Windows before running ForgeGradle.

## v0.5.4 generic plans

Keep the Python action vocabulary, `ActionCatalog.java`, and `WorldActions.execute()` synchronized. Immediate actions may be sequenced automatically; asynchronous actions MUST have completion/timeout logic in `ActionPlanRunner`. Never execute free-form model strings as commands. Add regression coverage for any new action and for plans exceeding eight steps. See `docs/generic-action-planner-v0.5.4.md`.
