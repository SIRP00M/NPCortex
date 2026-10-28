# start ALL
execute unless score #ready g5 matches 1 run tellraw @s {"text":"[G5] run npcortex_g5:setup first","color":"red"}
execute if score #ready g5 matches 1 unless score #running g5 matches 1 run say [G5] START ALL
# p00 decides the mode
execute if score #ready g5 matches 1 unless score #running g5 matches 1 run function npcortex_g5:lib/begin_all
