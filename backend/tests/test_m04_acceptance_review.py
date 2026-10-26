"""Read-only gameplay acceptance reviewer fixtures; no Minecraft runtime claims."""
import importlib.util
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing, contextmanager
from pathlib import Path

SCRIPT=Path(__file__).resolve().parents[2] / 'scripts/m04_acceptance_review.py'
spec=importlib.util.spec_from_file_location('m04_review',SCRIPT)
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

@contextmanager
def committed(path):
    # sqlite3's own context manager commits/rolls back but never closes; Windows then cannot delete the file.
    with closing(sqlite3.connect(path)) as conn:
        with conn:
            yield conn

class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.world=Path(self.temp.name)/'world'; self.world.mkdir()
        (self.world/'level.dat').write_bytes(b'fixture')
        self.db=Path(self.temp.name)/'backend.sqlite3'
        with committed(self.db) as db:
            db.execute('CREATE TABLE cplus_e3_g2_moves (task_id TEXT,attempt_id TEXT,world_id TEXT,actor_id TEXT,state TEXT,goal_id TEXT)')
    def tearDown(self): self.temp.cleanup()
    def capture(self,world='world-1',actor='actor-1'):
        directory=self.world/'data/npcortex/m04-evidence';directory.mkdir(parents=True,exist_ok=True)
        (directory/'m04-fixture.json').write_text(json.dumps({'capture_kind':'M04_PREVIEW_ONLY',
              'world_id':world,'actor_id':actor,'session_id':'s1','capture_tick':3,
              'mutation_permitted':False}),'utf8')
    def test_missing_evidence_is_not_pass(self):
        r=mod.review(self.world,self.db)
        self.assertEqual(r['snapshots'],[])
        self.assertTrue(r['verdict'].startswith('HOLD'))
    def test_matching_g2_hold_detected(self):
        self.capture()
        with committed(self.db) as db:
            db.execute('INSERT INTO cplus_e3_g2_moves VALUES (?,?,?,?,?,?)',
                       ('task','attempt','world-1','actor-1','HOLD',''))
        r=mod.review(self.world,self.db)
        self.assertEqual(len(r['unresolved_movement_claims']),1)
        self.assertEqual(r['unresolved_movement_claims'][0]['state'],'HOLD')
    def test_foreign_backend_does_not_correlate(self):
        self.capture()
        with committed(self.db) as db:
            db.execute('INSERT INTO cplus_e3_g2_moves VALUES (?,?,?,?,?,?)',
                       ('task','attempt','other-world','other-actor','COMPLETED','goal'))
        r=mod.review(self.world,self.db)
        self.assertEqual(r['historical_movement_rows'],[])
        self.assertNotEqual(r['verdict'],'PASS')
    def test_malformed_snapshot_fails(self):
        self.capture()
        path=self.world/'data/npcortex/m04-evidence/m04-fixture.json'
        path.write_text('{}')
        with self.assertRaises(ValueError):mod.review(self.world,self.db)

if __name__=='__main__':unittest.main()
