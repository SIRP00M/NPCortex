#!/usr/bin/env node
// TEST-ONLY: writes a SYNTHETIC run folder (Forge-format log, M03-style evidence JSON, SQLite) from the controller simulation so that
// scripts/collect-g5-evidence.ps1 can be exercised without Minecraft. The data is invented by a mock; it proves the collector's
// parsing and verdict rules only, never NPCortex behaviour.
//   usage: node g5-collector-fixture.js <outDir> <mode>
//   modes: ok | fb | nolog | real | realp00 | inconsistent | dupterm | nosnap | stale | hang | timer | nogo
//   fb = owner-gated full run (+ g5-gate-log.jsonl GO rows); nogo = same without the gate log; timer = a gate release removed from the log (timer-only progression);
//   hang = a goal that never ends (awaits forever); realp00 = REAL log excerpt + REAL M03 snapshots of the first revision-3 p00 run
'use strict';
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const { fullRun, hangRun } = require('./simulate-g5-datapack.js');
const out = path.resolve(process.argv[2] || 'g5-fixture');
const mode = process.argv[3] || 'ok';
const uuid = () => crypto.randomUUID();
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const p2 = x => String(x).padStart(2, '0');
// real Forge 1.20.1 format, e.g. [27Oct2026 06:35:22.281] (see scripts/fixtures/forge-real-p01-blocked.log)
const iso = d => `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}T${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`;   // local wall clock
const stamp = d => `${p2(d.getDate())}${MON[d.getMonth()]}${d.getFullYear()} ${p2(d.getHours())}:${p2(d.getMinutes())}:${p2(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`;

const L = [];
L.push(`[${stamp(new Date(Date.now() - 6 * 3600 * 1000))}] [main/INFO] [x/Y]: X-NPCortex-CPlus-Token: SECRETTOKENVALUE0123456789 Bearer abcDEF123456.zz password=hunter2`);

