# Fancy Oak acceptance — NOT RUN

ใช้ต้นเดิมของผู้เล่นในสำเนา world save เป็นกรณี A ก่อน แล้วจึงเพิ่มต้นใหม่
สถานะทุกแถวเริ่มเป็น **NOT RUN**; ห้ามนับ simulation เป็นผลเกมจริง

## เงื่อนไขร่วม

- Forge 1.20.1 และ mod/backend snapshot เดียวกัน; บันทึก Forge version, Java version, Qwen model tag และ world seed
- เก็บ world backup เพื่อ reset ต้น/พื้นที่/กระเป๋าเท่ากันทุกครั้ง
- เจ้าของอยู่ในระยะอนุญาต เปิด cheats + world-edit opt-in ตามข้อจำกัด alpha เดิม
- NPC bag มีวัสดุชั่วคราวที่ไม่ใช่ oak_log 32 ก้อน, พื้นที่ว่างเก็บไม้ และเครื่องมือ
- นับไม้ในต้นก่อนเริ่ม เก็บภาพพื้นที่ และจด inventory เริ่มต้น
- สั่ง gather oak_log 6; ต้องตัด **ต้นที่ commit ทั้งต้น** แม้เกิน 6
- บันทึก `/npcortex task status`, `/npcortex task trace` และ `logs/latest.log`

| กรณี | วิธีทดสอบ | เกณฑ์ผ่าน | ผล |
|---|---|---|---|
| A: Fancy Oak ต้นเดิม | สั่งเก็บไม้โดยไม่ช่วยเดิน/วางบล็อกให้ | log ใน committed tree เหลือ 0, bag เพิ่มตาม mined, temporary blocks/terrain edits 0 และ SUCCEEDED | NOT RUN |
| B: กิ่งด้านข้าง | ต้นที่ต้องเปลี่ยนจุดยืนหรือสร้างทางข้าง | เห็น navigate_3d execute/verify และรื้อทางได้จริง; ไม่ mine เกิน reach | NOT RUN |
| C: ต้นเล็ก | ทำงานเดิมกับ oak/birch/spruce ขนาดเล็ก | ยังเก็บครบโดยไม่สร้างโครงสร้างเกินจำเป็น | NOT RUN |
| D: Cancel ระหว่าง jump | cancel ก่อน/หลังวาง pillar | ไม่มี Qwen เก่าสั่งต่อ; รอร่อน/cleanup ปลอดภัย; เก็บของเดิมไว้ | NOT RUN |
| E: Cancel บน bridge | cancel หลังวางทาง 2–3 ก้อน | เดินย้อนก่อนรื้อ ไม่มีการรื้อพื้นใต้คนอื่น | NOT RUN |
| F: ทำลายเสาระหว่างทำงาน | ผู้เล่นขุด support ที่ NPC ใช้ | ตรวจพบสภาพเปลี่ยน หยุดหรือ pause อย่างตรงไปตรงมา ไม่วางกลางอากาศ/teleport | NOT RUN |
| G: เปลี่ยนบล็อกที่บันทึก | เปลี่ยน temporary block เป็นชนิดอื่นก่อน cleanup | ไม่ลบบล็อกใหม่; pause และรักษา ledger | NOT RUN |
| H: วัสดุหมด | เริ่มด้วยวัสดุเพียง 1–2 ก้อน | ไม่มีการสร้างของฟรี ไม่ใช้ไม้เป้าหมายชดเชย และไม่วนวิธีเดิมไม่จำกัด | NOT RUN |
| I: Inventory เต็ม | เติมของให้กระเป๋าใกล้เต็ม | ไม่กล่าวว่าครบถ้าของยังไม่อยู่ใน bag; วัสดุรื้อคืนไม่หาย (อาจตกพื้น) | NOT RUN |
| J: Reload / เจ้าของออก | save & quit ระหว่าง thinking, access และ cleanup | ไม่รับคำตอบเก่าข้ามงาน; งาน/ledger ยังอยู่และ resume อย่างปลอดภัย | NOT RUN |
| K: Qwen หยุดตอบ | ปิด backend ระหว่างรอ | pause ไม่ใช้ fallback ที่ทำลายโลกเอง | NOT RUN |
| L: คำสั่งแทรก | give/drop/follow/stay ขณะ task และ cleanup กำลังทำ | ไม่ทำให้ inventory ledger หรือ movement เสีย ownership | NOT RUN |
| M: เปลี่ยน dimension | ย้าย NPC ไปโลกอื่นระหว่างงาน แล้วกลับ | ไม่แก้พิกัดเดิมในโลกใหม่; เก็บงานไว้จนกลับโลกเดิม | NOT RUN |
| N: ความล้มเหลวสลับวิธี | ให้ approach เป้าหมายเดิมล้มเหลวสองครั้งโดยคั่น skill อื่น | ตัวเลือกเดิมถูก suppress เมื่อหลักฐานไม่เปลี่ยน และกลับมาได้เมื่อ geometry/material เปลี่ยน | NOT RUN |

บันทึกต่อ run: run_id, seed, target description, expected_logs, mined, bag_delta,
remaining_logs, temporary_blocks_remaining, material_delta, elapsed_ticks,
brain_calls, failures, stalls, interventions, final_state และเหตุผลที่ไม่ผ่าน

Gate เริ่มต้น: A–E และกรณีป้องกันความเสียหายทั้งหมดต้องมีผลจริงก่อนอนุมัติใช้โลกหลัก
หลังผ่านต้นเดิม ให้ทำซ้ำหลายต้น/หลาย seed แล้วรายงานจำนวนผ่าน/จำนวนทั้งหมดจริง
ยังไม่มีการรับรอง 95% และยังไม่ใช่ benchmark เทียบมนุษย์ภายใต้ Survival ที่เหมือนกันทุกข้อ
