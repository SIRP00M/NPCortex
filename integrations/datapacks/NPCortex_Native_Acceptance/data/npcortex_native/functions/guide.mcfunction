# Instructions only. Nothing here touches the world or simulates an NPC action: every Break/Pickup must come from the Manager (Brk/Pck + Confirm).
tellraw @s {"text":"[NPCortex native] Acceptance guide (Manager = the NPCortex Manager screen)","color":"gold","bold":true}
tellraw @s {"text":"A1 Break: spawn an NPC, stand it at tile S1 (4,6), face the log, Manager: Brk, Confirm. Then check/s1_break.","color":"white"}
tellraw @s {"text":"A2 Pickup: stand an NPC next to an item stack (S5 22,6 / S6 26,6 / S7 30,6), Manager: Pck, Confirm.","color":"white"}
tellraw @s {"text":"A3 Contention: two NPCs facing the S3 log (13,6): Brk + Confirm on both within a second. A3b: one NPC on each S2 log (8,6)(9,6).","color":"white"}
tellraw @s {"text":"A4 Interleave: while an NPC mines (use a slow tool), try the inventory-give, C2 follow, C1 move and C2 goal commands on it (type /npcortex and TAB). Refusals are the expected result.","color":"white"}
tellraw @s {"text":"A5 Rejoin: quit to title mid-mining, rejoin, Manager: Rec. on that NPC. Nothing is cleared for you.","color":"white"}
tellraw @s {"text":"A6 Many NPCs: prep/spawn_ten, then Brk + Confirm each on its own log of row S8 (z=12). Repeat with 1, 3, 5 first if you like.","color":"white"}
tellraw @s {"text":"A0 Denied: look at the diamond block (34,6) and the chest (36,6): Brk must be refused. A7: any failure or HOLD is judged by the report.","color":"white"}
tellraw @s [{"text":"[Check ALL]","color":"yellow","clickEvent":{"action":"run_command","value":"/function npcortex_native:check/all"}},{"text":"  then run scripts\\native-acceptance.ps1 -Action Report on Windows","color":"gray"}]
