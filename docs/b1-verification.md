# B1 — Windows Build & Integration Test Checklist

## Prerequisites

- Windows, Python 3.10+, JDK **17**, Forge 1.20.1 / Forge 47.4.10.
- Ollama running with `qwen3:8b` available (`ollama list`).
- Extract FULL ZIP outside Program Files, e.g. `C:\path\to\NPCortex-B1`.
- **Back up `backend\data\npcortex.sqlite3`** before changing builds or storage
  settings. Do not store personal secrets in NPC messages. There is no authentication.

From repo root in PowerShell:

```powershell
python -m unittest discover -s backend/tests -v
.\scripts\phase3-check.ps1
.\scripts\dev-forge.ps1 -Task build
```

Use another PowerShell for the real backend:

```powershell
ollama list
.\scripts\dev-backend.ps1
```

Check health from a separate PowerShell:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health
```

## B1 actor identity smoke test (real Qwen)

This intentionally creates **two different Yui actors** in a new example world.
Run once, then copy printed IDs for the restart test. All calls are to localhost.

```powershell
$base = 'http://127.0.0.1:8765'
function Post-Npc($route, $obj) {
    Invoke-RestMethod -Uri ($base + $route) -Method Post `
      -ContentType 'application/json' -Body ($obj | ConvertTo-Json -Depth 8)
}
$world = Post-Npc '/v1/worlds' @{ name = 'B1 Test World' }
$yui1 = Post-Npc '/v1/actors' @{ world_id = $world.world_id; dimension_id = 'minecraft:overworld'; character_id = 'yui' }
$yui2 = Post-Npc '/v1/actors' @{ world_id = $world.world_id; dimension_id = 'minecraft:overworld'; character_id = 'yui' }
"World ID: $($world.world_id)"
"Yui 1 ID: $($yui1.actor_id)"
"Yui 2 ID: $($yui2.actor_id)"
function Chat-Npc($actor, $text) {
    Post-Npc '/v1/actor-chat' @{
      world_id = $world.world_id; dimension_id = 'minecraft:overworld'
      actor_id = $actor.actor_id; player_id = 'b1-tester'; message = $text
    }
}
$r1 = Chat-Npc $yui1 'My secret nickname for you is Starfish. Please remember it.'
$r2 = Chat-Npc $yui2 'Hello, what is my nickname for you?'
$r3 = Chat-Npc $yui1 'What nickname did I give you?'
$r1; $r2; $r3
"First conversation: $($r1.conversation_id)"
"Second conversation: $($r2.conversation_id)"
Invoke-RestMethod ($base + '/v1/conversations/' + $r1.conversation_id)
```

**Machine-verifiable expected outcomes:** `$yui1.actor_id` and `$yui2.actor_id`
are different; `$r1.conversation_id` and `$r2.conversation_id` are different;
`GET /v1/conversations/...` returns only that dialogue's saved messages. Qwen's
verbal memory and truthfulness are stochastic; judge data isolation using the
transcript endpoint, not solely by the model's wording.

**Restart persistence test:** Stop the backend (`Ctrl+C`) and start it again.
With `$world`, `$yui1`, and the same PowerShell session still available, call
`Chat-Npc $yui1 'What nickname did I give you?'`. Verify the ID is unchanged;
`GET /v1/conversations/<ID>` shows messages from *before and after* restart.
If starting a new PowerShell, restore `$world` and `$yui1` using the printed UUIDs
as hashtables (`@{world_id='...'; ...}` and `@{actor_id='...'}` respectively).

Test errors: invalid `actor_id` (400), nonexistent actor UUID (404), correct actor
with wrong world or dimension (409), invalid character pack (400). Stop Ollama
and test 502 response with no bogus saved assistant exchange; restart Ollama,
retry, and verify recovery.

## Existing Minecraft compatibility smoke test

Run `runClient` with the backend online:

```powershell
.\scripts\dev-forge.ps1 -Task runClient
```

In a single-player test world with Cheats on:

```text
/npcortex status
/npcortex ask yui Hello Yui!
/npcortex ask guide How are you?
/npcortex ask invalid_character Hello!
```

B1 does **not** spawn NPCs or add a new Minecraft command; the above tests
A2 compatibility only. B1's new actor-chat must be exercised through the
backend API until Forge entity identity binding is implemented.

## Quality Gates

- [ ] Backend Python tests passed on your Windows machine (record count/log)
- [ ] JDK 17 Forge `build` passed
- [ ] Real local Ollama/Qwen actor-chat returned coherent replies
- [ ] Two actors using the same character pack have isolated transcripts
- [ ] Backend restart preserves actor and conversation identity/history
- [ ] Invalid/mismatched identities rejected; provider recovery works
- [ ] Minecraft legacy `/npcortex ask` continues to work without crash
- [ ] No GitHub Push until required gates pass

**Remaining limitations:** SQLite is local and unauthenticated; the backend is
single-process only, no shared multi-server writes; no dynamic dimension
migration, entity spawn, NPC-to-NPC dialogue, autonomous behavior, social goals,
semantic memory, or model-based validation of alleged world facts.
