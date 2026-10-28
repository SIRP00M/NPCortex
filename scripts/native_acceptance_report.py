"""Stage 4 native Break/Pickup gameplay-acceptance collector (READ-ONLY).

Combines two independent kinds of evidence and never writes either:
  * the ledger: the isolated backend's SQLite file, opened with mode=ro (typed receipts written by the real Forge executor);
  * the game: chat lines of the acceptance data pack ("S1 PASS: ...") and the NPCortex chat/log lines, taken from latest.log.
A world observation alone is never PASS for an effect, and a ledger row alone is never PASS either. It never releases a claim,
never reconciles a HOLD, never replays anything. Statuses: PASS / FAIL / INCONCLUSIVE / NOT_TESTED.
Exit code: 0 no FAIL, 1 at least one FAIL, 2 unusable input (no database, bad anchor).

  python scripts\\native_acceptance_report.py --db <native.sqlite3> --log forge-mod\\run\\logs\\latest.log [--log older.log] [--anchor x,y,z] [--json out.json]
Scenario ids (A*) map onto the data pack tiles (S*): A1=S1, A2=S5..S7, A3=S3, A3b=S2, A4=interleaving, A5=rejoin, A6=S8 row, A7=failures/HOLD, A0=denied.
"""
import argparse
import json
import math
import re
import sqlite3
import sys
from pathlib import Path

PASS, FAIL, INCONCLUSIVE, NOT_TESTED = 'PASS', 'FAIL', 'INCONCLUSIVE', 'NOT_TESTED'
UNRESOLVED = ('RESERVED', 'EXECUTING', 'HOLD_IN_DOUBT')
VERIFIED = 'EFFECT_VERIFIED'
ANCHOR_RE = re.compile(r'anchor \(SW corner, feet level\): \[(-?[\d.]+)d?, (-?[\d.]+)d?, (-?[\d.]+)d?\]')
REFUSAL_KINDS = {                                 # fragments of the real refusal messages of the guarded systems
    'inventory': 'a native world action owns this NPC\'s inventory',
    'follow': 'C2-F1 refused: a native world action owns this NPC',
    'motion': 'Native world action active; motion refused',
    'goal': 'C2 refused: a native world action owns this NPC',
}


class Evidence:
    def __init__(self, db_path, log_paths, anchor=None):
        self.lines = []
        for p in log_paths:
            self.lines += Path(p).read_text(encoding='utf-8', errors='replace').splitlines()
        self.anchor = anchor
        if self.anchor is None:
            for line in reversed(self.lines):
                m = ANCHOR_RE.search(line)
                if m:
                    self.anchor = tuple(float(g) for g in m.groups())
                    break
        self.tables = False
        self.runs, self.claims, self.holds, self.events = [], [], [], []
        uri = Path(db_path).resolve().as_uri() + '?mode=ro'
        with sqlite3.connect(uri, uri=True) as db:
            db.execute('PRAGMA query_only=1')
            have = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if {'cplus_wn_runs', 'cplus_wn_terminals', 'cplus_wn_claims', 'cplus_wn_holds', 'cplus_wn_binding_events'} <= have:
                self.tables = True
                for r in db.execute('''SELECT r.task_id,r.actor_id,r.session_id,r.action_kind,r.state,r.target_json,r.created_at,
                        t.evidence_json,t.finalized_at FROM cplus_wn_runs r LEFT JOIN cplus_wn_terminals t ON t.task_id=r.task_id ORDER BY r.created_at,r.rowid'''):
                    ev = json.loads(r[7]) if r[7] else {}
                    self.runs.append({'task': r[0], 'actor': r[1], 'session': r[2], 'kind': r[3], 'state': r[4], 'target': json.loads(r[5]),
                                      'created': r[6], 'receipt': ev, 'finalized': r[8]})
                self.claims = [{'task': r[0], 'kind': r[1], 'state': r[2]} for r in db.execute('SELECT task_id,kind,state FROM cplus_wn_claims')]
                self.holds = [{'task': r[0], 'reason': r[1]} for r in db.execute('SELECT task_id,reason FROM cplus_wn_holds')]
                self.events = [r[0] for r in db.execute('SELECT kind FROM cplus_wn_binding_events ORDER BY id')]

    def chat(self, fragment):
        return [ln for ln in self.lines if fragment in ln]

    def block_runs(self, dx, dy, dz):
        if self.anchor is None:
            return []
        x, y, z = (math.floor(self.anchor[0] + dx), math.floor(self.anchor[1] + dy), math.floor(self.anchor[2] + dz))
        return [r for r in self.runs if r['kind'] == 'break_block'
                and (r['target'].get('x'), r['target'].get('y'), r['target'].get('z')) == (x, y, z)]

    def last_world_line(self, tag):
        """Latest '<tag> PASS' / '<tag> FAIL' line of the data pack checks, or None."""
        found = None
        for ln in self.lines:
            for verdict in (PASS, FAIL):
                if f'{tag} {verdict}' in ln:
                    found = verdict
        return found


