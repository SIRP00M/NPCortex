# Adaptive mode lost sight of the NPC after p00 had confirmed observation: stop, never continue blind (no retries).
execute if score #nu g5 matches 0 run say [G5] NOTE no untagged non-operator candidate found (NPC gone or no longer selectable)
execute if score #nu g5 matches 2.. run say [G5] NOTE ambiguous: more than one untagged non-operator candidate (another NPC or human)
say [G5] ABORT NPC observation lost in adaptive mode; dependent scenarios were not run
tellraw @a[tag=g5_op] {"text":"[G5] ABORT: the NPC can no longer be observed (see log). Remaining scenarios were NOT run.","color":"red"}
scoreboard players add #blocked g5 1
scoreboard players set #running g5 0
