# NPCortex v0.8.0-alpha — Forge Compile Hotfix

This patch fixes two Java compilation errors in `WorldBlockActions.java`:
- `ClipContext` import changed to `net.minecraft.world.level.ClipContext` (Minecraft Forge 1.20.1).
- Disambiguated `Level.getEntities(...)` by explicitly typing null as `net.minecraft.world.entity.Entity`.

Install with PowerShell while Minecraft is closed:

```powershell
cd F:\AI\NPCortex
Expand-Archive -Path "$env:USERPROFILE\Downloads\NPCortex_v0.8.0_CompileFix_Update_Only.zip" -DestinationPath "F:\AI\NPCortex" -Force
.\scripts\dev-forge.ps1 -Task build
```

There are no changes to the backend, world save, character packs, or config.
Four deprecation warnings in other classes may remain; they are not compile errors.
Forge build is not run in the packaging environment and must be verified on your Windows computer.
