# Development guide

## Python

From repo root (Windows PowerShell):

```powershell
.\scripts\dev-backend.ps1
```

On macOS/Linux (or regular shell with PYTHONPATH):

```sh
PYTHONPATH=backend/src python -m npcortex_backend
```

Run unit tests:

```powershell
python -m unittest discover -s backend/tests -v
```

Optional variables from `backend/.env.example` are read from the running shell environment, not auto-loaded from a `.env` file. Example:

```powershell
$env:NPCORTEX_MODEL = 'qwen3:8b'
$env:NPCORTEX_PORT = '8765'
```

## Forge

Run `scripts/setup-forge-wrapper.ps1` from root first. This downloads the official Forge 47.4.10 MDK, verifies published SHA-1 and copies the wrapper files. Use JDK 17, then `.\scripts\phase3-check.ps1` and `.\scripts\dev-forge.ps1 -Task runClient` from the root. See [Phase 3](phase-3-forge.md).

This scaffold has not been built against a live ForgeGradle environment in the generation environment. Treat the Java command as a starting implementation and report build issues via GitHub issues; do not claim it is battle tested.

## Publishing

No remote GitHub repository is created by downloading this zip. You may publish under your own GitHub organization/account. Do not commit `data/`, `.env`, world saves, tokens or model weights.
