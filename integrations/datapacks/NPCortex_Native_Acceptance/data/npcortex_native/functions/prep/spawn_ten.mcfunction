# Spawns NPCs npx0..npx9 with the existing C1 command and puts each on its tile of row S8 (z=10). Needs the NPCortex C1 spawn command.
npcortex c1 spawn npx0
npcortex c1 spawn npx1
npcortex c1 spawn npx2
npcortex c1 spawn npx3
npcortex c1 spawn npx4
npcortex c1 spawn npx5
npcortex c1 spawn npx6
npcortex c1 spawn npx7
npcortex c1 spawn npx8
npcortex c1 spawn npx9
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx0,limit=1] ~2.5 ~ ~10.5
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx1,limit=1] ~4.5 ~ ~10.5
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx2,limit=1] ~6.5 ~ ~10.5
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx3,limit=1] ~8.5 ~ ~10.5
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx4,limit=1] ~10.5 ~ ~10.5
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx5,limit=1] ~12.5 ~ ~10.5
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx6,limit=1] ~14.5 ~ ~10.5
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx7,limit=1] ~16.5 ~ ~10.5
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx8,limit=1] ~18.5 ~ ~10.5
execute at @e[type=minecraft:marker,tag=npcx_anchor,limit=1] run tp @e[type=minecraft:player,name=npx9,limit=1] ~20.5 ~ ~10.5
item replace entity @e[type=minecraft:player,name=npx0,limit=1] container.0 with minecraft:diamond_axe
item replace entity @e[type=minecraft:player,name=npx1,limit=1] container.0 with minecraft:diamond_axe
item replace entity @e[type=minecraft:player,name=npx2,limit=1] container.0 with minecraft:diamond_axe
item replace entity @e[type=minecraft:player,name=npx3,limit=1] container.0 with minecraft:diamond_axe
item replace entity @e[type=minecraft:player,name=npx4,limit=1] container.0 with minecraft:diamond_axe
item replace entity @e[type=minecraft:player,name=npx5,limit=1] container.0 with minecraft:diamond_axe
item replace entity @e[type=minecraft:player,name=npx6,limit=1] container.0 with minecraft:diamond_axe
item replace entity @e[type=minecraft:player,name=npx7,limit=1] container.0 with minecraft:diamond_axe
item replace entity @e[type=minecraft:player,name=npx8,limit=1] container.0 with minecraft:diamond_axe
item replace entity @e[type=minecraft:player,name=npx9,limit=1] container.0 with minecraft:diamond_axe
tellraw @a {"text": "[NPCortex native] ten NPCs placed on row S8 with a diamond axe each. In the Manager press Brk then Confirm for each (look at its log first).", "color": "green"}
