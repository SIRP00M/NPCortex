# Coarse (1 block) movement tracking per 10-tick poll.
scoreboard players set #moving g5 0
execute unless score #px g5 = #lpx g5 run scoreboard players set #moving g5 1
execute unless score #pz g5 = #lpz g5 run scoreboard players set #moving g5 1
execute if score #moving g5 matches 1 run scoreboard players set #moved g5 1
execute if score #moving g5 matches 1 run scoreboard players set #still g5 0
execute if score #moving g5 matches 0 run scoreboard players add #still g5 1
scoreboard players operation #lpx g5 = #px g5
scoreboard players operation #lpz g5 = #pz g5
