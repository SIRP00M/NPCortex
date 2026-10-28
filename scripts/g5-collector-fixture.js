#!/usr/bin/env node
// TEST-ONLY: writes a SYNTHETIC run folder (Forge-format log, M03-style evidence JSON, SQLite) from the controller simulation so that
// scripts/collect-g5-evidence.ps1 can be exercised without Minecraft. The data is invented by a mock; it proves the collector's
// parsing and verdict rules only, never NPCortex behaviour.
//   usage: node g5-collector-fixture.js <outDir> <mode>
//   Rev6 modes: nodiag (no D1 lines) | diagactive (D1-B says LEG_ACTIVE) | diagmismatch (D1-B names another goal) | p08other (D1-A reason LONG_LEG_LIMIT) | nofinalize | wrongtask | nog3 | taint | nobacklog | oldgate | realp11
//   modes: ok | fb | nolog | real | realp00 | inconsistent | dupterm | nosnap | stale | hang | timer | nogo | nostatus | regfail | regfailnojournal | dupstatus | staleperf | noprofiles | dupactor
//   nostatus = no `c2 status` chat samples (p10 LEG_QUIESCING coverage must stay UNVERIFIED); regfail = lane g5a03 got a registration error (no profile row);
//   noprofiles = the database has no profile table; dupactor = two lanes share one actor id
//   fb = owner-gated full run (+ g5-gate-log.jsonl GO rows); nogo = same without the gate log; timer = a gate release removed from the log (timer-only progression);
//   hang = a goal that never ends (awaits forever); realp00 = REAL log excerpt + REAL M03 snapshots of the first revision-3 p00 run
'use strict';
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const { fullRun, hangRun } = require('./simulate-g5-datapack.js');
const out = path.resolve(process.argv[2] || 'g5-fixture');
const mode = process.argv[3] || 'ok';
const uuid = () => crypto.randomUUID();
const ACTORS = {}; const actorOf = n => (ACTORS[n] = ACTORS[n] || uuid());   // one stable actor id per NPC, shared by the log lines and the profile table
const has = m => mode === m;
const FAILLANE = (mode === 'regfail' || mode === 'regfailnojournal') ? 'g5a03' : null;
if (mode === 'dupactor') ACTORS['g5a03'] = actorOf('g5a02');   // two lanes claim one actor id
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
  if (!o.noGateRows) for (const g of (s.gates || [])) GATEROWS.push({ time: iso(at(g.tick)).slice(0, 19), run_id: 'fixture', scenario: g.id, decision: 'GO', reason: 'fixture (mock gate)', command: '/function npcortex_g5:gate/' + g.id });
  const server = (tick, text) => {
    L.push(`[${stamp(at(tick))}] [Server thread/INFO] [net.minecraft.server.MinecraftServer/]: [Not Secure] [Server] ${text}`);
    L.push(`[${stamp(new Date(at(tick).getTime() + 4))}] [Render thread/INFO] [net.minecraft.client.gui.components.ChatComponent/]: [System] [CHAT] [Server] ${text}`);   // client chat echo, as in the real log
  };
  const c2 = (tick, goal, result, detail, npc) => L.push(`[${stamp(at(tick))}] [Server thread/INFO] [io.github.sirp00m.npcortex.C2AgentRuntime/]: NPCortex C2 goal=${goal} actor=${actorOf(npc)} result=${result} detail=${detail}`);
  const interrupted = (tick, goal, npc, reason) => L.push(`[${stamp(at(tick))}] [Server thread/INFO] [io.github.sirp00m.npcortex.C2AgentRuntime/]: NPCortex C2 interrupted goal=${goal} actor=${actorOf(npc)} pendingReconciliation=false reason=${reason}`);
  for (const l of s.log) if (l.text.startsWith('[G5]') && !(o.dropGate && l.text.startsWith('[G5] GATE ' + o.dropGate + ' '))) server(l.tick, l.text);
  // the chat answer of `c2 status` (client Render thread echo of the command feedback), only when the mode keeps it
  if (o.dupStatus) for (const st of (s.statuses || [])) L.push(`[${stamp(new Date(at(st.tick).getTime() + 900))}] [Render thread/INFO] [net.minecraft.client.gui.components.ChatComponent/]: [System] [CHAT] ${st.text}`);   // a second, hand-typed-looking sample
  if (!o.noStatus) for (const st of (s.statuses || [])) L.push(`[${stamp(new Date(at(st.tick).getTime() + 8))}] [Render thread/INFO] [net.minecraft.client.gui.components.ChatComponent/]: [System] [CHAT] ${st.text}`);
  if (!o.noFinalize) for (const t of (s.typed || [])) {
    const text = o.wrongTask ? t.text.replace('SIM-TASK', 'OTHER-TASK') : t.text;
    L.push(`[${stamp(at(t.tick))}] [Render thread/INFO] [net.minecraft.client.gui.components.ChatComponent/]: [System] [CHAT] ${text}`);
  }
  if (o.logOnlyStart) return;
  const begins = s.log.filter(l => l.text.startsWith('[G5] BEGIN ')).map(l => ({ tick: l.tick, id: l.text.split(' ')[2] }));
  const winOf = t => { let w = null; for (const b of begins) if (b.tick <= t) w = b; return w; };
  const prevOf = id => { const i = begins.findIndex(b => b.id === id); return i > 0 ? begins[i - 1] : null; };
  const goals = GOALS_EXPORT; const goalOf = (scen, npc) => (goals[scen + '/' + npc] = goals[scen + '/' + npc] || uuid());
  const hasGoal = id => /^p(02|03|04|05|06|07|08|09|10|11|13|14|15|18|19|20|21|22)_/.test(id);
  // p10 runs up to five attempts; each attempt has its own goal, action and terminal state
  const attStarts = s.log.filter(l => /^\[G5\] NOTE attempt \d$/.test(l.text)).map(l => ({ tick: l.tick, n: +l.text.slice(-1) }));
  const attOf = t => { let a = 0; for (const x of attStarts) if (x.tick <= t) a = x.n; return a; };
  const attTick = t => { let a = -1; for (const x of attStarts) if (x.tick <= t) a = x.tick; return a; };
  const key = (wid, t) => (wid === 'p10_cancel_quiescing' ? wid + '#' + attOf(t) : wid);
  const prevKey = (wid, t) => (wid === 'p10_cancel_quiescing' && attOf(t) > 1 ? wid + '#' + (attOf(t) - 1) : null);
  const terminal = {};      // scen/npc -> 'COMPLETED' | 'FAILED' | 'CANCELLED'
  for (const ev of s.events) {
    const w = winOf(ev.tick); if (!w || !hasGoal(w.id)) continue;
    const wk = key(w.id, ev.tick);
    terminal[wk + '/' + ev.npc] = ev.type === 'COMPLETE' ? 'COMPLETED' : ev.type === 'FAIL' ? 'FAILED' : 'CANCELLED';
    const detail = ev.type === 'COMPLETE' ? 'C0 action=succeeded verified=true local=true' : 'C0 action=failed verified=false local=false';
    if (o.failLane && ev.npc === o.failLane && /^p(19|20|21|22)_/.test(w.id)) { terminal[wk + '/' + ev.npc] = 'INTERRUPTED'; interrupted(ev.tick + 2, goalOf(wk, ev.npc), ev.npc, 'Character Profile NOT registered: Backend returned HTTP 400: Character pack not found or invalid'); continue; }
    // Rev6 production diagnostics D1 (log only), written in the tick of the event, before the terminal bookkeeping
    if (!o.noDiag && ev.type === 'CANCEL') {
      const sn = s.snapshots.find(x => x.tick === ev.tick && x.npc === ev.npc);
      const quiesce = sn && sn.state === 'REPLANNING';
      const g = o.diagMismatch ? uuid() : goalOf(wk, ev.npc);
      L.push(`[${stamp(new Date(at(ev.tick).getTime() + 3))}] [Server thread/INFO] [io.github.sirp00m.npcortex.C2AgentRuntime/]: NPCortex C2 diag cancel actor=${actorOf(ev.npc)} goal=${g} phase=${quiesce && !o.diagActive ? 'REPLANNING' : 'MOVING'} long=${quiesce && !o.diagActive ? 'LEG_QUIESCING' : 'LEG_ACTIVE'}`);
    }
    if (!o.noDiag && ev.type === 'FAIL') L.push(`[${stamp(new Date(at(ev.tick).getTime() + 1))}] [Server thread/INFO] [io.github.sirp00m.npcortex.C2AgentRuntime/]: NPCortex C2 diag result actor=${actorOf(ev.npc)} goal=${goalOf(wk, ev.npc)} succeeded=false phase=MOVING long=LEG_ACTIVE reason=${o.p08Other ? 'LONG_LEG_LIMIT legs=40 best-effort heuristic; no path claim' : 'LONG_NO_PROGRESS legs=6 best-effort heuristic; no path claim'}`);
    c2(ev.tick + 2, goalOf(wk, ev.npc), terminal[wk + '/' + ev.npc], detail, ev.npc);
    if (o.dupterm && w.id === 'p04_navlong_80' && ev.type === 'COMPLETE') c2(ev.tick + 4, goalOf(wk, ev.npc), 'COMPLETED', 'C0 action=succeeded verified=true local=true', ev.npc);
  }
  if (o.nosnap) return;
  const evd = path.join(out, 'run', 'saves', 'NPCortex_G5_fixture', 'data', 'npcortex', 'm03-evidence'); fs.mkdirSync(evd, { recursive: true });
  let n = 0;
  for (const sn of s.snapshots) {
    if (sn.x === null) continue;
    const w = winOf(sn.tick); if (!w) continue;
    let journal = null;
    const lateStart = (sn.tick - w.tick) <= 40;          // the pre-dispatch snapshot of a scenario still shows the previous goal
    const kk = key(w.id, sn.tick), pk = prevKey(w.id, sn.tick), lateAtt = pk && (sn.tick - attTick(sn.tick)) <= 40 && sn.state === 'NONE';
    if (lateAtt && goals[pk + '/' + sn.npc]) journal = { goal_id: goals[pk + '/' + sn.npc], action_id: goalOf('act:' + pk, sn.npc), state: terminal[pk + '/' + sn.npc] || 'CANCELLED' };
    else if (w.id === 'p12_rejoin_no_replay') journal = { goal_id: goalOf('p11_savequit_checkpoint', 'yui'), action_id: goalOf('act:p11', 'yui'), state: 'CANCELLED' };
    else if (hasGoal(w.id) && lateStart && sn.state === 'NONE') { const pv = prevOf(w.id); if (pv && hasGoal(pv.id) && goals[key(pv.id, 1e12) + '/' + sn.npc]) { const pkey = key(pv.id, 1e12); journal = { goal_id: goals[pkey + '/' + sn.npc], action_id: goalOf('act:' + pkey, sn.npc), state: terminal[pkey + '/' + sn.npc] || 'COMPLETED' }; } }
    else if (hasGoal(w.id)) {
      const t = terminal[kk + '/' + sn.npc];
      const state = sn.state !== 'NONE' ? sn.state : (t || null);
      if (state) journal = { goal_id: goalOf(kk, sn.npc), action_id: goalOf('act:' + kk, sn.npc), state };
    }
    if (o.failNoJournal && o.failLane && sn.npc === o.failLane && /^p(19|20|21|22)_/.test(w.id)) journal = null;   // rejected before any journal row/goal id existed
    const j = { world_id: 'w', actor_id: actorOf(sn.npc), session_id: 's', dimension_id: 'minecraft:overworld', capture_tick: sn.tick, name: sn.npc, profile_id: uuid(), x: sn.x + (o.shift && w.id === o.shift ? 30 : 0), y: -60, z: sn.z, run_id: 'r', label: 'before-snapshot', captured_utc: at(sn.tick).toISOString() };
    if (journal) j.c2_journal = journal;
    fs.writeFileSync(path.join(evd, `run-${base}-${++n}.json`), JSON.stringify(j));
  }
}