// one simulated run, laid out in wall-clock time starting at `base`
const META = { awaits: [], gates: [], checkpoint: null, rejoin: null, done: null, begins: [] };
const GATEROWS = [];
function emitRun(s, base, o = {}) {
  const at = tick => new Date(base + tick * 50);
  for (const l of s.log) { const T = iso(at(l.tick)); let m;
    if ((m = /^\[G5\] AWAIT (\S+)/.exec(l.text))) META.awaits.push({ id: m[1], time: T });
    else if ((m = /^\[G5\] GATE (\S+) RELEASED/.exec(l.text))) META.gates.push({ id: m[1], time: T });
    else if ((m = /^\[G5\] BEGIN (\S+)/.exec(l.text))) META.begins.push({ id: m[1], time: T });
    else if (l.text.startsWith('[G5] CHECKPOINT')) META.checkpoint = T; else if (l.text.startsWith('[G5] REJOIN')) META.rejoin = T; else if (l.text.startsWith('[G5] SEQUENCE_DONE')) META.done = T; }
  if (!o.noGateRows) for (const g of (s.gates || [])) GATEROWS.push({ time: iso(at(g.tick)).slice(0, 19), scenario: g.id, decision: 'GO', reason: 'fixture (mock gate)', command: '/function npcortex_g5:gate/' + g.id });
  const server = (tick, text) => {
    L.push(`[${stamp(at(tick))}] [Server thread/INFO] [net.minecraft.server.MinecraftServer/]: [Not Secure] [Server] ${text}`);
    L.push(`[${stamp(new Date(at(tick).getTime() + 4))}] [Render thread/INFO] [net.minecraft.client.gui.components.ChatComponent/]: [System] [CHAT] [Server] ${text}`);   // client chat echo, as in the real log
  };
  const c2 = (tick, goal, result, detail) => L.push(`[${stamp(at(tick))}] [Server thread/INFO] [io.github.sirp00m.npcortex.C2AgentRuntime/]: NPCortex C2 goal=${goal} actor=${uuid()} result=${result} detail=${detail}`);
  for (const l of s.log) if (l.text.startsWith('[G5]') && !(o.dropGate && l.text.startsWith('[G5] GATE ' + o.dropGate + ' '))) server(l.tick, l.text);
  if (o.logOnlyStart) return;
  const begins = s.log.filter(l => l.text.startsWith('[G5] BEGIN ')).map(l => ({ tick: l.tick, id: l.text.split(' ')[2] }));
  const winOf = t => { let w = null; for (const b of begins) if (b.tick <= t) w = b; return w; };
  const prevOf = id => { const i = begins.findIndex(b => b.id === id); return i > 0 ? begins[i - 1] : null; };
  const goals = {}; const goalOf = (scen, npc) => (goals[scen + '/' + npc] = goals[scen + '/' + npc] || uuid());
  const hasGoal = id => /^p(02|03|04|05|06|07|08|09|10|11|13|14|15|19|20|21|22)_/.test(id);
  const terminal = {};      // scen/npc -> 'COMPLETED' | 'FAILED' | 'CANCELLED'
  for (const ev of s.events) {
    const w = winOf(ev.tick); if (!w || !hasGoal(w.id)) continue;
    terminal[w.id + '/' + ev.npc] = ev.type === 'COMPLETE' ? 'COMPLETED' : ev.type === 'FAIL' ? 'FAILED' : 'CANCELLED';
    const detail = ev.type === 'COMPLETE' ? 'C0 action=succeeded verified=true local=true' : 'C0 action=failed verified=false local=false';
    c2(ev.tick + 2, goalOf(w.id, ev.npc), terminal[w.id + '/' + ev.npc], detail);
    if (o.dupterm && w.id === 'p04_navlong_80' && ev.type === 'COMPLETE') c2(ev.tick + 4, goalOf(w.id, ev.npc), 'COMPLETED', 'C0 action=succeeded verified=true local=true');
  }
  if (o.nosnap) return;
  const evd = path.join(out, 'run', 'saves', 'NPCortex_G5_fixture', 'data', 'npcortex', 'm03-evidence'); fs.mkdirSync(evd, { recursive: true });
  let n = 0;
  for (const sn of s.snapshots) {
    if (sn.x === null) continue;
    const w = winOf(sn.tick); if (!w) continue;
    let journal = null;
    const lateStart = (sn.tick - w.tick) <= 40;          // the pre-dispatch snapshot of a scenario still shows the previous goal
    if (w.id === 'p12_rejoin_no_replay') journal = { goal_id: goalOf('p11_savequit_checkpoint', 'yui'), action_id: goalOf('act:p11', 'yui'), state: 'CANCELLED' };
    else if (hasGoal(w.id) && lateStart && sn.state === 'NONE') { const pv = prevOf(w.id); if (pv && hasGoal(pv.id) && goals[pv.id + '/' + sn.npc]) journal = { goal_id: goals[pv.id + '/' + sn.npc], action_id: goalOf('act:' + pv.id, sn.npc), state: terminal[pv.id + '/' + sn.npc] || 'COMPLETED' }; }
    else if (hasGoal(w.id)) {
      const t = terminal[w.id + '/' + sn.npc];
      const state = sn.state !== 'NONE' ? sn.state : (t || null);
      if (state) journal = { goal_id: goalOf(w.id, sn.npc), action_id: goalOf('act:' + w.id, sn.npc), state };
    }
    const j = { world_id: 'w', actor_id: 'a', session_id: 's', dimension_id: 'minecraft:overworld', capture_tick: sn.tick, name: sn.npc, profile_id: uuid(), x: sn.x + (o.shift && w.id === o.shift ? 30 : 0), y: -60, z: sn.z, run_id: 'r', label: 'before-snapshot', captured_utc: at(sn.tick).toISOString() };
    if (journal) j.c2_journal = journal;
    fs.writeFileSync(path.join(evd, `run-${base}-${++n}.json`), JSON.stringify(j));
  }
}

const perfAt = {};   // npc_count -> Date
const perf = (count, trans) => ({ kind: 'performance', run_id: 'r', npc_count: count, measurement_id: uuid(), status: 'MEASURED_NOT_ACCEPTED', samples: 2400, duration_seconds: 120, tick_event_mean_ms: 0.4, tick_event_p95_ms: 0.9, tick_event_max_ms: 3.1, server_average_tick_ms: 1.7, navigation_transitions: trans });
const tr = (name, st, sec) => ({ name, actor_id: 'a', seconds: sec, initial: false, active: true, state: st, goal_id: 'g' });

