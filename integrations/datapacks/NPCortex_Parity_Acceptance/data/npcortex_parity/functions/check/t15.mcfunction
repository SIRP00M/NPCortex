execute store result score #m pp_t run scoreboard players get Parity pp_mined
execute if score #m pp_t matches 1.. run function npcortex_parity:check/t15_pass
execute unless score #m pp_t matches 1.. run say PARITY|T15|PENDING no mined-stone statistic for Parity (run T14 first)