const GOALS_EXPORT = {};   // goal ids by scenario/npc, shared with the database rows below
const perfAt = {};   // npc_count -> Date
const perf = (count, trans) => ({ kind: 'performance', run_id: 'r', npc_count: count, measurement_id: uuid(), status: 'MEASURED_NOT_ACCEPTED', samples: 2400, duration_seconds: 120, tick_event_mean_ms: (has('taint') && count === 10 ? 86393.53290541668 : 0.4), tick_event_p95_ms: 0.9, tick_event_max_ms: (has('taint') && count === 10 ? 207341813.263 : 3.1), server_average_tick_ms: 1.7, navigation_transitions: trans });
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
} else if (mode === 'realp11') {
  // REAL Rev5 data: the two M03 snapshots of the p11 checkpoint (an earlier HTTP_PENDING one 1.0 s before, the MOVING one in the checkpoint tick) with the real
  // timestamps of the Rev5 run (UTC+7 wall clock re-based to this machine's time zone). The log holds only the marker lines the collector needs.
  const evd0 = path.join(out, 'run', 'saves', 'NPCortex_G5_fixture', 'data', 'npcortex', 'm03-evidence'); fs.mkdirSync(evd0, { recursive: true });
  const rd = path.join(__dirname, 'fixtures', 'm03-real-p11-rev5');
  for (const f of fs.readdirSync(rd)) {
    const j = JSON.parse(fs.readFileSync(path.join(rd, f), 'utf8'));
    const w = new Date(new Date(j.captured_utc.replace(/(\.\d{3})\d*Z$/, '$1Z')).getTime() + 7 * 3600 * 1000);
    j.captured_utc = new Date(w.getUTCFullYear(), w.getUTCMonth(), w.getUTCDate(), w.getUTCHours(), w.getUTCMinutes(), w.getUTCSeconds(), w.getUTCMilliseconds()).toISOString();
    fs.writeFileSync(path.join(evd0, f), JSON.stringify(j));
  }
  const st = (t, thr, src, m) => `[27Oct2026 ${t}] [${thr}/INFO] [${src}/]: ${m}`;
  for (const [t, m] of [['18:37:50.446', '[Not Secure] [Dev] [G5] START ALL'], ['18:54:38.935', '[Not Secure] [Server] [G5] BEGIN p11_savequit_checkpoint'], ['18:54:44.934', '[Not Secure] [Server] [G5] CHECKPOINT save_quit NPC is walking a navlong goal; world will be saved by Save and Quit']])
    L.push(st(t, 'Server thread', 'net.minecraft.server.MinecraftServer', m));
} else if (mode !== 'nolog') {
  const s = (mode === 'fb' || mode === 'nogo' || mode === 'timer') ? fullRun({ v1: false, v2: false }, true) : fullRun();
  emitRun(s, base, { noDiag: has('nodiag'), diagActive: has('diagactive'), diagMismatch: has('diagmismatch'), p08Other: has('p08other'), noFinalize: has('nofinalize'), wrongTask: has('wrongtask'), noStatus: mode === 'nostatus', dupStatus: mode === 'dupstatus', failNoJournal: mode === 'regfailnojournal', failLane: FAILLANE, dupterm: mode === 'dupterm', nosnap: mode === 'nosnap', shift: mode === 'inconsistent' ? 'p03_navlong_40' : null, noGateRows: mode === 'nogo', dropGate: mode === 'timer' ? 'p03_navlong_40' : null });
}
if (mode === 'real') fs.copyFileSync(path.join(__dirname, 'fixtures', 'forge-real-p01-blocked.log'), path.join(out, 'run', 'logs', 'latest.log'));
else if (mode === 'realp00') fs.copyFileSync(path.join(__dirname, 'fixtures', 'forge-real-p00-rev3.log'), path.join(out, 'run', 'logs', 'latest.log'));
if (mode === 'real' || mode === 'realp00') { const t = new Date(); fs.utimesSync(path.join(out, 'run', 'logs', 'latest.log'), t, t); }   // CopyFile keeps the source's mtime: the collector's look-back window must not depend on when the fixture was committed
else if (mode !== 'nolog') fs.writeFileSync(path.join(out, 'run', 'logs', 'latest.log'), L.join('\n') + '\n');
const ev = path.join(out, 'run', 'saves', 'NPCortex_G5_fixture', 'data', 'npcortex', 'm03-evidence'); fs.mkdirSync(ev, { recursive: true });
fs.writeFileSync(path.join(ev, 'performance-p10.json'), JSON.stringify(perf(1, ['DECIDED', 'MOVING', 'REPLANNING', 'MOVING', 'REPLANNING', 'CANCEL_PENDING'].map((x, i) => tr('yui', x, i)))));
for (const c of [1, 3, 5, 10]) { const f = path.join(ev, `performance-${c}.json`); fs.writeFileSync(f, JSON.stringify(perf(c, [tr('yui', 'COMPLETED', 1)]))); const a = META.awaits.find(x => x.id === ({ 1: 'p19_multi_1_npc', 3: 'p20_multi_3_npc', 5: 'p21_multi_5_npc', 10: 'p22_multi_10_npc' })[c]); const b = META.begins.find(x => x.id === ({ 1: 'p19_multi_1_npc', 3: 'p20_multi_3_npc', 5: 'p21_multi_5_npc', 10: 'p22_multi_10_npc' })[c]); const bi = META.begins.indexOf(b); const nb = bi >= 0 ? META.begins[bi + 1] : null; const span = b && nb ? Math.min(150000, new Date(nb.time).getTime() - new Date(b.time).getTime() - 1000) : 150000;   // the 120 s measurement is written inside the scenario window
  const when = a ? new Date(new Date(a.time).getTime() - 1000) : (b ? new Date(new Date(b.time).getTime() + span) : null); if (mode === 'staleperf') { const old = new Date(base - 5 * 3600 * 1000); fs.utimesSync(f, old, old); } else if (when) fs.utimesSync(f, when, when); }