def row(sid, title, status, notes, evidence=()):
    return {'id': sid, 'title': title, 'status': status, 'notes': notes, 'evidence': list(evidence)}


def short(run):
    return f"{run['task'][:8]} {run['kind']} {run['state']}"


def a0_denied(ev):
    attempted = ev.chat('not on the allow-list') + ev.chat('block entity') + ev.chat('Native effects refused')
    pass_lines = ev.chat('DENY-diamond PASS') + ev.chat('DENY-chest PASS')
    fail_lines = ev.chat('DENY-diamond FAIL') + ev.chat('DENY-chest FAIL')
    runs = ev.block_runs(34, 0, 6) + ev.block_runs(36, 0, 6)
    if fail_lines or runs:
        return row('A0', 'Denied targets stay untouched and never reach the ledger', FAIL, 'a denied block was broken or reserved',
                   [short(r) for r in runs] + fail_lines[:2])
    if not attempted:
        return row('A0', 'Denied targets stay untouched and never reach the ledger', NOT_TESTED, 'press Brk looking at the diamond block and at the chest, then run check/denied')
    if len(pass_lines) < 2:
        return row('A0', 'Denied targets stay untouched and never reach the ledger', INCONCLUSIVE, 'refusals seen; run /function npcortex_native:check/denied', attempted[:2])
    return row('A0', 'Denied targets stay untouched and never reach the ledger', PASS, 'refused at preview, no ledger row, world untouched', attempted[:2] + pass_lines[:2])


def a1_break(ev):
    title = 'S1 native timed break (EFFECT_VERIFIED + world)'
    runs = ev.block_runs(4, 0, 6)
    world = ev.last_world_line('S1')
    ok = [r for r in runs if r['state'] == VERIFIED]
    if not runs and world is None:
        return row('A1', title, NOT_TESTED, 'press Brk then Confirm on the S1 log, then run check/s1_break')
    if len(ok) > 1:
        return row('A1', title, FAIL, 'two verified terminals for one block', [short(r) for r in ok])
    if not ok:
        if world == PASS:
            return row('A1', title, INCONCLUSIVE, 'the log is gone but the ledger has no verified break for it (broken by hand?)', [short(r) for r in runs])
        bad = [r for r in runs if r['state'] not in UNRESOLVED]
        return row('A1', title, FAIL if bad else INCONCLUSIVE, 'no EFFECT_VERIFIED break; see A7 for the typed outcome', [short(r) for r in runs])
    r = ok[0]['receipt']
    evid = [short(ok[0]), f"receipt evidence: {json.dumps(r.get('evidence', {}), sort_keys=True)[:200]}"]
    if world is None:
        return row('A1', title, INCONCLUSIVE, 'ledger verified; run /function npcortex_native:check/s1_break for the world side', evid)
    if world == FAIL:
        return row('A1', title, FAIL, 'ledger says verified but the world still shows the log', evid)
    after = r.get('evidence', {}).get('after_block')
    if r.get('evidence', {}).get('destroy_returned') is not True or after != 'minecraft:air':
        return row('A1', title, FAIL, 'receipt evidence does not show a removed block', evid)
    return row('A1', title, PASS, 'ledger EFFECT_VERIFIED with a removed block, and the world agrees', evid)


