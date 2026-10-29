"""PAP-G1 TEST-ONLY: backend native-ledger growth on an ISOLATED temp SQLite file (never the live DB), measured separately from
the C1 journal. One actor performs N break_block attempts (reserve/start/finalize); we record file size, row counts, per-op
latency per quarter, and the cost of a backend restart (constructing a second store = sweep) and a count query.
Usage: python scripts/pap/pap_g1_ledger_growth.py [--sizes 512 2048 4096]"""
import argparse
import sqlite3
import statistics
import sys
import tempfile
import time
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import measure_native_ledger as m  # noqa: E402  (also puts backend/src on sys.path)

TABLES = ('cplus_wn_runs', 'cplus_wn_terminals', 'cplus_wn_claims', 'cplus_wn_holds')


def run(n):
    with tempfile.TemporaryDirectory() as tmp:
        db = Path(tmp) / 'growth.db'
        store = m.NativeEffectStore(db)
        world, session, actor = str(uuid4()), str(uuid4()), str(uuid4())
        store.bind({'schema_version': 1, 'world_id': world, 'folder_key': 'Growth', 'epoch': 0, 'session_id': session, 'contract': 'native-effects-v1'})
        quarters, cur, errors = [], [], 0
        for i in range(n):
            b = m.body_for(world, session, actor, i)
            t = time.perf_counter()
            try:
                store.reserve(b); store.start(m.ident(b)); store.finalize(m.receipt(b))
            except Exception:  # noqa: BLE001 - counted, not hidden
                errors += 1
            cur.append((time.perf_counter() - t) * 1000)
            if len(cur) == max(1, n // 4):
                quarters.append(round(statistics.median(cur), 2)); cur = []
        size = sum(p.stat().st_size for p in Path(tmp).glob('growth.db*'))
        con = sqlite3.connect(db)
        rows = {t: con.execute(f'select count(*) from {t}').fetchone()[0] for t in TABLES}
        con.close()
        t0 = time.perf_counter(); m.NativeEffectStore(db); restart = (time.perf_counter() - t0) * 1000
        return {'actions': n, 'errors': errors, 'db_bytes_incl_wal': size, 'bytes_per_action': round(size / n), 'rows': rows,
                'median_ms_per_action_by_quarter': quarters, 'restart_ms': round(restart, 1)}


if __name__ == '__main__':
    ap = argparse.ArgumentParser(); ap.add_argument('--sizes', nargs='+', type=int, default=[512, 2048, 4096])
    for n in ap.parse_args().sizes:
        print(run(n))
