"""Acceptance classifiers must not manufacture gameplay PASS from partial data."""
import argparse
import copy
import json
from pathlib import Path
import tempfile
import unittest
import shutil
import subprocess
import sys
import sqlite3
from contextlib import closing
import m03_final
from m03_performance import summarize


class EvidenceChecks(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.dir=Path(self.temp.name)
        self.before={k:k for k in ('world_id','actor_id','profile_id','dimension_id','inventory_nbt','equipment')}
        self.before.update(session_id='old',c2_journal={'goal_id':'g','state':'COMPLETED'},
            backend={**{k:k for k in ('task_id','attempt_id','goal_id','actor_id','world_id','session_id')},'state':'COMPLETED'},
            outbox=[{'file':'original.json','sha256':'unchanged'}])
        self.after=copy.deepcopy(self.before);self.after['session_id']='new'
        for key in ('c1_active','c2_active','follow_active','g2_pending','g3_pending','recovery_pending'):self.after[key]=False

    def compare(self):
        a=self.dir/'before.json';b=self.dir/'after.json';out=self.dir/'result.json'
        a.write_text(json.dumps(self.before));b.write_text(json.dumps(self.after))
        m03_final.compare(argparse.Namespace(before=a,after=b,output=out))
        return json.loads(out.read_text())

    def test_metadata_match_never_gameplay_pass(self):
        r=self.compare();self.assertEqual(r['status'],'AUTOMATED METADATA CHECK PASS');self.assertIn('PENDING',r['gameplay'])
    def test_missing_backend_hold(self):
        self.after.pop('backend');self.assertEqual(self.compare()['status'],'HOLD')
    def test_lost_outbox_fail(self):
        self.after['outbox']=[];self.assertEqual(self.compare()['status'],'FAIL')
    def test_terminal_contradiction_fail(self):
        self.after['backend']['state']='CANCELLED';self.assertEqual(self.compare()['status'],'FAIL')
    def test_same_session_no_rejoin_proof(self):
        self.after['session_id']='old';self.assertEqual(self.compare()['status'],'HOLD')
    def test_active_motion_hold(self):
        self.after['c2_active']=True;self.assertEqual(self.compare()['status'],'HOLD')
    def test_empty_performance_pending(self):
        self.assertEqual(summarize(self.dir)['status'],'PENDING / NOT TESTED')

    def test_collect_retains_f1_checkpoint_and_does_not_change_source(self):
        world=self.dir/'world';data=world/'data/npcortex';data.mkdir(parents=True)
        (world/'level.dat').write_bytes(b'fixture')
        cursor=data/'cplus_f1_delivery.cursor';cursor.write_bytes(b'original-cursor')
        db=self.dir/'fixture.sqlite3'
        with closing(sqlite3.connect(db)) as c:
            c.execute('create table cplus_f1_bridge_checkpoint (world_id text, sequence integer)')
            c.execute("insert into cplus_f1_bridge_checkpoint values ('fixture',7)");c.commit()
        before=db.read_bytes()
        out=self.dir/'collected'
        m03_final.collect(argparse.Namespace(world=world,database=db,output=out))
        self.assertEqual(db.read_bytes(),before);self.assertEqual(cursor.read_bytes(),b'original-cursor')
        self.assertEqual((out/'world/data/npcortex/cplus_f1_delivery.cursor').read_bytes(),b'original-cursor')
        export=json.loads((out/'backend-readonly.json').read_text())
        self.assertEqual(export['cplus_f1_bridge_checkpoint'][0]['sequence'],7)

    def test_uninstall_exact_hooks_preserves_data(self):
        root=m03_final.ROOT
        paths=['scripts/m03_uninstall.py','forge-mod/build.gradle',
            'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusM03SelfTest.java']
        paths += ['forge-mod/src/main/java/io/github/sirp00m/npcortex/'+name+'.java' for name in [
            'NPCortex','CPlusF1DiagnosticRuntime','CPlusOutbox','CPlusM03Runtime','CPlusM03Fault']]
        for rel in paths:
            dest=self.dir/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(root/rel,dest)
        data=self.dir/'backend/data/world.sqlite3';data.parent.mkdir(parents=True);data.write_bytes(b'preserve-me')
        result=subprocess.run([sys.executable,str(self.dir/'scripts/m03_uninstall.py')],capture_output=True,text=True)
        self.assertEqual(result.returncode,0,result.stdout+result.stderr)
        self.assertEqual(data.read_bytes(),b'preserve-me')
        self.assertNotIn('CPlusM03Fault.check',(self.dir/'forge-mod/src/main/java/io/github/sirp00m/npcortex/CPlusOutbox.java').read_text())
        self.assertTrue(list((self.dir/'docs/m03-final/uninstall-backup').rglob('CPlusM03Runtime.java')))


if __name__=='__main__':unittest.main()