def a2_pickup(ev):
    title = 'Native item pickup (actual inventory transfer)'
    runs = [r for r in ev.runs if r['kind'] == 'pickup_item']
    if not runs:
        return row('A2', title, NOT_TESTED, 'press Pck then Confirm next to a dropped stack (S5/S6/S7)')
    full = []
    for r in runs:
        e = r['receipt'].get('evidence', {})
        if r['state'] == VERIFIED and e.get('delta', 0) > 0 and e.get('journal') == 'COMMITTED' and e.get('entity_count_after') == 0 \
                and e.get('delta') == e.get('event_count'):
            full.append(r)
    if not full:
        partial = [r for r in runs if r['state'] == 'EFFECT_PARTIAL']
        return row('A2', title, INCONCLUSIVE, 'no fully verified pickup' + (' (a typed PARTIAL exists: valid, needs a second pickup)' if partial else ''), [short(r) for r in runs])
    seen = ev.chat('Pickup complete: EFFECT_VERIFIED')
    evid = [short(full[0]), f"inventory delta {full[0]['receipt']['evidence']['delta']}, journal COMMITTED, entity gone"]
    if not seen:
        return row('A2', title, INCONCLUSIVE, 'ledger verified but the in-game "Pickup complete" line is not in the supplied log', evid)
    return row('A2', title, PASS, 'inventory delta == pickup event == entity reduction, journal committed, in-game report seen', evid + seen[:1])


def a3_contention(ev):
    title = 'S3 two NPCs, one block: exactly one claimant'
    runs = ev.block_runs(13, 0, 6)
    ok = [r for r in runs if r['state'] == VERIFIED]
    conflicts = ev.chat('CLAIM_CONFLICT')
    if not runs and not conflicts:
        return row('A3', title, NOT_TESTED, 'two NPCs: Brk + Confirm on the S3 log within a second of each other')
    if len(ok) > 1 or len({r['actor'] for r in runs if r['state'] not in ('FAILED_NO_EFFECT', 'CANCELLED_NO_EFFECT')}) > 1:
        return row('A3', title, FAIL, 'more than one NPC owned the block', [short(r) for r in runs])
    if len(ok) == 1 and conflicts and ev.last_world_line('S3') == PASS:
        return row('A3', title, PASS, 'one verified break, the other NPC refused with CLAIM_CONFLICT, the log is gone', [short(ok[0])] + conflicts[:1])
    return row('A3', title, INCONCLUSIVE, 'need one verified break AND a CLAIM_CONFLICT refusal AND check/s3_contention', [short(r) for r in runs] + conflicts[:1])


def a3b_adjacent(ev):
    title = 'S2 adjacent logs, two NPCs (no false conflict)'
    runs = ev.block_runs(8, 0, 6) + ev.block_runs(9, 0, 6)
    if not runs:
        return row('A3b', title, NOT_TESTED, 'two NPCs: one Brk on each S2 log')
    ok = [r for r in runs if r['state'] == VERIFIED]
    if len({(r['target']['x'], r['target']['z']) for r in ok}) == 2 and ev.chat('S2a PASS') and ev.chat('S2b PASS'):
        return row('A3b', title, PASS, 'both adjacent logs verified and gone', [short(r) for r in ok])
    return row('A3b', title, INCONCLUSIVE, 'need both logs verified and check/s2_adjacent', [short(r) for r in runs])


