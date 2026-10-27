#!/usr/bin/env node
// TEST-ONLY deterministic simulation of the NPCortex G5 data pack controller (scoreboard state machine), revision 4.
// It interprets the pack's own .mcfunction files against a MOCK world (mock NPC motion, mock npcortex commands, a mock model of the vanilla
// 1.20.1 selector resolution) to test sequencing logic: probe v2, adaptive/owner-gated modes, AWAIT + owner gate, no timer-only progression,
// result codes, cancel triggers, checkpoint/rejoin, stale-run protection, group starters.
// The selector model follows the decompiled 1.20.1 code (EntitySelector.findPlayers/findEntities, EntitySelectorOptions): a positive type=player reads the
// PlayerList unless x/y/z/dx/dy/dz are present (then ServerLevel.players); @a always reads the PlayerList; other @e selectors read the level entities;
// x/y/z also override the origin of distance=. It proves controller logic only. It is NOT evidence about real NPCortex / Minecraft behaviour.
'use strict';
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const packDir = path.resolve(__dirname, '..', 'integrations', 'datapacks', 'NPCortex_G5', 'data', 'npcortex_g5', 'functions');
let assertions = 0;
function ok(c, m) { assertions++; if (!c) throw new Error('ASSERT: ' + m); }

class Sim {
  constructor(profile = {}) {
    this.p = Object.assign({ decide: 100, speed: 0.215, pausePeriod: 60, pauseLen: 30, loaded: (x, z) => Math.abs(x) <= 192 && Math.abs(z) <= 192,
      ignoreStart: () => false, stallAtX: null, v1: true, v2: true, tagFail: false, noData: false, extraPlayer: false, playerListIncludes: false,
      movesAfterRejoin: false, noStop: false, autoGate: true, quietTicks: 300 }, profile);
    if (profile.selectable === false) { this.p.v1 = false; this.p.v2 = false; }
    this.score = new Map(); this.log = []; this.sched = []; this.tick = 0;
    this.op = { name: 'Op', type: 'player', pos: [0.5, -60, 0.5], tags: new Set() };
    this.extra = this.p.extraPlayer ? { name: 'Steve', type: 'player', tags: new Set(), pos: [5, -60, 5] } : null;
    this.npcs = new Map(); this.session = false; this.snapshots = []; this.stopped = false; this.rejoined = false;
    this.cache = new Map(); this.cmds = []; this.selLog = []; this.events = []; this.gates = []; this.snapBy = {};
    Sim.last = this;
  }
  fnLines(name) {
    if (!this.cache.has(name)) {
      const f = path.join(packDir, name + '.mcfunction');
      if (!fs.existsSync(f)) throw new Error('missing function ' + name);
      this.cache.set(name, fs.readFileSync(f, 'utf8').split('\n').filter(l => l.trim() && !l.trim().startsWith('#')));
    }
    return this.cache.get(name);
  }
  get(h) { return this.score.has(h) ? this.score.get(h) : null; }
  say(t) { this.log.push({ tick: this.tick, text: t }); }
  // ---- selectors (model of vanilla 1.20.1 resolution, see the header)
  select(sel, ctx) {
    const m = /^@([aes])(?:\[(.*)\])?$/.exec(sel); if (!m) throw new Error('selector ' + sel);
    const args = []; (m[2] || '').split(',').filter(Boolean).forEach(kv => { const i = kv.indexOf('='); args.push([kv.slice(0, i), kv.slice(i + 1)]); });
    const one = k => (args.find(a => a[0] === k) || [])[1];
    if (args.some(a => a[0] === 'tag' && /^!?g5_npc/.test(a[1]))) this.selLog.push(this.tick);   // selector-based observation attempted
    const human = [this.op].concat(this.extra ? [this.extra] : []);
    const npcs = [...this.npcs.values()];
    const limited = ['x', 'y', 'z', 'dx', 'dy', 'dz'].some(k => one(k) !== undefined);          // setWorldLimited()
    let c;
    if (m[1] === 's') c = ctx.exec ? [ctx.exec] : [];
    else if (m[1] === 'a') c = human.concat(this.p.playerListIncludes ? npcs : []);                 // PlayerList
    else if (one('type') === 'player') c = limited ? human.concat(this.p.v1 ? npcs : []) : human.concat(this.p.playerListIncludes ? npcs : []);   // includesEntities=false
    else c = human.concat(this.p.v2 ? npcs : []);                                                 // level entity lookup
    const origin = [0, 1, 2].map(i => { const v = one(['x', 'y', 'z'][i]); return v !== undefined ? parseFloat(v) : ctx.pos[i]; });   // x/y/z override the distance origin
    if (one('dx') !== undefined || one('dy') !== undefined || one('dz') !== undefined) {
      const d = [one('dx'), one('dy'), one('dz')].map(v => (v === undefined ? 0 : parseFloat(v)));
      c = c.filter(e => [0, 1, 2].every(i => e.pos[i] >= origin[i] && e.pos[i] < origin[i] + d[i] + 1));
    }
    const ty = one('type');
    c = c.filter(e => (!ty || (ty === 'player' ? e.type === 'player' : ty.startsWith('!') ? e.type !== ty.slice(1) : e.type === ty))
      && (one('name') === undefined || e.name === one('name'))                                   // e.name = GameProfile name, never the character name
      && args.filter(a => a[0] === 'tag').every(([, v]) => v.startsWith('!') ? !e.tags.has(v.slice(1)) : e.tags.has(v)));
    if (one('distance') !== undefined) { const r = parseFloat(one('distance').replace('..', '')); c = c.filter(e => Math.hypot(e.pos[0] - origin[0], e.pos[1] - origin[1], e.pos[2] - origin[2]) <= r); }
    if (one('limit')) c = c.slice(0, parseInt(one('limit'), 10));
    return c;
  }
  inRange(v, r) {
    if (v === null) return false;
    const m = /^(-?\d+)?(\.\.)?(-?\d+)?$/.exec(r); if (!m) throw new Error('range ' + r);
    if (!m[2]) return v === parseInt(m[1], 10);
    return (m[1] === undefined || v >= parseInt(m[1], 10)) && (m[3] === undefined || v <= parseInt(m[3], 10));
  }
  // ---- command execution; returns integer result (0 = failure)
  run(cmd, ctx) {
    const t = cmd.trim().split(' '); const h = t[0];
    if (h === 'execute') return this.exec(t, 1, ctx);
    if (h === 'scoreboard') {
      if (t[1] === 'objectives') return 1;
      const [, , op, holder] = t;
      if (op === 'set') { this.score.set(holder, parseInt(t[5], 10)); return 1; }
      if (op === 'add') { this.score.set(holder, (this.get(holder) || 0) + parseInt(t[5], 10)); return 1; }
      if (op === 'remove') { this.score.set(holder, (this.get(holder) || 0) - parseInt(t[5], 10)); return 1; }
      if (op === 'operation') { const a = this.get(holder) || 0, b = this.get(t[6]) || 0; this.score.set(holder, t[5] === '=' ? b : t[5] === '%=' ? a % b : a + b); return 1; }
    }
    if (h === 'say') { this.say(cmd.slice(4)); return 1; }
    if (h === 'tellraw') { this.say('TELLRAW ' + cmd); return 1; }
    if (h === 'tp') { this.op.pos = [parseFloat(t[2]), parseFloat(t[3]), parseFloat(t[4])]; return 1; }
    if (h === 'fill') { this.say('FILL ' + t.slice(1).join(' ')); if (t[2] === '-60' && t[3] === '-80') this.wallLarge = t[7] === 'minecraft:stone'; return 1; }
    if (h === 'gamerule' || h === 'difficulty' || h === 'gamemode') return 1;
    if (h === 'tag') {
      const es = this.select(t[1], ctx); if (this.p.tagFail && es.some(e => e !== this.op && e.type === 'player' && this.npcs.has(e.character))) return 0;
      es.forEach(e => (t[2] === 'add' ? e.tags.add(t[3]) : e.tags.delete(t[3]))); return es.length ? es.length : 0;
    }
    if (h === 'schedule') {
      if (t[1] === 'clear') { this.sched = []; return 1; }
      this.sched.push({ at: this.tick + 10, fn: 'loop' }); this.sched = this.sched.filter((s, i, a) => i === a.findIndex(o => o.fn === s.fn)); return 1;
    }
    if (h === 'function') { return this.fn(t[1].split(':')[1], ctx); }
    if (h === 'data') { if (this.p.noData) return 0; const i = /Pos\[(\d)\]/.exec(cmd)[1]; return ctx.exec && ctx.exec.pos ? Math.trunc(ctx.exec.pos[parseInt(i, 10)]) : 0; }
    if (h === 'npcortex') return this.mock(t.slice(1), ctx);
    throw new Error('unsupported command ' + cmd);
  }
  fn(name, ctx) { let r = 1; for (const l of this.fnLines(name)) r = this.run(l, Object.assign({}, ctx)); return r; }
  exec(t, i, ctx) {
    if (i >= t.length) return 1;
    const k = t[i];
    if (k === 'run') return this.run(t.slice(i + 1).join(' '), ctx);
    if (k === 'as' || k === 'at') {
      const ents = this.select(t[i + 1], ctx); let last = 0;
      for (const e of ents) last = this.exec(t, i + 2, k === 'as' ? Object.assign({}, ctx, { exec: e }) : Object.assign({}, ctx, { pos: e.pos.slice() }));
      return ents.length ? last : 0;
    }
    if (k === 'positioned') return this.exec(t, i + 4, Object.assign({}, ctx, { pos: [parseFloat(t[i + 1]), parseFloat(t[i + 2]), parseFloat(t[i + 3])] }));
    if (k === 'store') {
      const kind = t[i + 1], holder = t[i + 3]; let next = i + 5;
      const res = this.exec(t, next, ctx);
      this.score.set(holder, kind === 'success' ? (res > 0 ? 1 : 0) : res); return res;
    }
    if (k === 'if' || k === 'unless') {
      let pass, n, cnt = 1;
      if (t[i + 1] === 'score') {
        const a = this.get(t[i + 2]);
        if (t[i + 4] === 'matches') { pass = this.inRange(a, t[i + 5]); n = i + 6; }
        else { const b = this.get(t[i + 5]); const op = t[i + 4]; pass = a !== null && b !== null && (op === '=' ? a === b : op === '>' ? a > b : op === '<' ? a < b : op === '>=' ? a >= b : a <= b); n = i + 7; }
      } else if (t[i + 1] === 'entity') { cnt = this.select(t[i + 2], ctx).length; pass = cnt > 0; n = i + 3; }
      else if (t[i + 1] === 'block') { const x = +t[i + 2], z = +t[i + 4]; pass = this.p.loaded(x, z) && t[i + 5] === 'minecraft:bedrock' && +t[i + 3] === -64; n = i + 6; }
      else throw new Error('condition ' + t.join(' '));
      if ((k === 'if') !== pass) return 0;
      return n >= t.length ? (k === 'if' ? cnt : 1) : this.exec(t, n, ctx);
    }
    throw new Error('execute token ' + k + ' in ' + t.join(' '));
  }
  // ---- mock NPCortex
  mock(a, ctx) {
    const J = a.join(' ');
    if (a[0] === 'c2') this.cmds.push(J);
    if (a[0] === 'c1' && a[1] === 'spawn') { if (!ctx.exec) return 0; this.cmds.push(J); this.npcs.set(a[2], { character: a[2], type: 'player', name: 'N' + a[2].slice(0, 8) + '_' + crypto.createHash('md5').update(a[2]).digest('hex').slice(0, 6), tags: new Set(), pos: [this.op.pos[0] + 1.5, this.op.pos[1], this.op.pos[2] + 1.5], goal: null, follow: false, moved: 0, lastMove: this.tick, lx: this.op.pos[0] + 1.5, lz: this.op.pos[2] + 1.5 }); return 1; }
    if (a[0] === 'c1' && a[1] === 'despawn') { if (this.npcs.has(a[2])) this.cmds.push(J); this.npcs.delete(a[2]); return 1; }
    if (a[0] === 'c2' && ['navlong', 'navigate', 'move'].includes(a[1])) {
      const n = this.npcs.get(a[2]); if (!n || n.goal || this.p.ignoreStart(a, this)) return 0;
      n.goal = { target: [parseFloat(a[3]) + (a[1] === 'move' ? 0 : 0.5), parseFloat(a[4]), parseFloat(a[5]) + (a[1] === 'move' ? 0 : 0.5)], start: this.tick + (a[1] === 'navlong' ? this.p.decide : 20), cmd: a[1] };
      return 1;
    }
    if (a[0] === 'c2' && a[1] === 'cancel') { const n = this.npcs.get(a[2]); if (n && n.goal) this.events.push({ tick: this.tick, npc: a[2], type: 'CANCEL' }); if (n) n.goal = null; this.say('MOCK cancel ' + a[2]); return 1; }
    if (J === 'debug cplus m03 enable') { this.session = true; return 1; }
    if (a[3] === 'snapshot') { const n = this.npcs.get(a[4]); const sn = { tick: this.tick, npc: a[4], state: n && n.goal ? (n.paused ? 'REPLANNING' : 'MOVING') : 'NONE', x: n ? n.pos[0] : null, z: n ? n.pos[2] : null, op: [this.op.pos[0], this.op.pos[2]] }; this.snapshots.push(sn); (this.snapBy[a[4]] = this.snapBy[a[4]] || []).push(sn); return 1; }
    if (a[3] === 'measure-auto') { this.say('MOCK measure ' + a[4]); return 1; }
    if (a[3] === 'move') { const n = this.npcs.get(a[4]); if (n && !n.goal) n.goal = { target: [n.pos[0] + 5, n.pos[1], n.pos[2]], start: this.tick + 20, cmd: 'e3' }; return 1; }
    if (a[3] === 'follow-self') { const n = this.npcs.get(a[4]); if (n) n.follow = true; return 1; }
    if (a[3] === 'unfollow') { const n = this.npcs.get(a[4]); if (n) n.follow = false; return 1; }
    throw new Error('unmocked npcortex ' + J);
  }
  moveNpcs() {
    for (const n of this.npcs.values()) {
      if (n.follow) { const d = Math.hypot(this.op.pos[0] - n.pos[0], this.op.pos[2] - n.pos[2]); if (d > 3) { n.pos[0] += (this.op.pos[0] - n.pos[0]) / d * 0.3; n.pos[2] += (this.op.pos[2] - n.pos[2]) / d * 0.3; } continue; }
      if (this.rejoined && this.p.movesAfterRejoin) { n.pos[0] += 0.2; continue; }
      const g = n.goal; if (!g || this.tick < g.start) continue;
      n.paused = false;
      const sinceStart = this.tick - g.start;
      if (g.cmd === 'navlong' && sinceStart % this.p.pausePeriod >= this.p.pausePeriod - this.p.pauseLen) { n.paused = true; continue; }   // inter-leg quiescence
      if (this.p.stallAtX !== null && g.cmd === 'navlong' && n.pos[0] >= this.p.stallAtX && g.target[0] > this.p.stallAtX + 20) { this.events.push({ tick: this.tick, npc: n.character, type: 'FAIL' }); n.goal = null; continue; }
      if (this.wallLarge && g.cmd === 'navlong' && n.pos[0] >= 9 && g.target[0] > 12) { this.events.push({ tick: this.tick, npc: n.character, type: 'FAIL' }); n.goal = null; continue; }   // wall blocks the lane
      const dx = g.target[0] - n.pos[0], dz = g.target[2] - n.pos[2], d = Math.hypot(dx, dz);
      if (d <= 0.2) { if (!this.p.noStop) { this.events.push({ tick: this.tick, npc: n.character, type: 'COMPLETE' }); n.goal = null; } continue; }
      const s = Math.min(this.p.speed, d); n.pos[0] += dx / d * s; n.pos[2] += dz / d * s;
    }
  }
  // ---- owner gate model: what scripts/g5-gate.ps1 decides from M03 snapshots + logged terminal lines (terminal AND quiescent), then the owner runs the gate function
  curPhase() { for (let i = this.log.length - 1; i >= 0; i--) { const m = /^\[G5\] BEGIN (\S+)/.exec(this.log[i].text); if (m) return m[1]; } return null; }
  tail(npc, sinceTick) { const a = this.snapBy[npc] || []; let i = a.length; while (i > 0 && a[i - 1].tick >= sinceTick) i--; return a.slice(i); }
  gateReady() {
    const id = this.curPhase(); if (!id) return false;
    const npcs = [...this.npcs.values()];
    if (id.startsWith('p11_')) return npcs.some(n => n.goal && Math.hypot(n.pos[0] - 2, n.pos[2] - 2) >= 3);    // mid-goal: the checkpoint may start
    let beginTick = 0; for (let k = this.log.length - 1; k >= 0; k--) if (this.log[k].text.startsWith('[G5] BEGIN ')) { beginTick = this.log[k].tick; break; }
    const spread = ss => Math.max(Math.max(...ss.map(s => s.x)) - Math.min(...ss.map(s => s.x)), Math.max(...ss.map(s => s.z)) - Math.min(...ss.map(s => s.z)));
    // same rule as scripts/g5-gate.ps1 (independent re-implementation): terminal (no active goal) AND >= 3 of the last 4 snapshots spanning >= 7 s with spread <= 0.5
    return npcs.length > 0 && npcs.every(n => {
      const all0 = this.tail(n.character, beginTick).filter(s => s.tick < this.tick); const ss = all0.filter(s => s.x !== null).slice(-4);
      if (id.startsWith('p12_')) { const all = all0; return all.length >= 3 && all[all.length - 1].tick - all[0].tick >= 300 && spread(all) <= 0.75; }
      return n.goal === null && ss.length >= 3 && ss[ss.length - 1].tick - ss[0].tick >= 140 && spread(ss) <= 0.5;
    });
  }
  step(n = 1) {
    for (let i = 0; i < n; i++) {
      this.tick++; this.moveNpcs();
      for (const v of this.npcs.values()) { if (Math.hypot(v.pos[0] - v.lx, v.pos[2] - v.lz) > 1e-6) { v.lastMove = this.tick; v.lx = v.pos[0]; v.lz = v.pos[2]; } }
      const due = this.sched.filter(s => s.at <= this.tick); this.sched = this.sched.filter(s => s.at > this.tick);
      for (const s of due) this.fn(s.fn, { exec: null, pos: [0, 0, 0] });
      if (this.get('#settle') === -2 && this.get('#running') === 1) { if (this.awaitSince === null || this.awaitSince === undefined) this.awaitSince = this.tick; } else this.awaitSince = null;
      // the owner reacts to the AWAIT message after >= 8 s (script poll + clipboard + chat), and only when the gate model says terminal + quiescent
      if (this.p.autoGate && this.tick % 10 === 5 && this.get('#running') === 1 && this.get('#settle') === -2 && this.tick - this.awaitSince >= 160 && this.gateReady()) {
        this.gates.push({ tick: this.tick, id: this.curPhase() }); this.fn('gate', { exec: this.op, pos: this.op.pos.slice() });
      }
    }
  }
  call(fn, asOp = true) { return this.fn(fn, { exec: asOp ? this.op : null, pos: this.op.pos.slice() }); }
  runUntil(pred, max) { for (let i = 0; i < max; i++) { if (pred()) return true; this.step(1); } return pred(); }
  obs() { const o = {}; for (const l of this.log) { const m = /^\[G5\] OBS (\S+) (\S+)$/.exec(l.text); if (m) (o[m[1]] = o[m[1]] || []).push(m[2]); } return o; }
  has(txt) { return this.log.some(l => l.text.includes(txt)); }
  awaits() { return this.log.map(l => /^\[G5\] AWAIT (\S+)/.exec(l.text)).filter(Boolean).map(m => m[1]); }
  nbegin() { return this.log.filter(l => /^\[G5\] BEGIN /.test(l.text)).length; }
}

