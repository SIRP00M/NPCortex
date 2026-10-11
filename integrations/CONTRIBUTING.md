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
