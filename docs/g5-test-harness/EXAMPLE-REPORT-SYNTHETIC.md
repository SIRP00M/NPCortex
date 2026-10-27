> **SYNTHETIC EXAMPLE.** Produced by `scripts/test-g5-collector.ps1` fixtures from a mock-world simulation. It shows the report format and the verdict rules only; it is NOT evidence about NPCortex or Minecraft. Goal/action ids are random test values.

# NPCortex G5 test report 20261027-072235

World: NPCortex_G5_fixture | datapack: UNVERIFIED | database: BACKUP_OK

| Scenario | Class | Status | Reason |
|---|---|---|---|
| p00_capability_probe | AUTO | **PASS** | probe completed, mode=ADAPTIVE (selector=OK tag=OK entity_data=OK position=OK); independent M03 snapshot places the NPC at the spawn block (distance 0.00) |
| p02_navlong_20 | AUTO | **PASS** | M03 final position 0.08 blocks from the target; goal=7dd1e6ef-141c-4bd8-bca6-74daeba801c8 action=16bea5be-cd99-48a3-bd87-7e6c49ef63d2 states=MOVING>COMPLETED; exactly one terminal line COMPLETED; in-game selector agrees |
| p03_navlong_40 | AUTO | **PASS** | M03 final position 0.16 blocks from the target; goal=84299c59-1c25-47ee-bf94-7093cd23310f action=66c40732-12d0-4ec0-85c9-98ff186c3928 states=MOVING>REPLANNING>MOVING>COMPLETED; exactly one terminal line COMPLETED; in-game selector agrees |
| p04_navlong_80 | AUTO | **PASS** | M03 final position 0.09 blocks from the target; goal=af80cbcc-620e-452f-8957-10959ddc479d action=5860b46f-1ec5-4eaa-a087-9f1d33739490 states=MOVING>REPLANNING>MOVING>REPLANNING>MOVING>COMPLETED; exactly one terminal line COMPLETED; in-game selector agrees |
| p05_navlong_150 | AUTO | **PASS** | M03 final position 0.00 blocks from the target; goal=d4f8d79b-a92d-4c5d-9b81-ec4849850f0e action=d16aefdf-573d-4575-b160-cf48c71f3352 states=MOVING>REPLANNING>MOVING>REPLANNING>MOVING>REPLANNING>MOVING>REPLANNING>MOVING>REPLANNING>COMPLETED; exactly one terminal line COMPLETED; in-game selector agrees |
| p06_navlong_diag60 | AUTO | **PASS** | M03 final position 0.00 blocks from the target; goal=32b469df-32a3-41fc-b4de-ed63c6cd692d action=c87e5fa7-b742-4edb-baf1-4a7686b55b3a states=MOVING>REPLANNING>MOVING>REPLANNING>MOVING>REPLANNING>COMPLETED; exactly one terminal line COMPLETED; in-game selector agrees |
| p07_small_wall_detour | AUTO | **PASS** | M03 final position 0.19 blocks from the target; goal=f87a83ab-7027-426e-a051-691e3ca5d59d action=2a853540-eb72-42e6-b4a8-3ee64f52c158 states=MOVING>REPLANNING>MOVING>COMPLETED; exactly one terminal line COMPLETED; in-game selector agrees |
| p08_large_wall_no_progress | AUTO | **UNVERIFIED** | bounded terminal observed (NPC stopped 53.41 blocks short, exactly one FAILED line) but the failure REASON (LONG_NO_PROGRESS vs OSCILLATION vs other) is not logged by production code; goal=401c4a8d-2df1-4e13-bd5a-0f3d0a14738c action=a5ace17f-611a-4573-9191-4b62b1e21020 states=MOVING>FAILED |
| p09_cancel_leg_active | AUTO | **PASS** | cancel issued in journal state MOVING (snapshot in the same tick); NPC stopped and stayed stopped (post-cancel spread 0.00); no COMPLETED result; goal=9aac4cc1-7ff8-45eb-a436-33e94bb8afca action=d015fef7-0b29-4f0d-a93b-5493dbcd74ba states=MOVING>CANCELLED |
| p10_cancel_quiescing | SEMI-AUTO | **PASS** | cancel issued in journal state REPLANNING (snapshot in the same tick); NPC stopped and stayed stopped (post-cancel spread 0.00); no COMPLETED result; goal=c7272fb4-5916-4465-8a5a-4907b4d90ac9 action=08dfff83-0261-436c-9bc8-2eeb31f77f47 states=MOVING>REPLANNING>CANCELLED |
| p11_savequit_checkpoint | SEMI-AUTO | **PASS** | harness reached the Save&Quit checkpoint; M03 snapshot shows journal state MOVING at x=4.364954381937942 (the quit itself is MANUAL; judged by p12); goal=bc3dc704-186d-4b90-a31a-1b4888846ca8 action=7f0835a5-9be5-4660-9664-94c5613aba16 states=MOVING |
| p12_rejoin_no_replay | SEMI-AUTO | **PASS** | no movement across 6 post-rejoin snapshots (spread 0.00); journal state 'CANCELLED' (fenced/terminal/no active goal) |
| p13_new_navlong_after_recovery | SEMI-AUTO | **PASS** | M03 final position 0.08 blocks from the target; goal=ab5b3d3d-fb14-4bb1-a703-13fca0368a5e action=a1f8ec24-40fb-4879-b76b-8f46e0d5f06c states=MOVING>COMPLETED; exactly one terminal line COMPLETED; in-game selector agrees |
| p14_regress_c2_navigate | AUTO | **PASS** | M03 final position 0.01 blocks from the target; goal=6596045d-b068-4d9b-aa13-23c4738dcd14 action=4b350553-3b0f-4207-8fba-8c65d43a4e98 states=COMPLETED; exactly one terminal line COMPLETED; in-game selector agrees |
| p15_regress_c2_move | AUTO | **PASS** | M03 final position 0.10 blocks from the target; goal=ea1c6b22-6a29-4295-8450-7bef98db983a action=be8d5d46-520a-40d0-806f-ee0fc44ab92d states=COMPLETED; exactly one terminal line COMPLETED; in-game selector agrees |
| p16_regress_follow_start | AUTO | **PASS** | M03 position is 2.70 blocks from the operator (follow works); selector agrees |
| p17_regress_follow_stop | AUTO | **PASS** | NPC stationary near x=27.796030463858312 while the operator is 90 blocks away (spread 0.00) |
| p18_regress_e3_taskmove | SEMI-AUTO | **PENDING** | E3 guarded move displaced the NPC by 4.95 blocks and it stopped; terminal/ACK acceptance needs the manual G3 finalize step and DB review |
| p19_multi_1_npc | AUTO | **PASS** | all 1 NPCs at their targets per M03 snapshots, 1 goals COMPLETED exactly once; tick samples recorded (comparison to a baseline is PENDING) |
| p20_multi_3_npc | AUTO | **PASS** | all 3 NPCs at their targets per M03 snapshots, 3 goals COMPLETED exactly once; tick samples recorded (comparison to a baseline is PENDING) |
| p21_multi_5_npc | AUTO | **PASS** | all 5 NPCs at their targets per M03 snapshots, 5 goals COMPLETED exactly once; tick samples recorded (comparison to a baseline is PENDING) |
| p22_multi_10_npc | AUTO | **PASS** | all 10 NPCs at their targets per M03 snapshots, 10 goals COMPLETED exactly once; tick samples recorded (comparison to a baseline is PENDING) |

