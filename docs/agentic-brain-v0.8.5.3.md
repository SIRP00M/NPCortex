# NPCortex v0.8.5.3-alpha — Backend Decision Diagnostics & Observation Contract Fix

This is a **Python-only hotfix** on top of v0.8.5.2-alpha. The Minecraft Forge
mod remains `0.8.5.1-alpha`. No JAR rebuild is necessary.

## Symptom

In-game: `[Agentic task paused: Qwen unavailable or invalid structured decision; progress preserved.]`

This is a **generic** error produced by Java when the `/v1/brain/step` request
fails or the returned payload is invalid. It does **not** identify a model
failure by itself. Causes include HTTP 400 (world state validation), HTTP 502
(Ollama/invalid Qwen decision), timeout, and Java response parsing.

## Changes

- **Fix observation contract mismatch**: Forge collects dropped items in an
  axis-aligned bounding box inflated by 12 blocks. Such a box can contain
  entities at a Euclidean distance greater than 16 blocks. Python's 16-block
  cap erroneously rejected a legitimate Java snapshot as HTTP 400. Accept
  distances up to 32 while still bounding the input. Minecraft continues to
  enforce its pickup/nav range and entity identity; this does not expand reach.
- **Observation-scoped JSON schema**: restrict the LLM's allowed `target`
  values to the exact current `bN` and `dN` references plus `""`. Block or
  drop tools are omitted if the world snapshot has no matching targets. Qwen
  still chooses the skill. A second strict validator still rejects any
  fabricated or mismatched target, and Java retains all safety checks.
- **Actionable backend logs**: incoming invalid world state prints
  `Brain request rejected BEFORE Qwen: <reason>`; malformed Qwen selections
  print `Brain decision rejected: <reason>; tool=... target=...`. Full world
  observations, chat histories and memory are NOT logged.
- **No untracked retries**: exactly one model request is made per Java brain
  request. Existing task budgets, safety, owner permissions and SQLite memory
  remain unchanged.
- **Read-only smoke test**: `scripts/diagnose-brain-v0853.ps1` invokes a
  synthetic `/v1/brain/step` with no Minecraft world modifications.

## Install

1. Stop the Python backend using Ctrl+C. Do not run the installer while its
   Python process is active.
2. Extract `NPCortex_v0.8.5.3-alpha_UpdateOnly.zip` into a temporary folder.
3. Run the install script **from that extracted temporary folder**:

```powershell
.\scripts\install-v0853.ps1 `
  -UpdateZip 'C:\Downloads\NPCortex_v0.8.5.3-alpha_UpdateOnly.zip' `
  -ProjectRoot 'F:\AI\NPCortex'
```

4. Run backend tests and restart backend. **Do not rebuild Forge for this patch**:

```powershell
cd F:\AI\NPCortex
.\scripts\test-agentic-v085.ps1
.\scripts\dev-backend.ps1
```

5. In a second PowerShell, check the synthetic decision path:

```powershell
cd F:\AI\NPCortex
.\scripts\diagnose-brain-v0853.ps1
```

6. In-game: cancel the paused task, try `gather minecraft:oak_log 1` again,
   check `/npcortex task status` and `/npcortex inventory`. If it still pauses,
   report the **Python terminal lines directly above the HTTP status**, plus
   the in-game task status. The exact HTTP status (400/502/timeout) matters.

## Scope of verification

Python automated tests can verify validation, schema selection, HTTP API and
error reporting. They do not verify a real Ollama process, Java Forge build,
Minecraft pathfinding, in-world mining or pickup. Test those separately.
