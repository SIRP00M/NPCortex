tellraw @s {"text":"[NPCortex M04 Option D] Read-only Acceptance Menu","color":"gold","bold":true}
tellraw @s [{"text":"[Capture Yui + preflight]","color":"green","clickEvent":{"action":"run_command","value":"/function npcortex_m04:preflight"}},{"text":"  (No XYZ, no mutation)","color":"gray"}]
tellraw @s [{"text":"[Check Inventory]","color":"aqua","clickEvent":{"action":"run_command","value":"/function npcortex_m04:inventory"}}]
tellraw @s [{"text":"[Capture again after Rejoin]","color":"yellow","clickEvent":{"action":"run_command","value":"/function npcortex_m04:after_rejoin"}}]
tellraw @s [{"text":"[Gates 3-6] HOLD until native authorization, mining cancellation and pickup are proven","color":"red"}]
tellraw @s {"text":"Do not claim PASS from this menu; review evidence with scripts/m04_acceptance_review.py","color":"gray"}
