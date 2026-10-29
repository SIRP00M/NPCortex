execute store result score #a pp_t if entity @a
execute store result score #e pp_t if entity @e[type=minecraft:player]
execute store success score #sel pp_t if entity @e[tag=npcortex_Parity,limit=1]
scoreboard players set Parity pp 0
execute store result score #sb pp_t run scoreboard players get Parity pp
tellraw @s [{"text":"@a count="},{"score":{"name":"#a","objective":"pp_t"}},{"text":"  @e[type=player] count="},{"score":{"name":"#e","objective":"pp_t"}},{"text":"  NPC selectable by name="},{"score":{"name":"#sel","objective":"pp_t"}}]
say PARITY|T18|PENDING observation: @a vs @e[type=player] counts above (Owner judges semantics)
