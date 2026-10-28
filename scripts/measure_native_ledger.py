"""Stage 4 native ledger: latency and lock-wait OBSERVATIONS for 1/3/5/10 concurrent actors on a temporary SQLite file.

Two layers are measured separately: the store called directly from N threads on separate connections (SQLite writer
serialization, BEGIN IMMEDIATE waits) and the same flow over real loopback HTTP through the real handler (transport +
handler + store). These are OBSERVATIONS on this machine; they are not an acceptance of G4 performance and say nothing about
Minecraft MSPT. Usage: python scripts\\measure_native_ledger.py [--attempts 12] [--json out.json]"""
import argparse
import http.client
import json
import statistics
import sys
import tempfile
import threading
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'backend' / 'src'))
sys.path.insert(0, str(ROOT / 'backend' / 'tests'))

from npcortex_backend.cplus_e_graph import GraphConflict  # noqa: E402
from npcortex_backend.cplus_wn_store import NativeEffectStore  # noqa: E402
from npcortex_backend.server import make_http_server  # noqa: E402

TOKEN = 'a-long-local-measurement-secret-not-a-real-credential'


def body_for(world, session, actor, x):
    return {'schema_version': 1, 'task_id': str(uuid4()), 'request_id': str(uuid4()), 'attempt_id': str(uuid4()), 'world_id': world,
            'actor_id': actor, 'profile_id': str(uuid4()), 'session_id': session, 'requester_id': str(uuid4()),
            'dimension_id': 'minecraft:overworld', 'action_kind': 'break_block', 'origin': 'MANAGER_PACKET',
            'authorization': {'decision': 'ALLOW', 'entry_id': 'measure', 'policy_sha256': 'b' * 64},
            'target': {'kind': 'block', 'x': x, 'y': 64, 'z': 5, 'block_id': 'minecraft:oak_log', 'state_sha256': 'a' * 64}}


def ident(b):
    return {k: b[k] for k in ('task_id', 'attempt_id', 'world_id', 'actor_id', 'session_id', 'requester_id')}


def receipt(b):
    r = {k: b[k] for k in ('task_id', 'attempt_id', 'world_id', 'actor_id', 'session_id')}
    t = b['target']
    r.update(schema_version=1, kind='native_effect_receipt', action_kind='break_block', result='EFFECT_VERIFIED', reason='', server_tick=1,
             executed=True, evidence={'x': t['x'], 'y': t['y'], 'z': t['z'], 'before_block': t['block_id'], 'after_block': 'minecraft:air',
                                      'destroy_returned': True, 'tool_before': 'minecraft:air', 'tool_after': 'minecraft:air',
                                      'tool_broke': False, 'drops': [], 'collateral': 'NONE', 'journal': 'NOT_REQUIRED'})
    return r


def pct(values, p):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * p))]


def summarize(samples):
    ms = [s * 1000.0 for s in samples]
    return {'calls': len(ms), 'p50_ms': round(statistics.median(ms), 2), 'p95_ms': round(pct(ms, 0.95), 2), 'max_ms': round(max(ms), 2)}


def run_direct(db, actors, attempts):
    store = NativeEffectStore(db)
    world, session = str(uuid4()), str(uuid4())
    store.bind({'schema_version': 1, 'world_id': world, 'folder_key': 'Measure', 'epoch': 0, 'session_id': session, 'contract': 'native-effects-v1'})
    samples, errors = [], []
    lock = threading.Lock()

    def worker(index):
        # ONE store per process, exactly like the backend: constructing a second store on a live database settles (HOLDs / fails)
        # every unfinished attempt, because a construction is a "backend restart".
        mine = store
        actor = str(uuid4())
        for n in range(attempts):
            b = body_for(world, session, actor, index * 1000 + n)
            for op, payload in (('reserve', b), ('start', ident(b)), ('finalize', receipt(b))):
                t = time.perf_counter()
                try:
                    getattr(mine, op)(payload)
                except Exception as exc:                                                     # noqa: BLE001 - recorded, not hidden
                    with lock:
                        errors.append(f'{op}: {exc!r}')
                with lock:
                    samples.append(time.perf_counter() - t)
    threads = [threading.Thread(target=worker, args=(i,)) for i in range(actors)]
    started = time.perf_counter()
    [t.start() for t in threads]
    [t.join() for t in threads]
    wall = time.perf_counter() - started
    return {**summarize(samples), 'errors': len(errors), 'first_error': errors[0] if errors else '', 'wall_s': round(wall, 2),
            'attempts_per_s': round(actors * attempts / wall, 1)}


def run_http(db, actors, attempts):
    service = SimpleNamespace(_require_world_store=lambda: SimpleNamespace(db_path=db), model='mock')
    server = make_http_server(service, port=0)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    world, session = str(uuid4()), str(uuid4())
    samples, errors = [], []
    lock = threading.Lock()

    def post(path, payload):
        c = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=10)
        c.request('POST', path, json.dumps(payload), {'Content-Type': 'application/json', 'X-NPCortex-CPlus-Token': TOKEN})
        r = c.getresponse()
        data = r.read()
        c.close()
        return r.status, data

    with patch('npcortex_backend.server.read_secret', return_value=TOKEN):
        post('/v1/cplus/wn/bind', {'schema_version': 1, 'world_id': world, 'folder_key': 'Measure', 'epoch': 0, 'session_id': session, 'contract': 'native-effects-v1'})

        def worker(index):
            actor = str(uuid4())
            for n in range(attempts):
                b = body_for(world, session, actor, index * 1000 + n)
                for op, payload in (('reserve', b), ('start', ident(b)), ('finalize', receipt(b))):
                    t = time.perf_counter()
                    status, _ = post('/v1/cplus/wn/' + op, payload)
                    with lock:
                        samples.append(time.perf_counter() - t)
                        if status != 200:
                            errors.append(f'{op}: HTTP {status}')
        threads = [threading.Thread(target=worker, args=(i,)) for i in range(actors)]
        started = time.perf_counter()
        [t.start() for t in threads]
        [t.join() for t in threads]
        wall = time.perf_counter() - started
    server.shutdown()
    server.server_close()
    return {**summarize(samples), 'errors': len(errors), 'first_error': errors[0] if errors else '', 'wall_s': round(wall, 2),
            'attempts_per_s': round(actors * attempts / wall, 1)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--attempts', type=int, default=12)
    parser.add_argument('--json', default='')
    args = parser.parse_args()
    out = {'note': 'OBSERVATION on this machine, temporary database; not an acceptance and not MSPT', 'direct_store': {}, 'http': {}}
    with tempfile.TemporaryDirectory() as tmp:
        for actors in (1, 3, 5, 10):
            out['direct_store'][str(actors)] = run_direct(Path(tmp) / f'direct-{actors}.db', actors, args.attempts)
            out['http'][str(actors)] = run_http(Path(tmp) / f'http-{actors}.db', actors, args.attempts)
            print(f'{actors:>2} actors  store {out["direct_store"][str(actors)]}')
            print(f'{actors:>2} actors  http  {out["http"][str(actors)]}')
    if args.json:
        Path(args.json).write_text(json.dumps(out, indent=2), encoding='utf-8')
    failed = [k for layer in ('direct_store', 'http') for k, v in out[layer].items() if v['errors']]
    if failed:
        print('ERRORS in', failed)
        return 1
    print('MEASURE: no errors (observations only)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
