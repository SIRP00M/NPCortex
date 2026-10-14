# NPCortex — Architecture Stabilization Sprint 1

ฐาน: ZIP `v0.8.2-alpha_AtomicBrainChoiceFix1_FullRepository(1)` ที่แนบมา
สถานะ: **development snapshot / experimental — ยังไม่รับรอง Forge build หรือผ่านเกมจริง**
ไม่ใช่การประกาศ Release ใหม่บน GitHub และไม่ใช่ Autonomous Companion ที่เสร็จสมบูรณ์

## สิ่งที่เปลี่ยนจริง

| ส่วน | การเปลี่ยนแปลง |
|---|---|
| Skill Registry | `agent/SkillRegistry.java` กำหนด Precondition, Execute, Verify, Recovery และชนิดเป้าหมาย; ปิดการแก้ Registry หลังเริ่ม runtime |
| Server registry | `BrainSkillRegistry` มี 13 skills และสร้างรายการคู่ `skill:ref` ที่เสนอได้จากข้อมูลจริง |
| Task adapter | แยก executor ของแต่ละ skill ไป `HarvestGoalSkills`; controller ไม่ dispatch ด้วย if/else รายชื่อ tool อีก |
| Backend | Contract v2 รับ catalog/choices จาก Java; เพิ่ม skill ฝั่งเซิร์ฟเวอร์ได้โดยไม่เพิ่มรายชื่อ tool หรือเงื่อนไขใน Python; v1 ยังรองรับ mod เก่า |
| Lifecycle | `AgentRuntime` และ `GoalState` แยก READY → THINKING → EXECUTING → VERIFYING ออกจาก phase ทางกายภาพ พร้อม CLEANING / PAUSED / SUCCEEDED / CANCELLED |
| Event bus | Typed events, history 128 events ในหน่วยความจำ; เก็บ journal 32 entries ต่อ task ใน NBT และเก็บงานล่าสุดหลังจบ |
| Ownership | จุดรับงาน gather/agentic/bulk transfer ใช้การตรวจ busy ร่วมกัน; follow/stay จะไม่แทรกระหว่าง legacy cleanup |
| Recovery | จำความล้มเหลวด้วย skill + block position / item UUID ไม่ใช่ b0/d0; เก็บได้ 32 ข้อ; ซ้ำสองครั้งในเงื่อนไขเดิมจะตัดตัวเลือกนั้นออก |
| 3D access | `Navigation3D` เป็น bounded weighted A* ที่ไม่รู้จักต้นไม้; รองรับ WALK, PILLAR, BRIDGE และคิดพื้นที่ยืน/หัว/พื้นรองรับ |
| Execution | `Navigation3DAccess` ใช้การเดินและกระโดดจริง วางบล็อกจากกระเป๋า ตรวจโลกซ้ำทุก primitive และเก็บเส้นทางย้อนกลับ |
| Cleanup | รื้อเส้นทาง 3D ย้อนลำดับก่อนรื้อเสาเดิม; โลกเปลี่ยน/พื้นหาย/มีคนยืนบนบล็อกจะหยุดพร้อมคง ledger |
| Diagnostics | `/npcortex task trace [npc]` อ่าน 10 events ล่าสุด; status แสดง lifecycle และจำนวน access blocks |

นี่เป็นการย้ายผ่าน adapter: `ActionPlanRunner` และ `GatherTaskController` ยังมี execution loop เดิมสำหรับความเข้ากันได้ ยังไม่ได้รวมทั้งหมดเป็น scheduler ตัวเดียว และ general goal ที่รับจากผู้เล่นใน sprint นี้ยังเป็น `harvest_logs` ระบบนำทางและ Registry ถูกแยกให้ใช้กับ goal อื่นได้ใน sprint ถัดไป ไม่ควรเรียกว่า General Minecraft Agent ที่ครบแล้ว

## สิ่งที่แก้ระหว่างตรวจความเสถียร

