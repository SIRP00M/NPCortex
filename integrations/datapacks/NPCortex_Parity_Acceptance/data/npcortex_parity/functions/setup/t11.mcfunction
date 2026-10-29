tellraw @s {"text":"[T11] Container interaction through vanilla menus. Human oracle: open a chest, shift-click a stack into your inventory.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t11 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
clear @a[tag=npcortex_Parity]
setblock ~2 ~ ~ minecraft:chest{Items:[{Slot:0b,id:"minecraft:stick",Count:5b}]}
tellraw @s {"text":"A chest with 5 sticks stands 2 blocks away. Run /function npcortex_parity:setup/t11_stage2 (open, shift-click, close), then /function npcortex_parity:check/t11.","color":"gray"}
