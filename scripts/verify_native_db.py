"""Inspects the temporary SQLite file of a real-backend native GameTest run and prints what the ledger recorded.

READ-ONLY (it opens the file with mode=ro). Exit 0 only if every invariant holds:
  - no attempt is left RESERVED or EXECUTING (a crash would leave those; a clean run must not);
  - no claim is left ACTIVE or HELD;
  - the terminal rows match the runs one to one (terminal state == run state) for every terminal state;
  - the outcomes are exactly those the real-backend tests are designed to produce.
Usage: python scripts\\verify_native_db.py <db> [--outbox <dir>]"""
import argparse
import sqlite3
import sys
from collections import Counter
from pathlib import Path

EXPECTED = {
    # real_01 break + pickup (2), real_02 one winner (1), real_03 cancelled event (1) + exception hold reconciled (1), real_04 repair (1),
    # real_05 ten parallel breaks (10)
    'EFFECT_VERIFIED': 2 + 1 + 1 + 10,
    'FAILED_NO_EFFECT': 1,
}
RECONCILED = ('RECONCILED_PERSISTED_STATE_UNCHANGED', 'RECONCILED_EFFECT_OBSERVED')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('db')
    p.add_argument('--outbox', default='')
    a = p.parse_args()
    uri = Path(a.db).resolve().as_uri() + '?mode=ro'
    db = sqlite3.connect(uri, uri=True)
    present = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if 'cplus_wn_runs' not in present:
        print('PROBLEMS:\n  - the native tables do not exist: no native request ever reached the backend')
        return 1
    runs = Counter(r[0] for r in db.execute('SELECT state FROM cplus_wn_runs'))
    kinds = Counter(r[0] for r in db.execute('SELECT action_kind FROM cplus_wn_runs'))
    claims = Counter(r[0] for r in db.execute('SELECT state FROM cplus_wn_claims'))
    terminals = Counter(r[0] for r in db.execute('SELECT outcome FROM cplus_wn_terminals'))
    mismatch = db.execute('SELECT COUNT(*) FROM cplus_wn_runs r JOIN cplus_wn_terminals t ON t.task_id=r.task_id WHERE r.state<>t.outcome').fetchone()[0]
    bindings = db.execute('SELECT world_id, folder_key, epoch, saved_epoch FROM cplus_wn_bindings').fetchall()
    events = Counter(r[0] for r in db.execute('SELECT kind FROM cplus_wn_binding_events'))
    holds = db.execute('SELECT reason FROM cplus_wn_holds').fetchall()
    print('runs      :', dict(runs))
    print('actions   :', dict(kinds))
    print('claims    :', dict(claims))
    print('terminals :', dict(terminals))
    print('holds     :', [h[0] for h in holds])
    print('bindings  :', [(b[1], 'epoch', b[2], 'saved', b[3]) for b in bindings], dict(events))
    if a.outbox and Path(a.outbox).is_dir():
        files = sorted(f.name.split('-')[-1] for f in Path(a.outbox).glob('*.json'))
        print('outbox    :', dict(Counter(files)), '(files are evidence; none is replayed or deleted)')
    problems = []
    if runs.get('RESERVED') or runs.get('EXECUTING'):
        problems.append('an attempt is still RESERVED/EXECUTING')
    if claims.get('ACTIVE') or claims.get('HELD'):
        problems.append('a claim is still ACTIVE/HELD')
    if mismatch:
        problems.append(f'{mismatch} terminal row(s) disagree with their run')
    if runs.get('HOLD_IN_DOUBT'):
        problems.append('an attempt is still HOLD_IN_DOUBT (every hold in the real tests is reconciled or repaired)')
    for state, n in EXPECTED.items():
        if runs.get(state, 0) != n:
            problems.append(f'{state}: expected {n}, found {runs.get(state, 0)}')
    if sum(runs.get(s, 0) for s in RECONCILED) != 1:
        problems.append('expected exactly one reconciled hold')
    if not bindings or not bindings[0][3] >= 0:
        problems.append('no binding row')
    if problems:
        print('PROBLEMS:')
        for q in problems:
            print('  -', q)
        return 1
    print('LEDGER OK: the real backend recorded exactly the expected outcomes and left nothing open')
    return 0


if __name__ == '__main__':
    sys.exit(main())