function setup(sim) { sim.op.tags.add('g5_op'); sim.call('setup'); sim.score.set('#ready', 1); }   // block checks are real-world only
function begins(sim) { return sim.log.filter(l => /^\[G5\] BEGIN /.test(l.text)).map(l => l.text.split(' ')[2]); }
const pdone = s => s.get('#running') === 0 && s.get('#probed') === 1;

// ---------------------------------------------------------------------------------------------
// full run; fb = true: owner-gated mode (the NPC is invisible to every selector kind)
function fullRun(profile = {}, fb = false) {
  const s = new Sim(profile); setup(s);
  s.call('start');
  ok(s.runUntil(() => s.has('CHECKPOINT save_quit'), 2000000), 'sequence reaches the Save&Quit checkpoint');
  ok(s.get('#running') === 0, 'loop halted at checkpoint (no blind continuation)');
  const before = begins(s).length; s.step(5000); ok(begins(s).length === before, 'nothing runs while paused at the checkpoint');
  s.rejoined = true; s.call('rejoin'); for (const n of s.npcs.values()) n.tags.clear();   // NPC entities are recreated on load: tags must be re-adopted
  ok(s.runUntil(() => s.has('OBS p12_rejoin_no_replay'), 5000), 'rejoin phases start');
  s.rejoined = false;   // a full start keeps #last=99: the sequence continues through regression and multi-NPC after the checkpoint
  ok(s.runUntil(() => s.has('SEQUENCE_DONE'), 8000000), 'full sequence finishes after the rejoin');
  const o = s.obs();
  const AD = { p00_capability_probe: 'ADAPTIVE', p02_navlong_20: 'REACHED', p03_navlong_40: 'REACHED', p04_navlong_80: 'REACHED', p05_navlong_150: 'REACHED',
    p06_navlong_diag60: 'REACHED', p07_small_wall_detour: 'REACHED', p08_large_wall_no_progress: 'STALLED_TERMINAL_INFERRED',
    p09_cancel_leg_active: 'STOPPED_AFTER_CANCEL', p10_cancel_quiescing: 'STOPPED_AFTER_CANCEL', p12_rejoin_no_replay: 'NO_MOVEMENT_AFTER_REJOIN', p13_new_navlong_after_recovery: 'REACHED',
    p14_regress_c2_navigate: 'REACHED', p15_regress_c2_move: 'REACHED', p16_regress_follow_start: 'REACHED', p17_regress_follow_stop: 'REACHED', p18_regress_e3_taskmove: 'REACHED',
    p19_multi_1_npc: 'ALL_REACHED', p20_multi_3_npc: 'ALL_REACHED', p21_multi_5_npc: 'ALL_REACHED', p22_multi_10_npc: 'ALL_REACHED' };
  const want = {}; for (const k of Object.keys(AD)) want[k] = fb ? (k === 'p00_capability_probe' ? 'OWNER_GATED' : 'AWAITING_OWNER_GATE') : AD[k];
  for (const [k, v] of Object.entries(want)) ok((o[k] || []).includes(v) && (o[k] || []).filter(x => !x.startsWith('NPC_')).length === 1, `${k} -> ${v}, got ${JSON.stringify(o[k])}`);
  const order = begins(s);
  ok(order.slice(0, 11).join() === 'p00_capability_probe,p02_navlong_20,p03_navlong_40,p04_navlong_80,p05_navlong_150,p06_navlong_diag60,p07_small_wall_detour,p08_large_wall_no_progress,p09_cancel_leg_active,p10_cancel_quiescing,p11_savequit_checkpoint', 'phases run in the documented order');
  ok(order.length === 22 && order[11] === 'p12_rejoin_no_replay', 'all 22 phases ran once');
  ok(s.has('SEQUENCE_DONE outcome=ALL_SCENARIOS_RAN'), 'completion marker says scenarios ran (not a verdict)');
  // owner gate bookkeeping: every AWAIT is released exactly once, in order, and never without being awaited
  const aw = s.awaits(), gt = s.log.map(l => /^\[G5\] GATE (\S+) RELEASED/.exec(l.text)).filter(Boolean).map(m => m[1]);
  ok(aw.join() === gt.join(), `every AWAIT is released exactly once, in order: AWAIT=${aw.join()} GATE=${gt.join()}`);
  if (fb) ok(aw.length === 21 && !aw.includes('p00_capability_probe'), `owner-gated mode: all 21 scenarios except the probe await the gate (${aw.length})`);
  else ok(aw.join() === 'p08_large_wall_no_progress', `adaptive mode awaits only for the non-terminal stall (p08), got ${aw.join()}`);
  // wall build/cleanup are paired and inside the area
  const fills = s.log.filter(l => l.text.startsWith('FILL ')).map(l => l.text);
  ok(fills.filter(f => f.endsWith('minecraft:stone')).length === fills.filter(f => f.endsWith('minecraft:air')).length, 'every wall placed is removed');
  const snaps = s.snapshots.filter(x => x.npc === 'yui');
  const cancelSnaps = []; s.log.forEach(l => { if (l.text === '[G5] NOTE cancel issued') cancelSnaps.push(l.tick); });
  ok(cancelSnaps.length === 2, 'two cancels issued (p09, p10)');
  const st = cancelSnaps.map(t => snaps.filter(x => x.tick === t).map(x => x.state));
  ok(st[0].length > 0 && st[1].length > 0, 'a snapshot is taken in the very tick of each cancel: ' + JSON.stringify(st));
  if (!fb) {
    ok(st[0].includes('MOVING'), 'p09 cancel snapshot state MOVING: ' + JSON.stringify(st));
    ok(st[1].includes('REPLANNING'), 'p10 cancel snapshot state REPLANNING (quiescing proxy): ' + JSON.stringify(st));
  } else {
    const end0 = s.log.find(l => l.text === '[G5] END p00_capability_probe').tick;
    ok(!s.selLog.some(t => t > end0), 'owner-gated mode never evaluates a selector-based observation after p00');
    ok(s.snapshots.length > 100, 'owner-gated mode produces dense snapshot evidence: ' + s.snapshots.length);
    ok(s.cmds.filter(c => c.startsWith('c2 navlong')).length >= 14, 'owner-gated mode still dispatches the scenarios');
  }
  // progression never came from a timer alone: the next BEGIN follows its gate release (or an in-game terminal observation in adaptive mode) - checked per await
  for (const g of s.gates) { const nxt = s.log.find(l => l.tick > g.tick && /^\[G5\] BEGIN /.test(l.text)); ok(!nxt || nxt.tick >= g.tick, 'no scenario began before its predecessor was released'); }
  return s;
}
// a scenario whose goal never ends: the pack must wait at AWAIT forever (no timer-only dispatch)
function hangRun(ticks = 60000) {
  const s = new Sim({ v1: false, v2: false, speed: 0.0002, autoGate: false }); setup(s);   // practically stationary but the goal never ends (journal MOVING)
  s.call('probe'); ok(s.runUntil(() => pdone(s), 5000), 'probe completes');
  s.call('only/p03_navlong_40'); s.step(ticks);
  return s;
}
function runGroup(sim, fn, max) {
  sim.call('probe'); ok(sim.runUntil(() => pdone(sim), 5000), 'probe before ' + fn);
  const mark = sim.log.length; sim.call(fn);
  return sim.runUntil(() => sim.log.slice(mark).some(l => l.text.includes('SEQUENCE_DONE')), max);
}
function afterRejoinContinue() {
  // regression + multi groups run via their group starters
  const s = new Sim({}); setup(s);
  ok(runGroup(s, 'tests/regression', 600000), 'regression group completes');
  const o = s.obs();
  for (const k of ['p14_regress_c2_navigate', 'p15_regress_c2_move', 'p16_regress_follow_start', 'p17_regress_follow_stop', 'p18_regress_e3_taskmove'])
    ok((o[k] || []).join() === 'REACHED', `${k} REACHED got ${JSON.stringify(o[k])}`);
  const m = new Sim({}); setup(m); ok(runGroup(m, 'tests/multi_npc', 1200000), 'multi group completes');
  const mo = m.obs();
  for (const [k, n] of [['p19_multi_1_npc', 1], ['p20_multi_3_npc', 3], ['p21_multi_5_npc', 5], ['p22_multi_10_npc', 10]])
    ok((mo[k] || []).includes('ALL_REACHED'), `${k} ALL_REACHED got ${JSON.stringify(mo[k])}`);
  ok(m.log.filter(l => /OBS p22_multi_10_npc NPC_\S+ REACHED/.test(l.text)).length === 10, 'all 10 per-NPC REACHED lines');
  // measurement window: next scenario never starts before 2600 ticks have elapsed
  const b = m.log.filter(l => /^\[G5\] BEGIN p(19|20|21|22)_/.test(l.text)).map(l => l.tick);
  for (let i = 1; i < b.length; i++) ok(b[i] - b[i - 1] >= 2600, 'multi scenarios spaced >= 2600 ticks: ' + (b[i] - b[i - 1]));
  // the same multi lanes through the typeless selector kind (world-limited player selector unusable)
  const m2 = new Sim({ v1: false }); setup(m2); ok(runGroup(m2, 'tests/multi_npc', 1200000), 'multi group completes through the typeless selector');
  ok(m2.get('#sel') === 2 && (m2.obs().p22_multi_10_npc || []).includes('ALL_REACHED'), 'typeless selector kind drives 10 NPC lanes');
}
function probed(profile) { const x = new Sim(profile); setup(x); x.call('probe'); ok(x.runUntil(() => pdone(x), 5000), 'probe completes'); return x; }
function only(id, profile, expectObs, extra, probe = true) {
  const s = new Sim(profile); setup(s);
  if (probe) { s.call('probe'); ok(s.runUntil(() => pdone(s), 5000), 'probe completes'); }
  const mark = s.log.length; s.call('only/' + id);
  ok(s.runUntil(() => s.log.slice(mark).some(l => l.text.includes('SEQUENCE_DONE')), 800000), id + ' finishes');
  const o = s.obs()[id] || [];
  ok(o.includes(expectObs), `${id}: expected ${expectObs}, got ${JSON.stringify(o)}`);
  if (extra) extra(s);
  return s;
}
const probeKeys = s => { const r = {}; for (const l of s.log) { const m = /^\[G5\] PROBE (\w+)=(\S+)/.exec(l.text); if (m) r[m[1]] = m[2]; } return r; };

