tellraw @s {"text":"[T08] Block placement / interaction through vanilla useItemOn (PAP).","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t08 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
setblock ~6 ~ ~2 minecraft:lever[face=floor,facing=north,powered=false]
tellraw @s {"text":"Give Parity cobblestone, look at the floor +4,+1 and say: Parity, place cobblestone -> confirm. Then: Parity, use the lever -> confirm. /function npcortex_parity:check/t08","color":"gray"}