- `ChatBridge` เดิมมีรายการ tool ซ้ำและไม่มี `clear_soft`/`restore` แม้ส่วนอื่นเสนอได้: เปลี่ยนให้ตรวจจาก Registry เดียวกัน
- Bulk transfer เดิมไม่ตรวจ Agentic task: ปิดช่องทางเปลี่ยน inventory ระหว่าง task กำลังนับผล
- ไม่อนุญาตให้ approach ปกติเดินหนีจากเสาที่ยังต้องรื้อ
- หยุด navigation เมื่อถึงจุดหมายแล้ว ป้องกันเดินต่อหลังยืนยันผล
- เงื่อนไข cleanup รวมโครงสร้าง 3D และ terrain edits; cleanup มีเวลาสิ้นสุด 2,400 ticks แล้ว pause ถ้าไม่สำเร็จ
- งานยกเลิกต้อง cleanup ก่อน; resume สามารถกลับเข้า cleanup แม้งบ Qwen หมดแล้ว
- ผล HTTP เก่ายังตรวจ task UUID, request nonce, generation และ state; เพิ่ม dimension guard
- เจ้าของหายไปจะ pause; เมื่อกลับมาใช้ resume โดยไม่ลบความคืบหน้า
- เสาและทางเดินใหม่ไม่ใช้ log ชนิดที่กำลังเก็บเป็นวัสดุ เพื่อไม่ทำให้ตัวนับไม้เข้า/ออกกระเป๋าคลาดเคลื่อน
- เป้าหมายใน chunk ที่ไม่โหลดจะไม่ถูกเสนอให้ Qwen แต่ยังไม่นับว่าต้นไม้เสร็จ

## ขอบเขตของ Navigation3D

- ไม่ teleport / fly / force-load chunk และ Qwen ส่งพิกัดเองไม่ได้
- ใช้ air เป็นช่องว่างของ body; ไม่ว่ายน้ำ ไม่ปีน ladder และไม่ขุด tunnel อัตโนมัติ
- เส้นทางใหม่มีได้ไม่เกิน 12 temporary blocks, 48 steps, 1,600 expanded nodes; จำกัด queue ด้วย
- สร้าง candidate plans ได้ไม่เกิน 2 เป้าหมายต่อ observation; คำนวณใหม่เมื่อลงมือ
- เสา `climb` เดิมยังมีเพดาน 6 ระดับ; แยกจากงบโครงสร้างของ `navigate_3d`
- กิ่งที่มีใบปิดทางทั้งหมดอาจยังต้องใช้ `clear_leaf`/`climb` หรือ pause; ไม่รับประกันหาเส้นทางได้ทุก Fancy Oak
- ความล้มเหลวจำจาก pose, บล็อกใกล้เป้าหมาย/ใกล้ตัว, วัสดุ และ ledger ที่สังเกต; ไม่ใช่แผนที่โลกถาวร
- จำกัดคำขอเดิม 1–16 logs, 80 Brain calls, 8 failures, 5 stalls และ 96 logs/tree ยังคงอยู่
- กติกา world edit เดิมยังต้อง singleplayer + cheats + opt-in; ระยะเข้าถึงขึ้นกับโหมดของเจ้าของ ยังไม่ใช่ executor ที่เทียบเท่า Survival ทุกด้าน (เช่นเวลา mining/tool durability)
- Ledger ตรวจตำแหน่งและชนิดบล็อก; หากผู้เล่นทำลายแล้ววางบล็อกชนิดเดียวกันกลับตำแหน่งเดิม ระบบยังพิสูจน์เจ้าของใหม่ไม่ได้

## วิธีตรวจและเปิดใช้งาน

อัปเดต **backend และ Forge mod พร้อมกัน** จาก snapshot นี้; backend รุ่นเก่าจะไม่เข้าใจ contract v2
แนะนำใช้สำเนาโลกทดสอบสำหรับ acceptance และ backup งานเดิมก่อนอัปเดต ไม่ลบฐานข้อมูล memory / character packs / config เดิม

