"""PAP-G2 TEST-ONLY mutation probe: breaks ONE enforcement rule at a time in a TEMP COPY of backend/src and requires
test_cplus_pap_store (plus the accepted native store tests) to fail. Production is never written.
Usage: python scripts/pap/pap_g2_mutation_probe.py"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
P = 'npcortex_backend/cplus_pap_store.py'
W = 'npcortex_backend/cplus_wn_store.py'
MUTATIONS = [
    ('PAP ignores native block claims', P, "if self._has(db, 'cplus_wn_claims') and db.execute(", "if False and db.execute("),
    ('native break ignores PAP block claims', W, "if db.execute(\"SELECT 1 FROM sqlite_master WHERE type='table' AND name='cplus_pap_claims'\").fetchone() and db.execute(", "if False and db.execute("),
    ('PAP skips the actor fence', P, 'self._actor_fence(db, body[\'world_id\'], body[\'actor_id\'])', 'pass'),
    ('native fence forgets PAP rows', W, "('cplus_pap_runs', ('RESERVED', 'EXECUTING', 'HOLD_IN_DOUBT'), 'a PAP place/use attempt')", "('cplus_pap_runs', ('NEVER',), 'x')"),
    ('PAP claim index dropped', P, 'CREATE UNIQUE INDEX IF NOT EXISTS cplus_pap_claim_cell', 'CREATE INDEX IF NOT EXISTS cplus_pap_claim_cell'),
    ('support cell not claimed', P, "if 'support_x' in target:", "if False:"),
    ('HOLD leaves claims ACTIVE-not-HELD', P, "UPDATE cplus_pap_claims SET state='HELD',updated_at=? WHERE task_id=? AND state='ACTIVE'\", (now, body['task_id'])", "UPDATE cplus_pap_claims SET state='ACTIVE',updated_at=? WHERE task_id=? AND state='ACTIVE'\", (now, body['task_id'])"),
    ('finalize keeps claims', P, "db.execute(\"UPDATE cplus_pap_claims SET state='RELEASED',updated_at=? WHERE task_id=?\", (now, row['task_id']))", "pass"),
    ('place evidence no longer needs a consumed item', P, "and e['item_before'] - e['item_after'] == 1", ""),
    ('use success without a state change accepted', P, "if not (ok and changed):", "if False:"),
    ('terminal re-judged instead of exact replay', P, "if done == (outcome, digest):\n                    return self._terminal(db, row)          # lost ACK", "if False:\n                    return self._terminal(db, row)          # lost ACK"),
    ('start without a bound session accepted', P, "if row['state'] == 'RESERVED' and not self._bound(db, body['world_id'], body['session_id']):", "if False:"),
    ('restart leaves EXECUTING alone', P, "for (task,) in db.execute(\"SELECT task_id FROM cplus_pap_runs WHERE state='EXECUTING'\" + scope, args).fetchall():", "for (task,) in []:"),
]


def run(tree):
    env = dict(os.environ, PYTHONPATH=os.pathsep.join([str(tree / 'src'), str(ROOT / 'backend' / 'tests')]))
    r = subprocess.run([sys.executable, '-m', 'unittest', 'test_cplus_pap_store', 'test_cplus_wn_store'], cwd=ROOT / 'backend' / 'tests',
                       env=env, capture_output=True, text=True)
    return r.returncode, r.stderr


def main():
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp)
        shutil.copytree(ROOT / 'backend' / 'src', tree / 'src', ignore=shutil.ignore_patterns('__pycache__'))
        code, err = run(tree)
        print(f'control (unmutated copy): {"PASS" if code == 0 else "FAIL"}')
        ok &= code == 0
        for name, rel, old, new in MUTATIONS:
            path = tree / 'src' / rel
            original = path.read_text(encoding='utf-8')
            if old not in original:
                print(f'PROBE-ERROR {name}: target text not found'); ok = False; continue
            path.write_text(original.replace(old, new, 1), encoding='utf-8')
            code, err = run(tree)
            path.write_text(original, encoding='utf-8')
            first = next((l for l in err.splitlines() if l.startswith(('FAIL:', 'ERROR:'))), '')
            print(f'{"DETECTED" if code else "MISSED  "} {name:50} {first[:60]}')
            ok &= code != 0
    print('G2 MUTATION PROBE:', 'PASS (every violation detected)' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