// isolated G5 run folder (what scripts/start-g5-backend.ps1 + g5-preflight.ps1 produce): marker, passing preflight, ONE database inside it
const g5run = path.join(out, 'g5', 'run-fixture'); fs.mkdirSync(g5run, { recursive: true });
const db = path.join(g5run, 'backend.sqlite3');
fs.writeFileSync(path.join(g5run, 'G5-ISOLATED.marker.json'), JSON.stringify({ kind: 'npcortex-g5-isolation-marker-v1', run_id: 'fixture', db_path: db, repo_default_db: path.join(__dirname, '..', 'backend', 'data', 'npcortex.sqlite3'), created_utc: new Date().toISOString().slice(0, 19) + 'Z' }));
fs.writeFileSync(path.join(g5run, 'preflight.json'), JSON.stringify({ status: 'PASS', phase: 'Start', run_id: 'fixture', db_path: db }));
const P18GOAL = GOALS_EXPORT['p18_regress_e3_taskmove/yui'] || 'none';
const lanes = Object.keys(ACTORS).filter(n => n !== FAILLANE);
const py = ['import sqlite3', `c=sqlite3.connect(r'${db}')`,
  'c.execute("create table agent_goals(goal_id text,status text)")', 'c.execute("create table agent_actions(action_id text,goal_id text,status text,verdict text)")',
  'c.execute("create table cplus_e3_g2_moves(task_id text,state text,goal_id text,token_hint text)")', 'c.execute("create table cplus_e3_g3_terminals(task_id text,outcome text,evidence_hash text,evidence_json text,finalized_at integer)")', 'c.execute("create table worlds(world_id text,name text)")',
  'c.execute("create table actors(actor_id text,world_id text,dimension_id text,character_id text)")',
  "c.execute(\"insert into agent_goals values('g','completed')\")", `c.execute("insert into cplus_e3_g2_moves values('SIM-TASK','C2_ACCEPTED','${P18GOAL}','x')")`];