function probeTests() {
  // default world: every capability passes, the player selector kind (scans only ServerLevel.players) is preferred
  const d = probed({}); const k = probeKeys(d);
  ok(k.playerlist_control === 'PASS' && k.world_limited_player === 'PASS' && k.typeless_entity === 'PASS' && k.tag_v1 === 'PASS' && k.tag_v2 === 'PASS' && k.entity_data === 'PASS' && k.position === 'PASS', 'default probe: all seven capabilities PASS ' + JSON.stringify(k));
  ok(k.selector_chosen === 'world_limited_player' && k.mode === 'ADAPTIVE' && d.get('#sel') === 1 && d.get('#adaptive') === 1 && d.get('#per') === 10, 'default probe: world-limited player selector chosen, ADAPTIVE');
  ok(!d.op.tags.has('g5_pv1') && ![...d.npcs.values()].some(n => n.tags.has('g5_pv1') || n.tags.has('g5_pv2')), 'probe tags are removed again');
  ok(d.npcs.get('yui').tags.has('g5_npc_yui') && !d.op.tags.has('g5_npc') && !d.op.tags.has('g5_npc_yui'), 'only the NPC is adopted, never the operator');
  // regression of the revision-3 bug: the old selector shape cannot see a non-PlayerList player, the boxed one can
  const ctx = { exec: null, pos: [0, 0, 0] };
  ok(d.select('@e[type=player,tag=g5_npc_yui]', ctx).length === 0 && d.select('@a[tag=g5_npc_yui]', ctx).length === 0, 'revision-3 selector shape (type=player without a box) and @a do NOT see the NPC');
  ok(d.select('@e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]', ctx).length === 1, 'boxed player selector sees exactly the NPC');
  ok(d.select('@e[tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=500,dy=20,dz=300]', ctx).length === 1, 'boxed typeless selector sees exactly the NPC');
  ok(d.select('@e[type=player,tag=g5_npc_yui,x=-250,y=-64,z=-150,dx=10,dy=20,dz=300]', ctx).length === 0, 'the box really bounds the search (NPC outside the box is not found)');
  // world-limited kind fails, typeless works -> the typeless kind is used end to end
  const t2 = probed({ v1: false }); const k2 = probeKeys(t2);
  ok(k2.world_limited_player === 'FAIL' && k2.tag_v1 === 'NOT_ATTEMPTED' && k2.typeless_entity === 'PASS' && k2.tag_v2 === 'PASS' && k2.selector_chosen === 'typeless_entity' && k2.mode === 'ADAPTIVE', 'player selector fails, typeless works: tag_v1 NOT_ATTEMPTED, typeless chosen, ADAPTIVE ' + JSON.stringify(k2));
  only('p03_navlong_40', { v1: false }, 'REACHED', s => ok(s.get('#sel') === 2 && s.get('#adaptive') === 1, 'p03 observed through the typeless selector kind'));
  // typeless fails, player works
  const t3 = probed({ v2: false }); const k3 = probeKeys(t3);
  ok(k3.typeless_entity === 'FAIL' && k3.tag_v2 === 'NOT_ATTEMPTED' && k3.selector_chosen === 'world_limited_player' && k3.mode === 'ADAPTIVE', 'typeless fails, player works: ADAPTIVE through the player selector');
  // nothing is visible: owner-gated, dependent capabilities are NOT_ATTEMPTED, never FAIL
  const none = probed({ v1: false, v2: false }); const kn = probeKeys(none);
  ok(kn.playerlist_control === 'PASS' && kn.world_limited_player === 'FAIL' && kn.typeless_entity === 'FAIL', 'invisible NPC: both selector kinds FAIL, the PlayerList control still PASSes');
  ok(kn.tag_v1 === 'NOT_ATTEMPTED' && kn.tag_v2 === 'NOT_ATTEMPTED' && kn.entity_data === 'NOT_ATTEMPTED' && kn.position === 'NOT_ATTEMPTED', 'invisible NPC: tag, entity data and position are NOT_ATTEMPTED (not FAIL): ' + JSON.stringify(kn));
  ok(kn.selector_chosen === 'NONE' && kn.mode === 'OWNER_GATED' && none.get('#adaptive') === 0 && none.get('#per') === 5 && (none.obs().p00_capability_probe || []).includes('OWNER_GATED'), 'invisible NPC: OWNER_GATED');
  // tag commands do not work on the NPC: both tag tests FAIL (they were attempted), entity data NOT_ATTEMPTED
  const tg = probed({ tagFail: true }); const ktg = probeKeys(tg);
  ok(ktg.world_limited_player === 'PASS' && ktg.tag_v1 === 'FAIL' && ktg.tag_v2 === 'FAIL' && ktg.entity_data === 'NOT_ATTEMPTED' && ktg.mode === 'OWNER_GATED', 'tag add unusable: tag tests FAIL, entity data NOT_ATTEMPTED, OWNER_GATED ' + JSON.stringify(ktg));
  // entity data unreadable
  const nd = probed({ noData: true }); const knd = probeKeys(nd);
  ok(knd.tag_v1 === 'PASS' && knd.entity_data === 'FAIL' && knd.position === 'NOT_ATTEMPTED' && knd.mode === 'OWNER_GATED' && nd.get('#adaptive') === 0, 'unreadable entity data: entity_data FAIL, position NOT_ATTEMPTED, OWNER_GATED');
  // ambiguity: a second player (human or foreign NPC) is never adopted or tagged
  const amb = probed({ extraPlayer: true }); const ka = probeKeys(amb);
  ok(ka.world_limited_player === 'FAIL' && ka.typeless_entity === 'FAIL' && ka.mode === 'OWNER_GATED' && amb.get('#adaptive') === 0, 'a second player: both selector kinds FAIL (ambiguous), OWNER_GATED ' + JSON.stringify(ka));
  ok(amb.extra.tags.size === 0 && !amb.op.tags.has('g5_npc') && ![...amb.npcs.values()].some(n => n.tags.size), 'ambiguous candidates stay untagged (nobody is tagged)');
  // PlayerList control failing is informational (it does not block adaptive mode)
  const pl = probed({ playerListIncludes: true }); const kp = probeKeys(pl);
  ok(kp.playerlist_control === 'FAIL' && kp.mode === 'ADAPTIVE', 'a PlayerList control that disagrees with the source is reported FAIL but informational');
  // say text never contains a selector (vanilla would expand it, as the revision-3 p00 log showed)
  for (const sim of [d, none]) ok(!sim.log.some(l => /^\[G5\] PROBE /.test(l.text) && l.text.includes('@')), 'no selector text inside say');
}

