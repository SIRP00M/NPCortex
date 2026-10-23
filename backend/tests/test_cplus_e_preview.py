"""E-P0: real durable SQLite DAG, no action execution or world effects."""
import copy
import http.client
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import unittest
from unittest.mock import patch
from uuid import uuid4
from types import SimpleNamespace

from npcortex_backend.cplus_e_graph import ReadOnlyGraph, GraphConflict
from npcortex_backend.server import make_http_server
from test_cplus_f2_observation import sample
from sqlite_test_utils import sqlite_test_connection

SECRET = 'e-preview-local-test-secret-123456789012345'

class GraphTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.path=Path(self.temp.name)/'mixed.sqlite3'
        with sqlite_test_connection(self.path) as conn:
            conn.execute('CREATE TABLE existing (value TEXT)')
            conn.execute('INSERT INTO existing VALUES (\'must remain\')')
        self.graph=ReadOnlyGraph(self.path)
        self.obs=sample()
        self.rid=str(uuid4())
    def tearDown(self):
        self.temp.cleanup()

    def test_durable_idempotent_read_only_dag(self):
        submitted=self.graph.submit(self.rid,self.obs)
        self.assertEqual(submitted['state'],'QUEUED')
        self.assertEqual([x['state'] for x in submitted['steps']],['WAITING']*3)
        completed=self.graph.advance(submitted['task_id'])
        self.assertEqual(completed['state'],'READ_ONLY_VERIFIED')
        self.assertTrue(all(step['state']=='READ_VERIFIED' for step in completed['steps']))
        self.assertEqual(completed['permission'],'UNKNOWN')
        self.assertFalse(completed['world_effects'])
        self.assertFalse(completed['can_replay_world_actions'])
        restarted=ReadOnlyGraph(self.path)
        self.assertEqual(restarted.submit(self.rid,self.obs)['task_id'],submitted['task_id'])
        self.assertEqual(restarted.advance(submitted['task_id']),completed)
        with sqlite_test_connection(self.path) as con:
            self.assertEqual(con.execute('SELECT value FROM existing').fetchone()[0],'must remain')

    def test_fifo_waits_for_first_task(self):
        a=self.graph.submit(str(uuid4()),self.obs)
        b=self.graph.submit(str(uuid4()),self.obs)
        self.assertEqual((a['sequence'],b['sequence']),(1,2))
        self.assertEqual(self.graph.advance(b['task_id'])['state'],'QUEUED')
        self.assertEqual(self.graph.advance(a['task_id'])['state'],'READ_ONLY_VERIFIED')
        self.assertEqual(self.graph.advance(b['task_id'])['state'],'READ_ONLY_VERIFIED')

    def test_conflicting_retry_rejected(self):
        self.graph.submit(self.rid,self.obs)
        changed=copy.deepcopy(self.obs)
        changed['observation_id']=str(uuid4())
        changed['permission']['evidence_id']=changed['observation_id']
        with self.assertRaisesRegex(GraphConflict,'conflicting request_id'):
            self.graph.submit(self.rid,changed)
        with self.assertRaises(GraphConflict):
            self.graph.status(str(uuid4()))

    def test_invalid_untrusted_forged_allow_rejected(self):
        changed=copy.deepcopy(self.obs)
        changed['permission']['decision']='ALLOW'
        with self.assertRaisesRegex(GraphConflict,'invalid C-F2'):
            self.graph.submit(self.rid,changed)
        self.assertEqual(self.graph.submit(str(uuid4()),self.obs)['permission'],'UNKNOWN')

    def test_queue_bounded_and_multi_actor_isolation(self):
        for _ in range(16): self.graph.submit(str(uuid4()),self.obs)
        with self.assertRaisesRegex(GraphConflict,'queue full'):
            self.graph.submit(str(uuid4()),self.obs)
        different=copy.deepcopy(self.obs)
        different['actor_id']=str(uuid4())
        self.assertEqual(self.graph.submit(str(uuid4()),different)['sequence'],1)

    def test_restart_queued_tasks_do_not_autorun(self):
        initial=self.graph.submit(self.rid,self.obs)
        new=ReadOnlyGraph(self.path)
        self.assertEqual(new.status(initial['task_id'])['state'],'QUEUED')
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_e_steps WHERE state=\'READ_VERIFIED\'').fetchone()[0],0)

    def test_corrupt_persisted_snapshot_enters_hold(self):
        initial=self.graph.submit(self.rid,self.obs)
        with sqlite_test_connection(self.path) as db:
            db.execute('UPDATE cplus_e_tasks SET snapshot_json=\'{}\' WHERE task_id=?',(initial['task_id'],))
        self.assertEqual(self.graph.advance(initial['task_id'])['state'],'HOLD')
        self.assertEqual(self.graph.advance(initial['task_id'])['state'],'HOLD')

    def test_synthetic_actor_1_3_5_10_isolation(self):
        for count in (1,3,5,10):
            for i in range(count):
                obs=copy.deepcopy(self.obs)
                obs['actor_id']=str(uuid4())
                task=self.graph.submit(str(uuid4()),obs)
                self.assertEqual(task['sequence'],1)
                self.assertEqual(self.graph.advance(task['task_id'])['state'],'READ_ONLY_VERIFIED')

class PreviewHttpTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.path=Path(self.temp.name)/'backend.sqlite3'
        service=SimpleNamespace(_require_world_store=lambda: SimpleNamespace(db_path=self.path),model='mock')
        self.server=make_http_server(service,port=0)
        self.worker=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.worker.start()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.worker.join(timeout=5)
        self.temp.cleanup()
    def request(self,data,token=SECRET,path='/v1/cplus/e/preview'):
        con=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=4)
        payload=json.dumps(data).encode()
        con.request('POST',path,body=payload,headers={'X-NPCortex-CPlus-Token':token,'Content-Type':'application/json'})
        res=con.getresponse(); body=json.loads(res.read()); status=res.status
        con.close();return status,body
    def test_operator_authenticated_end_to_end(self):
        data={'request_id':str(uuid4()),'snapshot':sample()}
        with patch('npcortex_backend.server.read_secret',return_value=SECRET):
            bad=self.request(data,token='wrong')
            self.assertEqual(bad[0],403)
            ok=self.request(data)
            self.assertEqual(ok[0],200)
            self.assertEqual(ok[1]['state'],'READ_ONLY_VERIFIED')
            self.assertFalse(ok[1]['world_effects'])
            again=self.request(data)
            self.assertEqual(again[1]['task_id'],ok[1]['task_id'])
            status=self.request({'task_id':ok[1]['task_id']},path='/v1/cplus/e/status')
            self.assertEqual(status[1]['state'],'READ_ONLY_VERIFIED')
    def test_rejects_forged_mutation_authorization(self):
        obs=sample();obs['permission']['decision']='ALLOW'
        with patch('npcortex_backend.server.read_secret',return_value=SECRET):
            code,_=self.request({'request_id':str(uuid4()),'snapshot':obs})
            self.assertEqual(code,409)

if __name__=='__main__': unittest.main()
