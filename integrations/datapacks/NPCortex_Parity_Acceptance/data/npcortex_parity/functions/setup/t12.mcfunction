tellraw @s {"text":"[T12] Death, drops, keepInventory, respawn. Human oracle: die with keepInventory=false -> items drop at the death spot; respawn with empty inventory.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t12 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
gamerule keepInventory false
function npcortex_parity:lib/spawn
tellraw @s {"text":"After 5 s run /function npcortex_parity:setup/t12_stage2 (second step; commands that need a player source cannot be scheduled).","color":"yellow"}
tellraw @s {"text":"After 4.5 s the NPC is given 3 oak logs and killed. Check after 5 s: logs must lie on the ground; then /npcortex c1 respawn Parity and /function npcortex_parity:check/t12b (inventory empty).","color":"gray"}
