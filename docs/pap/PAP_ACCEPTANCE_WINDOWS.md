# PAP place / use / chat acceptance on Windows (Owner procedure, PowerShell first)

Everything below is PENDING until you run it: no live client, dedicated server or MSPT evidence exists. Use a throw-away world whose folder name contains "Native" or "PAP". Nothing here touches your real worlds.

## 0. Prerequisites
- The mod build you want to test, the backend running on its normal database, a test world with an NPC (`Reika` below) created through the Manager.
- Your player UUID (`/data get entity @s UUID` is four ints; use the Manager or the log line `UUID of player ... is <uuid>`).

## 1. Install the arena and the allow-list
```powershell
# data pack (copies files only; refuses a world name without "Native"/"PAP" unless -AllowAnyName)
Copy-Item -Recurse integrations\datapacks\NPCortex_PAP_Acceptance "<saves>\<World>\datapacks\NPCortex_PAP_Acceptance"
# in game, standing where the arena may be built:   /reload   then   /function npcortex_pap:arena
# it prints the anchor x,y,z; use it below
scripts\pap\install-pap-allowlist.ps1 -ConfigDir <game>\config -PlayerUuid <your-uuid> -Actors Reika -Anchor <x>,<y>,<z>
```
The allow-list is re-read every second. Without it everything is denied (try step 3a first: it must be refused).

## 2. Give the NPC something to place
`/npcortex c1 give Reika <tx>` style commands exist; the simplest is to put blocks into the NPC inventory with the C1 inventory tools you already use (3-4 cobblestone in a hotbar slot that is NOT the selected one proves tool selection).

## 3. Scenarios (say the chat line in normal chat; look at the block first)
| # | Do | Expect (chat report) | Then |
|---|---|---|---|
| a | Delete the allow-list, say `Reika, place cobblestone` | "Denied: ... allow-list" | restore it |
| b | Look at the stone floor 2 blocks from the NPC, say `Reika, place cobblestone` | a "Preview: place minecraft:cobblestone ... hotbar slot N" message, no block yet | wait 10 s: still no block |
| c | Say `Reika, confirm` | NPC faces the cell, selects the slot, places; "Place complete: EFFECT_VERIFIED"; stack decreased by 1 | `/function npcortex_pap:check/placed` |
| d | Look at the lever, say `Reika, use the lever` then `confirm` | lever toggles; "Use complete: EFFECT_VERIFIED" | `/function npcortex_pap:check/lever` |
| e | Say `Reika, place tnt` (give her TNT) | "Denied ... built-in safety ceiling" even if you listed TNT | |
| f | Look at a door/chest, say `Reika, use that` | "lever and buttons only" | |
| g | Look at a log, `Reika, break that log`, `confirm` | break verified; then "Preview: pick up ..." (the chain only previews) | `Reika, yes` collects it |
| h | Start a preview, say `Reika, cancel` | "Cancelled"; a following `confirm` says "Nothing to confirm" | |
| i | Look at a block more than ~16 blocks away and ask for a place | refused or "walks to" (see the known limit below); nothing placed before your confirm | |
| j | Two NPCs, same block (Manager Brk on one, chat place on the other) | one is refused with CLAIM_CONFLICT | |
| k | Place, then `Save and Quit`, reload the world | the block and the inventory count are as before; no duplicate block; `Rec.` in the Manager shows nothing unresolved | |

## 4. Evidence
```powershell
scripts\pap\verify-pap-live.ps1 -Db <your backend sqlite file>     # read-only; PASS = nothing stuck
```
Send me: the chat reports, the script output, the Forge `latest.log` lines starting with `NPCortex native:` / `NPCortex player task:`.

## 5. Known limits you will see
- Walking to a far target goes through the accepted C2 goal pipeline, which asks the model backend for one `/decide` per move. With a model attached it can work; without one the walk times out and is cancelled with nothing started. A Qwen-free deterministic walk needs an Owner decision (see `PAP_HANDOFF.md`).
- Chat is the primary path; the Manager has Plc and Use buttons (Preview then Confirm). The layout of the two new buttons was not seen on a real client.
- The ServerChatEvent adapter was tested only through its inner handler in headless GameTests.

## 6. Automated suites (no game needed)
```powershell
scripts\pap\run-pap-gametests.ps1          # real avatar + real Python ledger, 14 GameTests, then a read-only ledger audit
python scripts\pap\pap_g2_mutation_probe.py ; python scripts\pap\pap_g0_mutation_probe.py
```
