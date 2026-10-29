tellraw @s {"text":"[T19] Moving vanilla chunk ring. Walk the NPC >= 150 blocks with C2 (/npcortex c2 navlong Parity x y z); chunks 80 blocks around the NEW position must be loaded. Operator stays FAR from the NPC.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t19 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
tellraw @s {"text":"Walk Parity 150+ blocks (npcortex c2 navlong Parity <x> <y> <z>), keep yourself >200 blocks from it, then /function npcortex_parity:check/t19 . Needs a pre-generated or generating path.","color":"gray"}
