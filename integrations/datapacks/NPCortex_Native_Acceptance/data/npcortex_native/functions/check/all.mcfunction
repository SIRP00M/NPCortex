# Runs every WORLD-side check once. These lines are evidence of the WORLD only; the ledger side is read by scripts\native-acceptance.ps1 -Action Report.
function npcortex_native:check/s1_break
function npcortex_native:check/s2_adjacent
function npcortex_native:check/s3_contention
function npcortex_native:check/s4_collateral
function npcortex_native:check/denied
function npcortex_native:check/row
function npcortex_native:check/items_left
tellraw @a {"text":"[NPCortex native] World checks done. Now run on Windows: scripts\\native-acceptance.ps1 -Action Report","color":"gold"}
