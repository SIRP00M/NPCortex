scoreboard players set #cp g5 0
npcortex debug cplus m03 enable
say [G5] REJOIN operator back; observing p12 (no replay) then p13 (new movement)
scoreboard players set #phase g5 12
scoreboard players set #tick g5 0
scoreboard players set #settle g5 -1
scoreboard players set #result g5 0
scoreboard players set #running g5 1
schedule function npcortex_g5:loop 10t replace