fs.mkdirSync(path.join(out, 'run', 'logs'), { recursive: true });
fs.mkdirSync(path.join(out, 'run', 'saves', 'NPCortex_G5_fixture', 'datapacks'), { recursive: true });
const base = Date.now() - 4 * 3600 * 1000;
if (mode === 'stale') {
  // an OLD complete run, then a new START with nothing after it: the old PASS evidence must not be credited to the new run
  emitRun(fullRun(), base - 3 * 3600 * 1000);
  emitRun({ log: [{ tick: 0, text: '[G5] START ALL' }], snapshots: [], events: [] }, base, { logOnlyStart: true });
} else if (mode === 'real') {
  // the log is the real excerpt; evidence comes from a synthetic run placed OUTSIDE its time range (must be ignored)
  emitRun(fullRun(), base - 5 * 3600 * 1000);
} else if (mode === 'hang') {
  emitRun(hangRun(80000), base, { noGateRows: true });
} else if (mode === 'realp00') {
  // REAL data: log excerpt + the two M03 snapshots of the first revision-3 probe run; the snapshots' UTC stamps are re-based to this machine's time zone (the original run was UTC+7)
  const evd0 = path.join(out, 'run', 'saves', 'NPCortex_G5_fixture', 'data', 'npcortex', 'm03-evidence'); fs.mkdirSync(evd0, { recursive: true });
  const rd = path.join(__dirname, 'fixtures', 'm03-real-p00-rev3');
  for (const f of fs.readdirSync(rd)) { const j = JSON.parse(fs.readFileSync(path.join(rd, f), 'utf8')); const w = new Date(new Date(j.captured_utc).getTime() + 7 * 3600 * 1000); j.captured_utc = new Date(w.getUTCFullYear(), w.getUTCMonth(), w.getUTCDate(), w.getUTCHours(), w.getUTCMinutes(), w.getUTCSeconds(), w.getUTCMilliseconds()).toISOString(); fs.writeFileSync(path.join(evd0, f), JSON.stringify(j)); }
} else if (mode !== 'nolog') {
  const s = (mode === 'fb' || mode === 'nogo' || mode === 'timer') ? fullRun({ v1: false, v2: false }, true) : fullRun();
  emitRun(s, base, { dupterm: mode === 'dupterm', nosnap: mode === 'nosnap', shift: mode === 'inconsistent' ? 'p03_navlong_40' : null, noGateRows: mode === 'nogo', dropGate: mode === 'timer' ? 'p03_navlong_40' : null });
}
if (mode === 'real') fs.copyFileSync(path.join(__dirname, 'fixtures', 'forge-real-p01-blocked.log'), path.join(out, 'run', 'logs', 'latest.log'));
else if (mode === 'realp00') fs.copyFileSync(path.join(__dirname, 'fixtures', 'forge-real-p00-rev3.log'), path.join(out, 'run', 'logs', 'latest.log'));
else if (mode !== 'nolog') fs.writeFileSync(path.join(out, 'run', 'logs', 'latest.log'), L.join('\n') + '\n');
const ev = path.join(out, 'run', 'saves', 'NPCortex_G5_fixture', 'data', 'npcortex', 'm03-evidence'); fs.mkdirSync(ev, { recursive: true });
fs.writeFileSync(path.join(ev, 'performance-p10.json'), JSON.stringify(perf(1, ['DECIDED', 'MOVING', 'REPLANNING', 'MOVING', 'REPLANNING', 'CANCEL_PENDING'].map((x, i) => tr('yui', x, i)))));
for (const c of [1, 3, 5, 10]) { const f = path.join(ev, `performance-${c}.json`); fs.writeFileSync(f, JSON.stringify(perf(c, [tr('yui', 'COMPLETED', 1)]))); const a = META.awaits.find(x => x.id === ({ 1: 'p19_multi_1_npc', 3: 'p20_multi_3_npc', 5: 'p21_multi_5_npc', 10: 'p22_multi_10_npc' })[c]); const b = META.begins.find(x => x.id === ({ 1: 'p19_multi_1_npc', 3: 'p20_multi_3_npc', 5: 'p21_multi_5_npc', 10: 'p22_multi_10_npc' })[c]); const when = a ? new Date(new Date(a.time).getTime() - 1000) : (b ? new Date(new Date(b.time).getTime() + 150000) : null); if (when) fs.utimesSync(f, when, when); }
const db = path.join(out, 'backend', 'npcortex.sqlite3'); fs.mkdirSync(path.dirname(db), { recursive: true });
require('child_process').execFileSync('python', ['-I', '-c', `import sqlite3;c=sqlite3.connect(r'${db}');c.execute("create table agent_goals(goal_id text,status text)");c.execute("create table agent_actions(action_id text,goal_id text,status text,verdict text)");c.execute("create table cplus_e3_g2_moves(task_id text,state text,token_hint text)");c.execute("insert into agent_goals values('g','completed')");c.execute("insert into cplus_e3_g2_moves values('t','RESERVED','x')");c.commit()`]);
fs.mkdirSync(path.join(out, 'out'), { recursive: true });
if (GATEROWS.length) fs.writeFileSync(path.join(out, 'out', 'g5-gate-log.jsonl'), GATEROWS.map(r => JSON.stringify(r)).join(String.fromCharCode(10)) + String.fromCharCode(10));
fs.writeFileSync(path.join(out, 'meta.json'), JSON.stringify(META));
console.log('fixture written to ' + out + ' mode=' + mode);
