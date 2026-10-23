# NPCortex C-F1 D+ — Automatic Local Authentication (Windows Checklist)

This is **local C-F1 diagnostic bridge authentication only**, not Ollama API access, Qwen credentials, network-wide authentication, or permission to execute world actions.

## Setup — no manual token

1. Back up your C-F1 world / backend SQLite before using a new build.
2. Stop old backend/Minecraft processes. In PowerShell at the repository root:

```powershell
# Window A — Backend (same Windows user who starts Minecraft)
$env:PYTHONPATH = (Resolve-Path .\backend\src).Path
Remove-Item Env:NPCORTEX_CPLUS_F1_TOKEN -ErrorAction SilentlyContinue
python -m npcortex_backend
```

Backend prints `C-F1 local diagnostic credential: new private file` on first startup, or `existing private file` on later starts. It **never prints the token**. The private file lives at `%LOCALAPPDATA%\NPCortex\bridge\cplus-f1.secret` by default (outside repository).

```powershell
# Window B — Minecraft, same Windows user
Remove-Item Env:NPCORTEX_CPLUS_F1_TOKEN -ErrorAction SilentlyContinue
.\scripts\dev-forge.ps1 -Task runClient
```

3. In Minecraft, as a game operator:

```mcfunction
/npcortex c1 spawn yui
/npcortex debug cplus inspect yui
/npcortex debug cplus probe yui
/npcortex debug cplus sync yui
/npcortex debug cplus syncstatus yui
```

**Pass:** `sync` returns durable ACK / cursor progress and no auth error. `probe` is synthetic: no world, inventory, or motion effects. Sync again: event not duplicated. No arena needed.

4. Close both programs. Restart backend and Minecraft: secret should be unchanged; previously ACKed events remain ACKed.
5. Stop only Backend; try `sync`: HOLD/pending, not Success; restart Backend with same DB and retry.
6. Run `scripts/test-cplus-f1-auto-local-auth.ps1`, `scripts/test-cplus-f1-dplus-transaction-bridge.ps1` and `scripts/dev-forge.ps1 -Task build` on Windows Temurin 17 / Python 3.13.
7. Check Legacy C1/C2 Follow (including Creative flying), Inventory, Navigate and Save/Rejoin; no code changed in these modules.

## Security / recovery

- The backend creates the secret in the current user's private profile. Forge reads it; Forge never generates or changes it.
- An explicit `NPCORTEX_CPLUS_F1_TOKEN` remains a supported manual override (same value both processes). Remove old overrides from **both** shells if switching to automatic mode.
- An optional absolute `NPCORTEX_CPLUS_F1_SECRET_FILE` path can be used by both processes when separate profiles are intentionally configured; keep it private. Avoid synced/shared folders, remote access, or Git tracking.
- A missing, unreadable, partial, or insecure secret fails closed. If compromised, **stop both programs first**, preserve incident evidence, then rotate/delete with operator intent; backend will create a new secret on next launch. Never paste your secret into logs/issues.
- Only C-F1 diagnostic endpoints use this token; other legacy HTTP routes remain unauthenticated. Keep the entire backend bound to localhost; this is not remote-server authentication.
- No automatic world effect retry, no Saga, no block/item mutation, and no permission upgrade is introduced.

## Remaining quality gates

Windows Forge build, Windows ACL behavior, live game sync, real hardware crash/power-loss behavior, multi-NPC MSPT and all production operational acceptance need owner-run verification. Add logs with secrets redacted.
