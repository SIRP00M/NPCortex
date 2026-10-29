tellraw @s {"text":"[T14] Advancements. Human oracle: mining stone with a pickaxe grants story/mine_stone.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t14 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
setblock ~4 ~ ~1 minecraft:stone
tellraw @s {"text":"Allow minecraft:stone in the allow-list, give Parity a pickaxe, look at the stone and say: Parity, break that stone -> confirm -> pickup (the cobblestone enters the inventory). Then /function npcortex_parity:check/t14","color":"gray"}
