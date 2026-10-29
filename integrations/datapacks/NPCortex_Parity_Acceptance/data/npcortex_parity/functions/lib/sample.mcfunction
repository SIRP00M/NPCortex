# Samples the NPC's Health every 5 ticks into the minimum #minhp (started by lib/spawn, stopped by reset). Only reads; never changes the NPC.
execute as @e[tag=npcortex_Parity,limit=1] store result score #hpnow pp_t run data get entity @s Health 1
execute if score #hpnow pp_t < #minhp pp_t run scoreboard players operation #minhp pp_t = #hpnow pp_t
scoreboard players reset #hpnow pp_t
schedule function npcortex_parity:lib/sample 5t