Correlation (scenario / goal id / action id / journal states / terminal lines):

| Scenario | Mode | Goal | Action | Journal states | Terminal |
|---|---|---|---|---|---|
| p00_capability_probe | ADAPTIVE |  |  |  |  |
| p02_navlong_20 | ADAPTIVE | 7dd1e6ef-141c-4bd8-bca6-74daeba801c8 | 16bea5be-cd99-48a3-bd87-7e6c49ef63d2 | MOVING>COMPLETED | COMPLETED |
| p03_navlong_40 | ADAPTIVE | 84299c59-1c25-47ee-bf94-7093cd23310f | 66c40732-12d0-4ec0-85c9-98ff186c3928 | MOVING>REPLANNING>MOVING>COMPLETED | COMPLETED |
| p04_navlong_80 | ADAPTIVE | af80cbcc-620e-452f-8957-10959ddc479d | 5860b46f-1ec5-4eaa-a087-9f1d33739490 | MOVING>REPLANNING>MOVING>REPLANNING>MOVING>COMPLETED | COMPLETED |
| p05_navlong_150 | ADAPTIVE | d4f8d79b-a92d-4c5d-9b81-ec4849850f0e | d16aefdf-573d-4575-b160-cf48c71f3352 | MOVING>REPLANNING>MOVING>REPLANNING>MOVING>REPLANNING>MOVING>REPLANNING>MOVING>REPLANNING>COMPLETED | COMPLETED |
| p06_navlong_diag60 | ADAPTIVE | 32b469df-32a3-41fc-b4de-ed63c6cd692d | c87e5fa7-b742-4edb-baf1-4a7686b55b3a | MOVING>REPLANNING>MOVING>REPLANNING>MOVING>REPLANNING>COMPLETED | COMPLETED |
| p07_small_wall_detour | ADAPTIVE | f87a83ab-7027-426e-a051-691e3ca5d59d | 2a853540-eb72-42e6-b4a8-3ee64f52c158 | MOVING>REPLANNING>MOVING>COMPLETED | COMPLETED |
| p08_large_wall_no_progress | ADAPTIVE | 401c4a8d-2df1-4e13-bd5a-0f3d0a14738c | a5ace17f-611a-4573-9191-4b62b1e21020 | MOVING>FAILED | FAILED |
| p09_cancel_leg_active | ADAPTIVE | 9aac4cc1-7ff8-45eb-a436-33e94bb8afca | d015fef7-0b29-4f0d-a93b-5493dbcd74ba | MOVING>CANCELLED | CANCELLED |
| p10_cancel_quiescing | ADAPTIVE | c7272fb4-5916-4465-8a5a-4907b4d90ac9 | 08dfff83-0261-436c-9bc8-2eeb31f77f47 | MOVING>REPLANNING>CANCELLED | CANCELLED |
| p11_savequit_checkpoint | ADAPTIVE | bc3dc704-186d-4b90-a31a-1b4888846ca8 | 7f0835a5-9be5-4660-9664-94c5613aba16 | MOVING | COMPLETED |
| p12_rejoin_no_replay | ADAPTIVE |  |  |  |  |
| p13_new_navlong_after_recovery | ADAPTIVE | ab5b3d3d-fb14-4bb1-a703-13fca0368a5e | a1f8ec24-40fb-4879-b76b-8f46e0d5f06c | MOVING>COMPLETED | COMPLETED |
| p14_regress_c2_navigate | ADAPTIVE | 6596045d-b068-4d9b-aa13-23c4738dcd14 | 4b350553-3b0f-4207-8fba-8c65d43a4e98 | COMPLETED | COMPLETED |
| p15_regress_c2_move | ADAPTIVE | ea1c6b22-6a29-4295-8450-7bef98db983a | be8d5d46-520a-40d0-806f-ee0fc44ab92d | COMPLETED | COMPLETED |
| p16_regress_follow_start | ADAPTIVE |  |  |  |  |
| p17_regress_follow_stop | ADAPTIVE |  |  |  |  |
| p18_regress_e3_taskmove | ADAPTIVE |  |  |  |  |
| p19_multi_1_npc | ADAPTIVE | 320907a3-08ed-4afd-b286-8dc8f3ed60ec | 9259a071-d6a2-43d9-9fe1-0f5f186fe62a | COMPLETED | COMPLETED |
| p20_multi_3_npc | ADAPTIVE | bc559d11-60b9-425e-b30c-be1fead714d8,b07e7e8b-2111-48e7-8b66-044d72e6e511,10ca91b2-4451-4c63-84af-129d97e49972 | 38bc0c22-01ec-4741-b266-a5da999ddd05,8886eb71-1521-464e-98d7-286a8bd6348d,04129286-7630-4ae6-898a-11396146ce28 | COMPLETED | COMPLETED,COMPLETED,COMPLETED |
| p21_multi_5_npc | ADAPTIVE | 22372348-1a60-46d0-9101-74223338c6ab,24f5b4c6-0e06-4f84-b918-eefeb132c981,deeb9b6e-e558-4b4a-886d-b396c23d85ed,2caa79f9-64c7-4c3a-a29b-81f05c413252,90fe7361-2c08-44d2-9624-52e5974a7884 | 1849ddcf-cbb8-48ad-97be-f559919fcbc7,ca72684b-07e6-48e5-94c3-c0a47a2f8e84,4e9bfeb7-fa7d-4e64-aa83-7d8eb7d4d23f,43ff2fd3-9d6a-470b-991c-475f1f543e30,56c5c0fb-2037-4d9c-a77e-7100f243e57c | COMPLETED | COMPLETED,COMPLETED,COMPLETED,COMPLETED,COMPLETED |
| p22_multi_10_npc | ADAPTIVE | 2e12ed24-6665-49d8-a76a-d84a4dcf32cf,ec957477-8970-4419-ae4e-2895f79ea43e,a58362a7-879c-46cb-96f8-da7b90c3122e,db0ca3be-4b5c-4375-b208-35302e5d3397,90a3715d-218e-4ab8-b7ea-826e0dfd8f52,8e418d4b-4a7d-42a2-8277-77012cc5016f,c49c5146-fa9a-49a8-b8e8-e5d708fa51a2,e5ae7a99-2ee3-4788-b819-4433f09fb255,7de2310f-1d6e-4127-a2d8-7c4094c984cf,ea007c1f-0390-4a34-8c81-9d245e408e0b | 860917fa-5e91-463f-9bbd-0ab96ca58f9b,0764acc4-a31c-4e2e-a36e-2bd92627a7fc,83971ee2-400a-49a0-88a8-702ca5af89db,68810931-873b-4e7b-a65c-d076c2596f06,3b9657d7-518c-441c-8af2-21f4f2db7e7d,46cd8835-0fb2-4135-b765-7680c3d1d8c7,5983c4ea-b065-46dd-af2f-349da4c14b03,b8016973-8fdd-41b6-93b6-b8a1d8937039,031b3892-1caf-45f9-a92b-2c394b02ae20,8f4d48aa-5d72-4355-9c82-65161e616966 | COMPLETED | COMPLETED,COMPLETED,COMPLETED,COMPLETED,COMPLETED,COMPLETED,COMPLETED,COMPLETED,COMPLETED,COMPLETED |

Counts: PASS=20, PENDING=1, UNVERIFIED=1

Not observable with the existing production logging:
- LongState/leg counts (c2 status is chat-only; transitions only inside M03 measurement windows)
- LONG_NO_PROGRESS vs OSCILLATION reason text (not logged)
- FIFO wait distribution (not logged)
- segment-start capture cost (no timing log)
- real MSPT percentiles unless /debug start|stop output is supplied
- dedicated-server and fault-injected shutdown (not exercised)

Collector notes:
- backend log not supplied (-BackendLog); backend console output is UNVERIFIED
- no run/debug folder: no /debug profiler output (G4 MSPT percentiles PENDING)
