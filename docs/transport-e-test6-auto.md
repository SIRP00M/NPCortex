> M03-FINAL fresh audit update: this document is historical. Current source, integrated commands and acceptance status are in [m03-final/README.md](m03-final/README.md). The legacy Test6 source and hooks were absent at audit; use `npcortex_m03:test6a`, not the old Test6 functions. Historical PASS does not certify this candidate.

# M03 / Transport E — TEMP Test 6 controlled network outage (Option D)

**Scope and evidence:** This is a *single-reserve, controlled network-loss simulation* in Minecraft Forge 1.20.1. It is NOT a real Python backend process stop/restart, not a general transport monkeypatch, and does NOT close the shared backend or interrupt other NPCs. Do not mark the complete real-backend-offline acceptance gate PASS from this test. Build/type-check and Windows gameplay remain PENDING until actually run on Windows.

## What this does automatically

- `/function npcortex_test6:start` as an ONLINE operator triggers an isolated helper; no manual XYZ.
- Uses Yui's live ServerPlayer position and loaded/flat 5-block corridor (same safety selection as Test5).
- Preflight: requires no active Follow/C2/G2/G3/recovery, stable actor/session/OP, and previous backend status `NONE`, `COMPLETED`, or `CANCELLED`. Never clears existing HOLD or SQLite.
- Calls the **real** `CPlusE3G2Runtime.move` path once. After `CPlusOutbox` writes its durable `/reserve` envelope but BEFORE transport network send, a one-shot **only-for-this-actor/world/requester, only-for-`/reserve`** simulated `ConnectException` occurs. This is an intentionally prevented handoff; no request should reach backend.
- The G2 existing failure handler must report `HOLD [CONNECT_FAILED]` and `no navigation started`; the helper waits until G2 pending has cleared and confirms the C2 journal, C1 motion, and actor position have not changed.
- Automatically restores normal networking for subsequent requests (the one-shot hook disarms), queries the **real** authenticated backend status, compares its original task/state to preflight, and examines the new original reserve record. `handoff_possible=false` and exact task/actor/session/requester/world identity must match. Nothing is deleted or resent.
- Only then emits `CONTROLLED OUTAGE PASS`. Any mismatch => `HOLD` and existing records are retained for forensic inspection. No auto taskmove retry, backend reset, C2 replay, mutation or forced actor fence release.

**Important:** If the backend had already accepted an unresolved attempt, this helper refuses to start. In other words, it will not manufacture a PASS by clearing old attempts.

## 1. Install (PowerShell / Windows) — use a copy of your test world

1. Save/backup the current **source** and use this full-repository candidate as a copy; do not overwrite your existing `forge-mod/run/saves`, backend SQLite, tokens or `.env`.
2. Build with Temurin JDK 17 (check `java -version`). From repo root:

```powershell
cd C:\path\to\NPCortex
.\scripts\test-test5-harness.ps1
.\scripts\test-test6-harness.ps1
.\scripts\dev-forge.ps1 -Task build
.\scripts\test-transport-e.ps1
```

3. With Minecraft closed, list saves and install into the chosen copied test world:

```powershell
Get-ChildItem '.\forge-mod\run\saves' -Directory | Select-Object -ExpandProperty Name
.\scripts\install-test6-datapack.ps1 -WorldName 'YOUR_TEST_WORLD_FOLDER'
```

4. Start the normal backend and Forge Minecraft client **in separate PowerShell windows**:

```powershell
.\scripts\dev-backend.ps1
```

```powershell
.\scripts\dev-forge.ps1 -Task runClient
```

The backend MUST be ONLINE before the test starts. Spawn Yui and stand nearby to keep the test area loaded. Cheat/OP permissions required. The bridge neither kills/restarts backend nor touches its process.

## 2. One in-game command

```mcfunction
/reload
/function npcortex_test6:start
```

No XYZ. You may inspect:

```mcfunction
/function npcortex_test6:status
```

If `/function` loses OP context, equivalent directly:

```mcfunction
/npcortex debug cplus autotest6 start yui
/npcortex debug cplus autotest6 status
```

**Do NOT issue concurrent taskmoves, click Test5 start, or manually retry a HOLD test.** A partial/hung request can remain ambiguous; preserve it. `abort` is allowed only once the scoped offline fault has been consumed. The helper rejects unsafe abort while the request is pending.

## 3. Expected evidence / acceptance

| Moment | Expected | Status |
|---|---|---|
| Start | `PREFLIGHT ... target=x,y,z`; Yui avatar position used | Pending gameplay |
| Simulated offline | `one taskmove queued`; `E3 G2 HOLD: reserve failed [CONNECT_FAILED]`; `no navigation started` | Pending gameplay |
| Fault scope | Exactly **one** injected reserve failure, no other route/actor blocked | Pure-Java self-test 11 checks PASS; game pending |
| Recovery of connectivity | Helper queries backend online, must still see original preflight terminal/NONE task and unchanged C2 journal | Pending gameplay |
| Durability | Original `/data/npcortex/transport/<task>-reserve.json` exists, matches identity, `handoff_possible=false` | Pending gameplay |
| Final | `CONTROLLED OUTAGE PASS`, no movement/replay | Pending gameplay |
| Real backend killed/restarted | **NOT TESTED BY THIS HELPER**; run a separate real backend offline/restart gate | PENDING |

If `HOLD`, collect `/function npcortex_test6:status`, `/npcortex debug cplus taskmovestatus yui`, `/npcortex c2 status yui`, Forge `latest.log`, and relevant redacted backend output. Do not delete sqlite, `.tmp`, save or outbox to clear a fence.

## 4. Separate remaining Transport E gates

- Genuine backend offline/restart before reserve and during admitted C2 movement (different failure windows), followed by comparison of original durable task/goal/attempt IDs. **Requires supervised test-world setup; this simulated fault does not replace it.**
- Report/terminal response or ACK loss, automatic recovery metadata-only, Save/Quit/Rejoin; Test7 and Test8 still PENDING.
- Exact Cancellation and Completion race winner coverage; Test5 cancel-wins was user-confirmed PASS; completion-wins collision remains PENDING.
- C1/C2/Follow/Inventory regression, 1/3/5/10 NPC MSPT, corrupt/disk fault injection remain separate.

## 5. Remove temporary Test6, then Test5

When no test is in progress and after capturing evidence, **close Minecraft and backend**:

```powershell
.\scripts\remove-test6-harness.ps1 -WorldName 'YOUR_TEST_WORLD_FOLDER' -RemoveFromSource
.\scripts\dev-forge.ps1 -Task build
```

Test5 remains installed until its own removal:

```powershell
.\scripts\remove-test5-harness.ps1 -WorldName 'YOUR_TEST_WORLD_FOLDER' -RemoveFromSource
.\scripts\dev-forge.ps1 -Task build
```

**Order matters**: remove Test6 before removing Test5, because this temporary Test6 helper reuses Test5's coordinate finder. All removal operations are limited to temporary source hooks, test Java files, scripts' packaged datapack source, and the named test-world datapack archive; **no game world, NPC saves, backend SQLite, task history or outbox is deleted**. Archive this guide and the test logs as acceptance evidence.