จาก root ของ repository:

```powershell
python scripts/test-runtime.py
.\scripts\setup-forge-wrapper.ps1
cd forge-mod
.\gradlew.bat build --no-daemon
```

นำ JAR ที่ build สำเร็จจาก `forge-mod/build/libs` ไปใช้กับ Forge 1.20.1 ตามการติดตั้งเดิม (อย่าใส่ JAR รุ่นเก่าซ้ำ)
เริ่ม backend ตาม README เดิม และ restart Minecraft หลังเปลี่ยน JAR

ให้ NPC ถือเครื่องมือและมี dirt/cobblestone สำหรับสร้างทางใน **NPC bag** เช่น 32 ก้อน ไม่ใช่เฉพาะกระเป๋าผู้เล่น
เปิด world edits ด้วยวิธีเดิมก่อนทดสอบ

```text
/npcortex task gather minecraft:oak_log 6
/npcortex task status
/npcortex task trace
/npcortex task cancel
/npcortex task resume
```

`goal_count` คือขั้นต่ำของไม้ที่เพิ่มในกระเป๋า ยังต้องตัดต้นที่ commit แล้วจนหมด เก็บไม้ที่ขุด และ cleanup ให้ครบก่อน finish
Trace ใช้แยกว่า Qwen เลือกอะไร executor ทำจริงหรือไม่ และติดขัดตรงไหน
หากเสาหรือทางเดินถูกขุดจน cleanup ไม่ปลอดภัย งานจะ pause พร้อม ledger; ซ่อมทางที่จำเป็นก่อน resume ห้ามลบ ledger เพื่อหลอกว่ารื้อเสร็จ

## ผลตรวจในสภาพแวดล้อมที่จัดทำ

| Gate | ผล |
|---|---|
| Backend regression + Registry v2 | 191 tests ผ่าน (รวมเดิม 182 + ใหม่ 9) |
| Java 17 core compile/run | 193 assertions ผ่าน: registry, lifecycle, events, failure history, เส้นทางและ inverse route ใน voxel simulation |
| Parse Java source ทั้งหมด | 65 files, syntax errors 0 |
| Forge compile/type-check/build | **ยังไม่ผ่านการรับรอง**: Gradle distribution download ผ่าน JVM ติด `Network is unreachable`; ไม่มี Forge dependency build สำเร็จ |
| Qwen/Ollama จริง | **ยังไม่ได้ทดสอบ**; API tests ใช้ mock provider |
| Fancy Oak ใน Minecraft จริง | **ยังไม่ได้ทดสอบ**; ไม่มี world save / game instance ของต้นเดิมใน environment นี้ |

Syntax/core tests ไม่ใช่หลักฐานว่า Forge APIs ถูกต้องทุกจุดหรือ Minecraft physics ทำงานตามคาด ยังต้องผ่าน build และ acceptance ในไฟล์ `FANCY_OAK_ACCEPTANCE.md` ก่อนเรียก snapshot นี้ว่าเสถียรในเกม

## งานต่อจาก sprint นี้

1. ให้ Forge build ผ่านและเก็บผลจากต้น Fancy Oak เดิมพร้อม trace; แก้ executor จากหลักฐานที่ได้
2. ย้าย ActionPlan และ legacy Gather เข้า scheduler/lifecycle/skill catalog เดียวกันจนหมด พร้อม migration ของงานเก่า
3. แยก Goal interface จาก harvest ledger และเพิ่ม non-tree goal ที่มี verifier ชัดเจน
4. เพิ่มการสังเกตพื้นที่/วางแผนแบบ incremental ต่อ tick ถ้า profiling แสดงว่า 3D search กระทบ server tick
5. เพิ่ม survival-consistent mining/crafting และ benchmark หลาย seed ก่อนขยายบุคลิก/ชีวิตประจำวัน
