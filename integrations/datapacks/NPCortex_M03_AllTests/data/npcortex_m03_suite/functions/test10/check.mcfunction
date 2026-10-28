# NPCortex M03-FINAL copied-world guided operator acceptance (1.20.1).
# No automatic PASS. Use only while M03 bridge enabled.
tellraw @s {"text":"[M03] 10: snapshots and evidence need performance JSON + profiler/OS sampler; output alone is NOT PASS.","color":"yellow"}
npcortex debug cplus m03 status
npcortex debug cplus m03 snapshot yui
tellraw @s {"text":"[M03] Inspect <world>/data/npcortex/m03-evidence/performance-*.json and m03_performance.py.","color":"aqua"}
