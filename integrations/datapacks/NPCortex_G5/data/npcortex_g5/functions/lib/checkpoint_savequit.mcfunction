function npcortex_g5:lib/snap
say [G5] CHECKPOINT save_quit NPC is walking a navlong goal; world will be saved by Save and Quit
tellraw @a[tag=g5_op] [{"text":"[G5] CHECKPOINT: now press Esc > Save and Quit to Title (while yui is walking). Re-open THIS world, then run ","color":"gold"},{"text":"/function npcortex_g5:rejoin","color":"aqua","clickEvent":{"action":"suggest_command","value":"/function npcortex_g5:rejoin"}}]
scoreboard players set #running g5 0
scoreboard players set #cp g5 1
scoreboard players set #phase g5 12
scoreboard players set #tick g5 -1
scoreboard players set #settle g5 -1
scoreboard players set #result g5 0
# recovery group runs p12 and p13 after the rejoin; a full start keeps its own #last
execute if score #last g5 matches ..12 run scoreboard players set #last g5 13