def a4_interleave(ev):
    title = 'S4 inventory / Follow / Motion / goal refused during mining'
    kinds = [k for k, frag in REFUSAL_KINDS.items() if ev.chat(frag)]
    double = {}
    for r in ev.runs:
        if r['kind'] == 'break_block' and r['state'] == VERIFIED:
            double.setdefault((r['target']['x'], r['target']['y'], r['target']['z']), []).append(r)
    if any(len(v) > 1 for v in double.values()):
        return row('A4', title, FAIL, 'one block was verified-broken twice', [])
    if not kinds:
        return row('A4', title, NOT_TESTED, 'while an NPC mines: try give/Follow/move/goal on it, then wait for the terminal')
    if len(kinds) < 3:
        return row('A4', title, INCONCLUSIVE, f'refusal seen for {kinds}; try the missing kinds ({sorted(set(REFUSAL_KINDS) - set(kinds))})')
    stuck = [r for r in ev.runs if r['state'] in ('RESERVED', 'EXECUTING')]
    if stuck:
        return row('A4', title, INCONCLUSIVE, 'an attempt is still running or was cut off; wait or restart then Rec.', [short(r) for r in stuck])
    return row('A4', title, PASS, 'guards refused ' + ', '.join(kinds) + '; no attempt left running (E3/G3 move is evidence-only: note what you saw)')


def a5_rejoin(ev):
    title = 'Save & Quit / Rejoin / Reconcile'
    binds = [k for k in ev.events if k.startswith('BOUND')]
    sessions = {r['session'] for r in ev.runs}
    if len(binds) < 2 and len(sessions) < 2:
        return row('A5', title, NOT_TESTED, 'quit mid-mining, relaunch, open the Manager, press Rec.')
    holds = [r for r in ev.runs if r['state'] == 'HOLD_IN_DOUBT']
    if holds:
        return row('A5', title, INCONCLUSIVE, 'a HOLD_IN_DOUBT is still open: the Owner decides (Rec.); nothing is cleared automatically', [short(r) for r in holds])
    live = [r for r in ev.runs if r['state'] in ('RESERVED', 'EXECUTING')]
    if live:
        return row('A5', title, INCONCLUSIVE, 'an attempt is unresolved after the rejoin', [short(r) for r in live])
    if len(binds) < 2:
        return row('A5', title, INCONCLUSIVE, 'only one binding recorded: the world was not re-bound')
    return row('A5', title, PASS, 'world re-bound, no attempt left unresolved', [f'binding events: {binds}'])


def a6_multi(ev):
    title = 'S8 multi-NPC row (1/3/5/10) functional'
    if ev.anchor is None:
        return row('A6', title, NOT_TESTED, 'no anchor known')
    z = math.floor(ev.anchor[2] + 12)
    row_runs = [r for r in ev.runs if r['kind'] == 'break_block' and r['target'].get('z') == z and r['state'] == VERIFIED]
    actors = {r['actor'] for r in row_runs}
    n = len(actors)
    if n == 0:
        return row('A6', title, NOT_TESTED, 'spawn NPCs (prep/spawn_ten) and Brk + Confirm each on its own log of the row')
    tiers = [t for t in (1, 3, 5, 10) if n >= t]
    gone = [int(m.group(1)) for ln in ev.lines for m in [re.search(r'S8: logs gone: (\d+)', ln)] if m]
    notes = f'{n} distinct NPCs verified on the row; tiers reached {tiers}; concurrency here is functional only, NOT an MSPT result'
    if n >= 10 and gone and gone[-1] >= 10:
        return row('A6', title, PASS, notes, [f'S8 world count {gone[-1]}/10'])
    return row('A6', title, INCONCLUSIVE, notes + '; PASS needs 10 verified and check/row = 10', [f'S8 world count {gone[-1] if gone else "not run"}'])