function negatives() {
  // ---- never moved / blocked / timeout / stall: non-terminal outcomes AWAIT the gate, they never advance by themselves
  only('p02_navlong_20', { ignoreStart: () => true }, 'NEVER_MOVED', s => ok(s.awaits().includes('p02_navlong_20') && s.gates.length === 1, 'NEVER_MOVED awaits the owner gate'));
  only('p05_navlong_150', { loaded: (x, z) => Math.abs(x) <= 100 }, 'BLOCKED_TARGET_CHUNK_NOT_LOADED', s => { ok(!s.cmds.some(c => c.startsWith('c2 navlong yui 152')), 'no navlong when the target chunk is unloaded'); ok(s.has('SEQUENCE_DONE outcome=HAD_BLOCKED_SCENARIOS'), 'blocked scenarios are not reported as a clean completion'); ok(s.awaits().length === 0, 'nothing was dispatched, nothing to await'); });
  only('p02_navlong_20', { stallAtX: 3 }, 'REACHED');                                           // control: stall rule only affects far targets
  only('p04_navlong_80', { stallAtX: 20 }, 'STALLED_TERMINAL_INFERRED', s => ok(s.awaits().join() === 'p04_navlong_80' && s.gates.length === 1, 'a stall is not terminal evidence: it awaits the gate'));
  only('p09_cancel_leg_active', { noStop: true, stallAtX: null }, 'STOPPED_AFTER_CANCEL');
  // ---- TIMEOUT with an actor that is still walking: the pack must hold at AWAIT (no timer-only progression) until a safe checkpoint
  const to = new Sim({ speed: 0.01, autoGate: false }); setup(to); to.call('probe'); ok(to.runUntil(() => pdone(to), 5000), 'probe');
  to.call('only/p02_navlong_20'); ok(to.runUntil(() => to.has('AWAIT p02_navlong_20'), 100000), 'TIMEOUT scenario reaches AWAIT');
  ok((to.obs().p02_navlong_20 || []).includes('TIMEOUT'), 'adaptive timeout reported as TIMEOUT');
  const cmdsAtAwait = to.cmds.length, beginsAtAwait = to.nbegin(), doneAtAwait = to.log.filter(l => l.text.includes('SEQUENCE_DONE')).length;
  to.step(60000);
  ok(to.get('#running') === 1 && to.get('#settle') === -2 && to.nbegin() === beginsAtAwait && to.cmds.length === cmdsAtAwait && !to.has('END p02') && to.log.filter(l => l.text.includes('SEQUENCE_DONE')).length === doneAtAwait, 'NO TIMER-ONLY PROGRESSION: 50 game-minutes after the timeout nothing was dispatched, spawned, despawned or advanced');
  ok(to.snapshots.filter(x => x.tick > to.log.find(l => l.text.startsWith('[G5] AWAIT')).tick).length > 100, 'snapshots keep flowing while awaiting (evidence for the gate)');
  to.call('cancel_current'); ok(to.has('NOTE cancel_current issued') && to.cmds.includes('c2 cancel yui') && to.npcs.get('yui').goal === null, 'cancel_current cancels the actor (explicit safe checkpoint)');
  to.p.autoGate = true; ok(to.runUntil(() => to.has('END p02_navlong_20'), 100000) && to.has('GATE p02_navlong_20 RELEASED'), 'after the cancel the gate becomes releasable and the run finishes');
  // ---- owner-gated mode with a hanging actor: waits forever, p03 never ends by itself
  const h = hangRun(80000);
  ok(h.has('AWAIT p03_navlong_40') && !h.has('END p03') && h.log.filter(l => l.text.includes('SEQUENCE_DONE')).length === 1 && h.nbegin() === 2 && h.get('#settle') === -2, 'owner-gated hang: the scenario stays AWAITING (BEGIN p00, p03 only; the only SEQUENCE_DONE is the probe)');
  // ---- movement after rejoin keeps p12 awaiting (a replayed goal is active C2 work)
  const r = probed({ movesAfterRejoin: true }); r.call('only/p11_savequit_checkpoint');
  ok(r.runUntil(() => r.has('CHECKPOINT save_quit'), 200000), 'checkpoint (negative run)');
  r.rejoined = true; r.call('rejoin'); ok(r.runUntil(() => r.has('OBS p12_rejoin_no_replay'), 5000), 'p12 observes');
  ok((r.obs().p12_rejoin_no_replay || []).includes('MOVED_AFTER_REJOIN'), 'movement after rejoin is flagged, not passed');
  r.step(60000); ok(r.has('AWAIT p12_rejoin_no_replay') && r.log.filter(l => l.text.includes('SEQUENCE_DONE')).length === 1 && !r.has('END p12') && r.get('#settle') === -2, 'a moving NPC after the rejoin is never released by time');
  const abort = probed({ ignoreStart: () => true }); abort.call('only/p11_savequit_checkpoint');
  ok(abort.runUntil(() => abort.get('#running') === 0, 200000), 'p11 aborts when the goal never starts');
  ok(!abort.has('CHECKPOINT save_quit') && abort.has('ABORTED_BEFORE_CHECKPOINT'), 'no checkpoint is offered when no walking was observed');
  // ---- p11 in owner-gated mode: the checkpoint starts only through the gate, while the NPC is mid-goal
  const g11 = new Sim({ v1: false, v2: false, autoGate: false }); setup(g11); g11.call('probe'); ok(g11.runUntil(() => pdone(g11), 5000), 'probe');
  g11.call('only/p11_savequit_checkpoint'); ok(g11.runUntil(() => g11.has('AWAIT p11_savequit_checkpoint'), 100000), 'p11 awaits the gate in owner-gated mode');
  g11.step(200); ok(!g11.has('CHECKPOINT save_quit') && g11.get('#running') === 1, 'no checkpoint without the owner gate');
  ok(g11.gateReady(), 'NPC is still walking (150 blocks) when the gate would be given');
  g11.call('gate'); ok(g11.has('GATE p11_savequit_checkpoint RELEASED') && g11.has('CHECKPOINT save_quit') && g11.get('#running') === 0 && g11.get('#cp') === 1, 'gate release starts the Save&Quit checkpoint');
  // ---- regression: character name != GameProfile name, identification via tags only
  const id = only('p03_navlong_40', {}, 'REACHED');
  const yui = id.npcs.get('yui');
  ok(/^Nyui_[0-9a-f]{6}$/.test(yui.name) && yui.name !== 'yui', 'mock models the real GameProfile name: ' + yui.name);
  ok(yui.tags.has('g5_npc_yui') && yui.tags.has('g5_npc') && !id.op.tags.has('g5_npc') && !id.op.tags.has('g5_npc_yui'), 'only the NPC is tagged, never the operator');
  ok(!fs.readdirSync(packDir, { recursive: true }).filter(f => f.endsWith('.mcfunction')).some(f => /name=/.test(fs.readFileSync(path.join(packDir, f), 'utf8').replace(/^#.*$/gm, ''))), 'no function selects by name=');
  // adaptive observation lost mid-run: stop at once, no blind dispatch, no completion marker
  const lost = new Sim({}); setup(lost); lost.call('start');
  ok(lost.runUntil(() => begins(lost).length >= 3, 100000), 'adaptive run under way'); lost.p.v1 = false; const sent = lost.cmds.filter(c => c.startsWith('c2 ')).length;
  ok(lost.runUntil(() => lost.get('#running') === 0, 20000) && lost.has('ABORT NPC observation lost'), 'observation loss aborts the run');
  ok(lost.cmds.filter(c => c.startsWith('c2 ')).length <= sent + 1 && !lost.has('SEQUENCE_DONE'), 'no blind dispatch after the loss, no completion marker');
  // ---- stale-run protection
  const stale = new Sim({}); setup(stale); stale.call('rejoin');
  ok(stale.get('#running') === 0 && stale.has('rejoin refused') && begins(stale).length === 0, 'rejoin refused without a checkpoint');
  const gs = new Sim({}); setup(gs); gs.call('gate'); ok(gs.has('gate refused') || gs.log.some(l => l.text.includes('gate refused')), 'gate refused without a run');
  gs.call('cancel_current'); ok(gs.has('cancel_current refused') && !gs.cmds.some(c => c.startsWith('c2 cancel')), 'cancel_current refused when nothing is awaiting');
  const gr = new Sim({ autoGate: false }); setup(gr); gr.call('tests/obstacles');   // unprobed: owner-gated
  ok(gr.has('mode=OWNER_GATED_UNPROBED') && gr.runUntil(() => gr.nbegin() === 1, 100), 'unprobed group start announces OWNER_GATED_UNPROBED');
  gr.call('gate/p08_large_wall_no_progress'); ok(gr.has('gate refused: p08_large_wall_no_progress is not the current phase') && gr.get('#settle') === -1, 'a gate command for another scenario is refused');
  gr.call('gate'); ok(gr.has('gate refused: p07_small_wall_detour is not awaiting the gate') && gr.get('#settle') === -1, 'the gate is refused while the scenario is still inside its window');
  ok(gr.runUntil(() => gr.has('AWAIT p07_small_wall_detour'), 100000), 'p07 reaches AWAIT'); gr.step(20000);
  ok(gr.nbegin() === 1 && !gr.has('END p07'), 'unprobed owner-gated run waits at AWAIT (no timer progression)');
  gr.call('gate/p07_small_wall_detour'); ok(gr.has('GATE p07_small_wall_detour RELEASED') && gr.runUntil(() => gr.nbegin() === 2, 100) && gr.has('END p07'), 'the correct gate command releases exactly this scenario');
  gr.call('gate'); gr.call('gate/p07_small_wall_detour'); ok(gr.log.filter(l => l.text.includes('GATE p07_small_wall_detour RELEASED')).length === 1, 'a stale/repeated gate command cannot release anything twice');
  const sc = new Sim({}); setup(sc); sc.call('start'); sc.step(100); ok(sc.sched.length === 1, 'run is scheduled');
  sc.call('setup'); ok(sc.get('#running') === 0 && sc.sched.length === 0, 'setup purges a stale scheduled loop and run state'); const nb = sc.nbegin(); sc.step(500); ok(sc.nbegin() === nb, 'a purged run never continues');
  // ---- strict mode: even an in-game REACHED needs the gate
  const stx = probed({ autoGate: false }); stx.call('strict_on'); stx.call('only/p02_navlong_20');
  ok(stx.runUntil(() => stx.has('AWAIT p02_navlong_20'), 100000) && (stx.obs().p02_navlong_20 || []).includes('REACHED'), 'strict: REACHED still awaits the gate'); stx.step(20000); ok(!stx.has('END p02'), 'strict: not advanced by time');
  const unp = new Sim({}); setup(unp); unp.call('tests/obstacles');
  ok(unp.has('mode=OWNER_GATED_UNPROBED') && unp.runUntil(() => unp.has('SEQUENCE_DONE'), 800000), 'unprobed group start announces OWNER_GATED_UNPROBED');
  ok((unp.obs().p07_small_wall_detour || []).includes('AWAITING_OWNER_GATE'), 'unprobed scenario awaits the gate, it does not end by a window');
  const op = new Sim({}); setup(op); op.call('start'); op.step(200); op.op.tags.delete('g5_op'); op.step(30);
  ok(op.get('#running') === 0 && op.has('ABORT operator offline'), 'operator watchdog stops the loop');
}
function groups() {
  const s = new Sim({}); setup(s); s.call('tests/navigation');
  ok(s.runUntil(() => s.has('SEQUENCE_DONE'), 400000), 'navigation group'); ok(begins(s).length === 6 && begins(s)[5] === 'p06_navlong_diag60', 'navigation = p00..p06');
  const o = new Sim({}); setup(o); o.call('tests/obstacles'); ok(o.runUntil(() => o.has('SEQUENCE_DONE'), 800000), 'obstacle group'); ok(begins(o).length === 2, 'obstacles = p07..p08');
  const nostart = new Sim({}); nostart.call('start'); ok(nostart.log.length === 0 || !nostart.has('START'), 'start refuses before setup');
  const st = new Sim({}); setup(st); st.call('start'); st.step(50); st.call('stop'); st.step(300);
  ok(st.get('#running') === 0 && begins(st).length >= 1 && st.sched.length === 0, 'stop halts the loop and clears the schedule');
}
if (require.main !== module) { module.exports = { Sim, setup, fullRun, hangRun }; } else try {
  fullRun(); fullRun({ v1: false, v2: false }, true); probeTests(); afterRejoinContinue(); negatives(); groups();
  console.log(`PASS G5 data pack controller simulation: ${assertions} assertions (mock world; controller logic only, not NPCortex/Minecraft behaviour)`);
} catch (e) {
  console.error('FAIL ' + e.message); if (process.env.G5_SIM_TRACE) console.error(e.stack);
  if (Sim.last) { const s = Sim.last; console.error('state: tick=' + s.tick + ' phase=' + s.get('#phase') + ' pt=' + s.get('#tick') + ' result=' + s.get('#result') + ' settle=' + s.get('#settle') + ' running=' + s.get('#running') + ' moved=' + s.get('#moved') + ' still=' + s.get('#still'));
    console.error(s.log.filter(l => l.text.startsWith('[G5]')).slice(-10).map(l => l.tick + ' ' + l.text).join(String.fromCharCode(10))); }
  process.exit(1);
}
