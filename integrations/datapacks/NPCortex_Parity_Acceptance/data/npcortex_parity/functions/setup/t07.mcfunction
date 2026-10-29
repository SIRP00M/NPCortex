tellraw @s {"text":"[T07] Mining via the legitimate path (hardness, tool, drops, durability). Human oracle: break the same log with the same axe.","color":"gold"}
function npcortex_parity:lib/init
scoreboard players set #t07 pp 4
function npcortex_parity:lib/anchor
function npcortex_parity:lib/floor
function npcortex_parity:lib/spawn
setblock ~4 ~ ~1 minecraft:oak_log
tag @s add pp_op
tellraw @s {"text":"Install the PAP/native allow-list for Parity (scripts\\pap\\install-pap-allowlist.ps1), give Parity an iron axe (/npcortex c1 give Parity minecraft:iron_axe 1), look at the log at +4 and say: Parity, break that log -> confirm -> pickup. Then /function npcortex_parity:check/t07","color":"gray"}
