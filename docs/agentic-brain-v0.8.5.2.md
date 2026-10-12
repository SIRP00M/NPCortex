# NPCortex v0.8.5.2-alpha — Backend structured-tool hotfix

**Applies on top of v0.8.5.1-alpha.** This is a Python Backend-only hotfix.
The Forge mod JAR remains the existing v0.8.5.1-alpha build; no Forge files or
Minecraft save files are changed. The archive/release uses the name `v0.8.5.2`
for tracking this hotfix without changing Minecraft mod binary version.

## Reproduced cause

The backend reported:

```
ValueError: Tool does not accept target
POST /v1/brain/step HTTP/1.1 502
```

Qwen emitted a syntactically valid response with a supported targetless tool
(`pickup`, `descend`, `wait`, `finish`, or `pause`) but filled in the required
`target` field. The earlier validator rejected this without running any tool.
The server paused on the 502. A 502 here does NOT mean Ollama was unreachable.

## Behavior of this change

- Keep `target` mandatory in the uniform Ollama JSON schema so the existing API
  contract is unchanged; explain more explicitly when it must be empty.
- Canonicalize the *unused* `target` argument to `""` for the five targetless
  tools. Example: `{ "tool":"pickup", "target":"d0", ... }` becomes
  `{ "tool":"pickup", "target":"", ... }`. No additional inference is needed.
- Continue to reject unobserved block refs, fake drop refs, unknown tool names,
  extra JSON fields, oversized targets, and exceeded planning budgets.
- Minecraft (Forge) still verifies safety, rights, physics, inventory, and
  success. `finish` still cannot claim success before the bag count is met.
- No new hardcoded mining/gathering strategy is added.

This hotfix **only resolves this one response-validation issue**. It does not
claim to fix every navigation or pickup failure, or to guarantee model choices
will be good. A 502 from a genuinely invented target ref remains possible and
is deliberate fail-closed behavior.

## Install on Windows

Shut down the Python Backend before installing. Minecraft/Forge can remain
closed or running for this Python-only change, but restart the backend.

Extract the update ZIP somewhere temporary, then run its installer:

```powershell
.\scripts\install-v0852.ps1 `
  -UpdateZip 'C:\Downloads\NPCortex_v0.8.5.2-alpha_UpdateOnly.zip' `
  -ProjectRoot 'F:\AI\NPCortex'

cd F:\AI\NPCortex
.\scripts\test-agentic-v085.ps1
.\scripts\dev-backend.ps1
```

The installer verifies an existing `0.8.5.1-alpha` layout, checks the patch ZIP
allowlist, and saves copies of replaced files in `backups/pre-v0852-*` before
writing. It never updates character packs, `.env`, SQLite memory, or game saves.

**No Forge rebuild or mod JAR replacement is necessary** for this Backend-only
hotfix. If you also changed Java sources locally, build/test those separately.

## In-game retest

1. Cancel any paused old agentic task, then start a fresh simple test:
   `/npcortex task gather minecraft:oak_log 1`.
2. Confirm the backend serves `/v1/brain/step` with HTTP 200 for a model
   `pickup` decision even if the model added an unused short target.
3. Verify actual bag storage with `/npcortex inventory`, and
   `/npcortex task status` for `chosen=`, `stored=` and `last=`.
4. Retest `/npcortex task gather minecraft:oak_log 6` on flat terrain, then
   the original hillside in a backed-up world. If navigation still fails,
   gather the exact status output and the most recent Java action feedback.

## Test boundaries

- Python Backend/unit/API tests: run in the package creation environment;
  results reported separately in chat.
- Java policy test: unchanged Minecraft safety policy; can run standalone.
- Forge build: NOT RUN as part of this Backend-only patch.
- Local Qwen inference: NOT RUN here.
- In-game Minecraft: NOT RUN here.
