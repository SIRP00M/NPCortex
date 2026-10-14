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

The Gradle wrapper is already included. Use `scripts/setup-forge-wrapper.ps1` **only if wrapper files are missing**; it downloads the official Forge 47.4.10 MDK and verifies its published SHA-1. Use JDK 17, then `.\scripts\phase3-check.ps1` and `.\scripts\dev-forge.ps1 -Task runClient` from the root. See [Phase 3](phase-3-forge.md).

This scaffold has not been built against a live ForgeGradle environment in the generation environment. Treat the Java command as a starting implementation and report build issues via GitHub issues; do not claim it is battle tested.

## Publishing

No remote GitHub repository is created by downloading this zip. You may publish under your own GitHub organization/account. Do not commit `data/`, `.env`, world saves, tokens or model weights.

## A2 configurable safety limits

All variables are optional OS environment variables; `.env.example` is documentation and is not loaded automatically.

| Variable | Default | Meaning |
|---|---:|---|
| `NPCORTEX_MAX_SESSIONS` | 128 | Maximum retained conversations |
| `NPCORTEX_SESSION_TTL_SECONDS` | 1800 | Idle history expiration (lazy cleanup) |
| `NPCORTEX_MAX_SESSION_REQUESTS` | 2 | In-flight + waiting requests per conversation |
| `NPCORTEX_SESSION_WAIT_SECONDS` | 20 | Maximum wait to acquire same-session turn |
| `NPCORTEX_MAX_MODEL_REQUESTS` | 4 | Maximum simultaneous provider calls |
| `NPCORTEX_MODEL_WAIT_SECONDS` | 15 | Maximum wait for an Ollama call slot |

Capacity limits apply to all HTTP clients of a single backend process. A queued same-session request may return HTTP 429 rather than wait indefinitely. Concurrent conversations may return HTTP 503 when the local model is saturated. Server errors remain JSON objects containing `error`; A2 adds a stable `code` string. See `docs/api.md`.

## Verification

```powershell
python -m unittest discover -s backend/tests -v
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\dev-forge.ps1 -Task runClient
```

See [A2 verification and manual in-game checklist](a2-verification.md). Python tests do not prove Forge build or Minecraft runtime success.
