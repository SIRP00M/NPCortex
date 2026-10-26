"""Self-contained acceptance checks for the OBSERVER; no production DB modifications."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

P = Path(__file__).with_name('m03_evidence_watch.py')
spec = importlib.util.spec_from_file_location('m03_evidence_watch',P)
m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class EvidenceKitTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.world=self.root/'New World (2)'
        self.world.mkdir();(self.world/'level.dat').write_bytes(b'fixture')
        self.game=self.world/'data/npcortex/m03-evidence';self.game.mkdir(parents=True)
        self.outbox=self.world/'data/npcortex/transport';self.outbox.mkdir(parents=True)
        self.worldid='world-A';self.actor='actor-A';self.task='task-A'
        self.db=self.root/'backend.sqlite3'
        with sqlite3.connect(self.db) as con:
            con.execute('CREATE TABLE cplus_e3_g2_moves(task_id TEXT, world_id TEXT, actor_id TEXT, attempt_id TEXT, state TEXT)')
            con.execute('CREATE TABLE cplus_e3_g3_terminals(task_id TEXT, outcome TEXT, evidence_json TEXT)')
            con.execute('INSERT INTO cplus_e3_g2_moves VALUES(?,?,?,?,?)',(self.task,self.worldid,self.actor,'attempt-A','HOLD'))
            con.execute('INSERT INTO cplus_e3_g3_terminals VALUES(?,?,?)',(self.task,'CANCELLED','secret blob should not export'))
        (self.game/'snap.json').write_text(json.dumps({'world_id':self.worldid,'actor_id':self.actor,
            'session_id':'session-A','name':'yui','x':0,'y':64,'z':0,'backend':{'state':'HOLD'}}))
        (self.game/'fault.json').write_text(json.dumps({'kind':'AFTER_SUCCESSFUL_RESPONSE','route':'reserve','task_id':self.task}))
        (self.outbox/'reserve.json').write_text(json.dumps({'route':'/v1/cplus/e3/reserve',
            'body':{'world_id':self.worldid,'actor_id':self.actor,'task_id':self.task,'attempt_id':'attempt-A'}}))
        self.out=self.root/'capture';self.out.mkdir()

    def run_checkpoint(self):
        return m.checkpoint(self.world,self.db,self.out,{},'test')

    def test_matching_database_correlates(self):
        c=self.run_checkpoint()
        corr=c['backend']['correlation']
        self.assertEqual(corr['status'],'MATCHED_WORLD_ACTOR')
        self.assertEqual(corr['matched_outbox_tasks'],[self.task])
        self.assertEqual(len(c['new_files']),3)
        report=m.report(self.out)
        self.assertEqual(report['status'],'COLLECTED_NOT_ACCEPTED')
        self.assertEqual(report['backend_db_matched_checkpoints'],1)

    def test_no_cross_world_false_pass(self):
        with sqlite3.connect(self.db) as con:
            con.execute('DELETE FROM cplus_e3_g2_moves')
            con.execute('INSERT INTO cplus_e3_g2_moves VALUES(?,?,?,?,?)',(self.task,'foreign-world',self.actor,'attempt-A','COMPLETED'))
        corr=self.run_checkpoint()['backend']['correlation']
        self.assertEqual(corr['status'],'NO_MATCHING_G2_ROWS')
        self.assertEqual(corr['matched_outbox_tasks'],[])

    def test_does_not_include_evidence_json_or_tokens(self):
        c=self.run_checkpoint()
        rows=c['backend']['tables']['cplus_e3_g3_terminals']['rows']
        self.assertEqual(rows[0],{'task_id':self.task,'outcome':'CANCELLED'})
        self.assertNotIn('secret blob',json.dumps(c))

    def test_missing_backend_table_holds(self):
        with sqlite3.connect(self.db) as con:
            con.execute('DROP TABLE cplus_e3_g2_moves')
        c=self.run_checkpoint()
        self.assertEqual(c['backend']['tables']['cplus_e3_g2_moves']['status'],'MISSING')
        self.assertEqual(c['backend']['correlation']['status'],'NO_MATCHING_G2_ROWS')

    def test_read_only_database(self):
        prior=self.db.read_bytes()
        self.run_checkpoint()
        self.assertEqual(self.db.read_bytes(),prior)
        self.assertFalse((self.root/'backend.sqlite3-wal').exists())

    def test_retains_raw_fault_event(self):
        self.run_checkpoint()
        self.assertTrue((self.out/'raw/game/fault.json').exists())
        report=m.report(self.out)
        self.assertEqual(report['faults'][0]['kind'],'AFTER_SUCCESSFUL_RESPONSE')

    def test_output_folder_safety(self):
        self.assertEqual(m.root_state(self.world,self.db),(self.world.resolve(),self.db.resolve()))
        with self.assertRaises((ValueError, FileNotFoundError)):
            m.root_state(self.root/'not-a-world',self.db)

    def test_revised_evidence_immutable(self):
        seen={};m.scan_files(self.world,self.out,seen)
        original=(self.out/'raw/game/snap.json').read_bytes()
        (self.game/'snap.json').write_text(json.dumps({'world_id':self.worldid,'actor_id':self.actor,'session_id':'session-B'}))
        add,*_=m.scan_files(self.world,self.out,seen)
        self.assertTrue(any(x['kind']=='game' for x in add))
        self.assertEqual((self.out/'raw/game/snap.json').read_bytes(),original)
        self.assertEqual(len(list((self.out/'raw/game').glob('snap*.json'))),2)

if __name__=='__main__':unittest.main(verbosity=2)
