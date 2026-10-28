# M03 snapshot every #per polls (position + journal state = evidence independent of selectors).
scoreboard players operation #m g5 = #tick g5
scoreboard players operation #m g5 %= #per g5
execute if score #m g5 matches 0 run function npcortex_g5:lib/snap