if (!has('nog3')) py.push(`c.execute("insert into cplus_e3_g3_terminals values('SIM-TASK','COMPLETED','${'cd'.repeat(32)}','{}',1)")`);
if (mode !== 'noprofiles') {
  py.push('c.execute("create table character_profiles(actor_id text,revision integer,source text,display_name text,composition text,content_sha256 text)")');
  lanes.forEach((n, k) => {
    const a = actorOf(n);
    py.push(`c.execute("insert into actors values('${a}','w','minecraft:overworld','@profile')")`);
    py.push(`c.execute("insert into character_profiles values('${a}',1,'default','${n}','structured','${'ab'.repeat(32)}')")`);
  });
}
py.push('c.execute("PRAGMA user_version=5")'); py.push('c.commit()');
require('child_process').execFileSync('python', ['-I', '-c', py.join(';')]);
fs.mkdirSync(path.join(out, 'out'), { recursive: true });
const NL = String.fromCharCode(10);
if (has('oldgate')) {
  // only the GLOBAL log exists: rows of an older run, rows without any run id, and the p08 row of THIS run
  const rows = [];
  for (const r of GATEROWS) { rows.push(Object.assign({}, r, { run_id: 'older-run' })); const q = Object.assign({}, r); delete q.run_id; rows.push(q); if (r.scenario === 'p08_large_wall_no_progress') rows.push(r); }
  fs.writeFileSync(path.join(out, 'out', 'g5-gate-log.jsonl'), rows.map(r => JSON.stringify(r)).join(NL) + NL);
} else if (GATEROWS.length) fs.writeFileSync(path.join(g5run, 'gate-log.jsonl'), GATEROWS.map(r => JSON.stringify(r)).join(NL) + NL);
// the verified launcher metadata + the isolated backend logs (what scripts/start-g5-backend.ps1 writes) and the health recorded before the backend was stopped
const errLog = path.join(g5run, 'backend.err.log'), outLog = path.join(g5run, 'backend.out.log');
fs.writeFileSync(path.join(g5run, 'backend-run.json'), JSON.stringify({ pid: 0, port: 1, db_path: db, out_log: outLog, err_log: errLog, started_utc: new Date().toISOString().slice(0, 19) + 'Z' }));
if (!has('nobacklog')) {
  fs.writeFileSync(errLog, ['INFO:     127.0.0.1:50000 - "POST /v1/worlds HTTP/1.1" 201 Created', 'INFO:     127.0.0.1:50000 - "POST /v1/actors HTTP/1.1" 201 Created', 'INFO:     127.0.0.1:50000 - "GET /health HTTP/1.1" 200 OK'].join(NL) + NL + 'INFO:     partial line still being writ');
  fs.writeFileSync(outLog, '');
}
fs.writeFileSync(path.join(g5run, 'backend-stop.json'), JSON.stringify({ run_id: 'fixture', stopped_utc: new Date().toISOString().slice(0, 19) + 'Z', health_before_stop: '{"status": "ok"}', pid: 0 }));
fs.writeFileSync(path.join(out, 'meta.json'), JSON.stringify(META));
console.log('fixture written to ' + out + ' mode=' + mode);
