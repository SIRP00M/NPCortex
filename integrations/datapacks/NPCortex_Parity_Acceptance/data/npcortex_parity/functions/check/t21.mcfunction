execute at @e[tag=npcortex_Parity,limit=1] store result score #m pp_t if entity @e[type=#minecraft:skeletons,distance=..120]
execute at @e[tag=npcortex_Parity,limit=1] store result score #z pp_t if entity @e[type=minecraft:zombie,distance=..120]
tellraw @s [{"text":"hostiles near NPC: zombies="},{"score":{"name":"#z","objective":"pp_t"}},{"text":" skeletons="},{"score":{"name":"#m","objective":"pp_t"}}]
say PARITY|T21|PENDING observation: hostile counts above (spawning is random; 0 is inconclusive, not a failure)
