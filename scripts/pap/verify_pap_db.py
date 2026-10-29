"""Inspects the temporary SQLite file of a real-backend PAP GameTest run (READ-ONLY, opens with mode=ro).

Exit 0 only if every invariant holds:
  - nothing is left RESERVED, EXECUTING or HOLD_IN_DOUBT, and no claim is left ACTIVE or HELD (a clean run leaves none);
  - terminal rows match run states one to one;
  - the outcomes are exactly those the PAP real-backend tests are designed to produce.
Usage: python scripts/pap/verify_pap_db.py <db> [--live]   (--live: only the 'nothing is stuck' invariants, for the Owner's own world)"""
import sqlite3
import sys
from collections import Counter
from pathlib import Path

# place e2e 1 + use e2e 1 + many(1,3,5,10)=19 verified; two hold scenarios reconciled from the observed block.
EXPECTED_STATES = {"EFFECT_VERIFIED": 1 + 1 + 19 + 1, "RECONCILED_EFFECT_OBSERVED": 2}   # + chat place (1)
EXPECTED_KINDS = {"place_block": 1 + 2 + 19 + 1, "use_block": 1}


def main():
    db = sqlite3.connect(Path(sys.argv[1]).resolve().as_uri() + '?mode=ro', uri=True)
    present = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if 'cplus_pap_runs' not in present:
        print('PROBLEMS:\n  - the PAP tables do not exist: no player action ever reached the backend')
        return 1
    runs = Counter(r[0] for r in db.execute('SELECT state FROM cplus_pap_runs'))
    kinds = Counter(r[0] for r in db.execute('SELECT action_kind FROM cplus_pap_runs'))
    claims = Counter(r[0] for r in db.execute('SELECT state FROM cplus_pap_claims'))
    terminals = Counter(r[0] for r in db.execute('SELECT outcome FROM cplus_pap_terminals'))
    holds = [r[0] for r in db.execute('SELECT reason FROM cplus_pap_holds')]
    mismatch = db.execute('SELECT COUNT(*) FROM cplus_pap_runs r JOIN cplus_pap_terminals t ON t.task_id=r.task_id WHERE r.state<>t.outcome').fetchone()[0]
    native = Counter(r[0] for r in db.execute('SELECT state FROM cplus_wn_runs')) if 'cplus_wn_runs' in present else {}
    wn_claims = Counter(r[0] for r in db.execute('SELECT state FROM cplus_wn_claims')) if 'cplus_wn_claims' in present else {}
    print('pap runs     :', dict(runs))
    print('pap actions  :', dict(kinds))
    print('pap claims   :', dict(claims))
    print('pap terminals:', dict(terminals))
    print('pap holds    :', holds)
    print('native runs  :', dict(native), '| native claims:', dict(wn_claims))
    live = '--live' in sys.argv
    problems = []
    for bad in ('RESERVED', 'EXECUTING', 'HOLD_IN_DOUBT'):
        if runs.get(bad):
            problems.append(f'{runs[bad]} PAP run(s) left {bad}')
    for bad in ('ACTIVE', 'HELD'):
        if claims.get(bad) or wn_claims.get(bad):
            problems.append(f'claim(s) left {bad}')
    if mismatch:
        problems.append(f'{mismatch} terminal row(s) disagree with their run')
    if not live and dict(runs) != EXPECTED_STATES:
        problems.append(f'PAP outcomes {dict(runs)} differ from the designed {EXPECTED_STATES}')
    if not live and dict(kinds) != EXPECTED_KINDS:
        problems.append(f'PAP kinds {dict(kinds)} differ from the designed {EXPECTED_KINDS}')
    if sum(terminals.values()) != sum(runs.values()):
        problems.append('terminal rows and runs do not match one to one')
    if not live and sorted(holds) != ['INVENTORY_COMMIT_FAILED'] * 2:
        problems.append(f'holds {holds} differ from the two injected inventory failures')
    if problems:
        print('PROBLEMS:')
        for p in problems:
            print('  -', p)
        return 1
    print('PAP LEDGER OK: every attempt is terminal, no claim is left, outcomes are exactly the designed ones')
    return 0


if __name__ == '__main__':
    sys.exit(main())