def a7_failures(ev):
    title = 'Failure / HOLD / claim inspection (nothing is cleared)'
    runs_by_task = {r['task']: r for r in ev.runs}
    odd = [c for c in ev.claims if c['state'] in ('ACTIVE', 'HELD')
           and (c['task'] not in runs_by_task or runs_by_task[c['task']]['state'] not in UNRESOLVED)]
    if odd:
        return row('A7', title, FAIL, 'a claim is ACTIVE/HELD but its attempt is terminal or unknown (leak)', [f"{c['task'][:8]} {c['kind']} {c['state']}" for c in odd])
    unresolved = [r for r in ev.runs if r['state'] in UNRESOLVED]
    typed = [r for r in ev.runs if r['state'] in ('FAILED_NO_EFFECT', 'CANCELLED_NO_EFFECT') or r['state'].startswith('RECONCILED')]
    evid = [f"{short(r)} reason={r['receipt'].get('reason', '')}" for r in typed] + [f"{short(r)} (unresolved)" for r in unresolved]
    held = [c for c in ev.claims if c['state'] == 'HELD']
    if unresolved:
        return row('A7', title, INCONCLUSIVE, 'unresolved attempts need an Owner decision (Rec.); HELD claims are kept on purpose' + (f' ({len(held)} HELD)' if held else ''), evid)
    if not typed and not ev.holds:
        return row('A7', title, NOT_TESTED, 'no failure or HOLD happened yet (try a Brk with a wrong tool or quit mid-pickup)')
    return row('A7', title, PASS, 'every failure is typed, no claim leaked, nothing left unresolved', evid)


def evaluate(ev):
    if not ev.tables:
        return [row(s, t, NOT_TESTED, 'the database has no native tables: no native request ever reached this backend')
                for s, t in (('A0', 'denied'), ('A1', 'break'), ('A2', 'pickup'), ('A3', 'contention'), ('A3b', 'adjacent'),
                             ('A4', 'interleave'), ('A5', 'rejoin'), ('A6', 'multi'), ('A7', 'failures'))]
    return [f(ev) for f in (a0_denied, a1_break, a2_pickup, a3_contention, a3b_adjacent, a4_interleave, a5_rejoin, a6_multi, a7_failures)]


def overall(rows):
    states = {r['status'] for r in rows}
    if FAIL in states:
        return FAIL
    if states == {PASS}:
        return PASS
    return NOT_TESTED if states == {NOT_TESTED} else INCONCLUSIVE


def main(argv=None):
    p = argparse.ArgumentParser()
    p.add_argument('--db', required=True)
    p.add_argument('--log', action='append', default=[])
    p.add_argument('--anchor', default='')
    p.add_argument('--json', default='')
    a = p.parse_args(argv)
    if not Path(a.db).is_file():
        print(f'no database at {a.db}')
        return 2
    logs = [x for x in a.log if Path(x).is_file()]
    if not logs:
        print('no readable log (pass --log <latest.log>): world-side evidence cannot be judged')
        return 2
    anchor = None
    if a.anchor:
        try:
            anchor = tuple(float(v) for v in a.anchor.split(','))
            assert len(anchor) == 3
        except (ValueError, AssertionError):
            print('--anchor needs x,y,z')
            return 2
    ev = Evidence(a.db, logs, anchor)
    if ev.anchor is None:
        print('INCONCLUSIVE: the anchor is unknown. Run /function npcortex_native:info in the game (its chat line is read from the log) or pass --anchor x,y,z')
        return 2
    rows = evaluate(ev)
    print(f'NPCortex native acceptance report | anchor {ev.anchor} | database read-only | logs {len(logs)}')
    for r in rows:
        print(f"[{r['status']:<12}] {r['id']:<4} {r['title']}")
        print(f"               {r['notes']}")
        for e in r['evidence'][:4]:
            print(f'               - {e}')
    total = overall(rows)
    print(f'OVERALL: {total}  (PASS needs every scenario PASS; INCONCLUSIVE/NOT_TESTED is not a pass; gameplay acceptance is the Owner\'s)')
    if a.json:
        Path(a.json).write_text(json.dumps({'overall': total, 'anchor': ev.anchor, 'scenarios': rows}, indent=2), encoding='utf-8')
    return 1 if total == FAIL else 0


if __name__ == '__main__':
    sys.exit(main())
