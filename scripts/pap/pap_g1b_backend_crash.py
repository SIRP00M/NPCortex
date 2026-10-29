"""PAP-G1b TEST-ONLY X0: REAL process kill of a backend process on an ISOLATED temp SQLite file (never the live DB).
Child A reserves a native break and dies (os._exit) before START; child B reserves + STARTs (durable START ACK) and dies before
any terminal. A fresh process then opens the store (= backend restart sweep) and reports what the ledger decided.
Usage: python scripts/pap/pap_g1b_backend_crash.py"""
import json
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent.parent
CHILD = r'''
import json, os, sys
sys.path.insert(0, sys.argv[1]); sys.path.insert(0, sys.argv[2]); sys.path.insert(0, sys.argv[3])
import measure_native_ledger as m
db, mode, world, session, actor, x = sys.argv[4], sys.argv[5], sys.argv[6], sys.argv[7], sys.argv[8], int(sys.argv[9])
store = m.NativeEffectStore(db)
if mode == 'bind':
    store.bind({'schema_version': 1, 'world_id': world, 'folder_key': 'X0', 'epoch': 0, 'session_id': session, 'contract': 'native-effects-v1'})
    print(json.dumps({'bound': True})); sys.exit(0)
b = m.body_for(world, session, actor, x)
store.reserve(b)
if mode == 'start':
    store.start(m.ident(b))
print(json.dumps({'task_id': b['task_id']}), flush=True)
os._exit(137)  # hard kill: no finalize, no cancel, no cleanup
'''


def child(db, mode, world, session, actor, x):
    p = subprocess.run([sys.executable, '-c', CHILD, str(ROOT / 'backend' / 'src'), str(ROOT / 'backend' / 'tests'), str(ROOT / 'scripts'),
                        str(db), mode, world, session, actor, str(x)], capture_output=True, text=True)
    return p.returncode, (json.loads(p.stdout.strip().splitlines()[-1]) if p.stdout.strip() else {})


def main():
    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / 'x0.db'
        world, session = str(uuid4()), str(uuid4())
        child(db, 'bind', world, session, str(uuid4()), 0)
        rc_a, a = child(db, 'reserve', world, session, str(uuid4()), 1)
        rc_b, b = child(db, 'start', world, session, str(uuid4()), 2)
        print(f'child A (RESERVED, killed): exit {rc_a}; child B (RESERVED+STARTED, killed): exit {rc_b}')
        con = sqlite3.connect(db)
        before = dict(con.execute('select task_id,state from cplus_wn_runs').fetchall())
        print('after child B started (its own startup already swept child A):', {k[:8]: v for k, v in before.items()})
        con.close()
        child(db, 'bind', world, str(uuid4()), str(uuid4()), 0)  # fresh process, NEW session = restart; constructing the store sweeps
        con = sqlite3.connect(db)
        runs = dict(con.execute('select task_id,state from cplus_wn_runs').fetchall())
        claims = con.execute('select task_id,state from cplus_wn_claims').fetchall()
        print('after restart:', {k[:8]: v for k, v in runs.items()})
        print('claims:', {t[:8]: s for t, s in claims})
        ok = runs.get(a['task_id']) == 'FAILED_NO_EFFECT' and runs.get(b['task_id']) == 'HOLD_IN_DOUBT' \
            and dict(claims).get(a['task_id']) == 'RELEASED' and dict(claims).get(b['task_id']) == 'HELD'
        con.close()
        print('X0 RESULT:', 'PASS (RESERVED -> FAILED_NO_EFFECT + claim RELEASED; EXECUTING -> HOLD_IN_DOUBT + claim HELD; nothing replayed)' if ok else 'FAIL')
        return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
