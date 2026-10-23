"""E1-E Gate 2: read-only two-phase dispatch, locks/fencing and restart."""
import copy
import json
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

from npcortex_backend.cplus_e2_dispatcher import ReadOnlyDispatcher, GraphConflict
from test_cplus_f2_observation import sample
from sqlite_test_utils import sqlite_test_connection


class E2Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.path=Path(self.tmp.name)/'e2.db'
        self.coord=ReadOnlyDispatcher(self.path)
        self.a=sample()
        self.b=copy.deepcopy(self.a);self.b['actor_id']=str(uuid4())
        self.who=str(uuid4())
    def tearDown(self): self.tmp.cleanup()
    def submit(self,obs=None):
        return self.coord.submit_task(str(uuid4()),self.who,obs or self.a)
    @staticmethod
    def fresh(obs,delta=2):
        v=copy.deepcopy(obs);v['observation_id']=str(uuid4());v['capture_tick']+=delta
        v['permission']['checked_tick']+=delta;v['permission']['evidence_id']=v['observation_id']
        return v

    def test_two_stage_readonly_verified_and_fifo(self):
        a=self.submit();b=self.submit()
        first=self.coord.dispatch(self.fresh(self.a))
        self.assertEqual(first['state'],'DISPATCHING')
        self.assertEqual(first['task_id'],a['task_id'])
        with self.assertRaisesRegex(GraphConflict,'FIFO head not ready'):
            self.coord.dispatch(self.fresh(self.a))
        confirmed=self.coord.verify(a['task_id'],first['lease_id'],first['generation'],self.fresh(self.a,6))
        self.assertEqual(confirmed['state'],'READ_ONLY_VERIFIED')
        self.assertEqual(confirmed['read_only_receipts'],3)
        self.assertEqual([n['state'] for n in confirmed['nodes']],['READ_VERIFIED']*3)
        self.assertFalse(confirmed['world_effects'])
        self.assertEqual(confirmed['nodes'][2]['kind'],'report_permission')
        second=self.coord.dispatch(self.fresh(self.a))
        self.assertEqual(second['task_id'],b['task_id'])
        with sqlite_test_connection(self.path) as db:
            receipt=json.loads(db.execute('SELECT result_json FROM cplus_e2_receipts WHERE task_id=? AND node_id=?',
                 (a['task_id'],'permission')).fetchone()[0])
            self.assertEqual(receipt['decision'],'UNKNOWN')
            self.assertEqual(db.execute("SELECT task_id FROM cplus_e2_leases WHERE resource_key=?",(self.coord._resource(self.a),)).fetchone()[0],b['task_id'])

    def test_block_shared_resource_and_fencing(self):
        a=self.submit(self.a);b=self.submit(self.b)
        first=self.coord.dispatch(self.fresh(self.a))
        with self.assertRaisesRegex(GraphConflict,'resource busy'):
            self.coord.dispatch(self.fresh(self.b))
        self.assertEqual(self.coord.cancel_task(a['task_id'],self.who)['state'],'CANCELLED')
        second=self.coord.dispatch(self.fresh(self.b))
        self.assertNotEqual(first['lease_id'],second['lease_id'])
        self.assertGreater(second['generation'],first['generation'])
        with sqlite_test_connection(self.path) as db:
            with self.assertRaisesRegex(GraphConflict,'stale'):
                self.coord._release(db,self.coord._resource(self.a),a['task_id'],first['lease_id'],first['generation'])
        self.assertEqual(self.coord.task_status(b['task_id'])['state'],'DISPATCHING')
        self.assertEqual(self.coord.cancel_task(b['task_id'],self.who)['cancel_ack_id'].__class__,str)
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_e2_cancel_acks').fetchone()[0],2)

    def test_restart_hold_and_never_auto_resume(self):
        task=self.submit();active=self.coord.dispatch(self.fresh(self.a))
        recovered=ReadOnlyDispatcher(self.path)
        self.assertEqual(recovered.task_status(task['task_id'])['state'],'HOLD')
        with self.assertRaises(GraphConflict):
            recovered.verify(task['task_id'],active['lease_id'],active['generation'],self.fresh(self.a,3))
        with self.assertRaisesRegex(GraphConflict,'FIFO head not ready'):
            recovered.dispatch(self.fresh(self.a))
        self.assertEqual(recovered.cancel_task(task['task_id'],self.who)['state'],'CANCELLED')
        self.assertEqual(recovered.dispatch(self.fresh(self.a))['state'],'EMPTY')

    def test_snapshot_corrupt_identity_coverage_session_and_replay(self):
        task=self.submit()
        forbidden=self.fresh(self.a);forbidden['permission']['decision']='ALLOW'
        with self.assertRaises(GraphConflict):self.coord.dispatch(forbidden)
        missing=self.fresh(self.a);missing['coverage']['unknown']=1
        with self.assertRaises(GraphConflict):self.coord.dispatch(missing)
        active=self.coord.dispatch(self.fresh(self.a))
        mismatch=self.fresh(self.a);mismatch['session_id']=str(uuid4())
        with self.assertRaisesRegex(GraphConflict,'identity'):
            self.coord.verify(task['task_id'],active['lease_id'],active['generation'],mismatch)
        with self.assertRaisesRegex(GraphConflict,'not a new'):
            same=self.fresh(self.a);same['observation_id']=active['dispatch_observation_id']
            same['permission']['evidence_id']=same['observation_id']
            self.coord.verify(task['task_id'],active['lease_id'],active['generation'],same)
        with self.assertRaises(GraphConflict):
            self.coord.verify(task['task_id'],str(uuid4()),active['generation'],self.fresh(self.a))
        good=self.coord.verify(task['task_id'],active['lease_id'],active['generation'],self.fresh(self.a,6))
        self.assertEqual(good['state'],'READ_ONLY_VERIFIED')
        with self.assertRaises(GraphConflict):
            self.coord.verify(task['task_id'],active['lease_id'],active['generation'],self.fresh(self.a,7))

    def test_expired_lease_holds_no_retry(self):
        task=self.submit();active=self.coord.dispatch(self.fresh(self.a))
        with sqlite_test_connection(self.path) as db:
            db.execute('UPDATE cplus_e2_leases SET expires_at=1')
        expired=self.coord.verify(task['task_id'],active['lease_id'],active['generation'],self.fresh(self.a,5))
        self.assertEqual(expired['state'],'HOLD')
        self.assertFalse(expired['can_replay_world_actions'])

    def test_concurrent_dispatch_per_actor_no_double_lease(self):
        self.submit()
        with ThreadPoolExecutor(max_workers=5) as pool:
            fs=[pool.submit(self.coord.dispatch,self.fresh(self.a)) for _ in range(5)]
            results=[]
            for f in fs:
                try:results.append(f.result())
                except GraphConflict:pass
        self.assertEqual(len(results),1)
        self.assertEqual(results[0]['state'],'DISPATCHING')

    def test_canceled_queued_does_not_block_head(self):
        a=self.submit();b=self.submit()
        self.coord.cancel_task(a['task_id'],self.who)
        self.assertEqual(self.coord.dispatch(self.fresh(self.a))['task_id'],b['task_id'])

    def test_corrupt_verified_receipt_fails_closed(self):
        task=self.submit();started=self.coord.dispatch(self.fresh(self.a))
        done=self.coord.verify(task['task_id'],started['lease_id'],started['generation'],self.fresh(self.a,4))
        self.assertEqual(done['state'],'READ_ONLY_VERIFIED')
        with sqlite_test_connection(self.path) as db:
            db.execute('DELETE FROM cplus_e2_receipts WHERE task_id=? AND node_id=?',
                       (task['task_id'],'coverage'))
        self.assertEqual(self.coord.task_status(task['task_id'])['state'],'HOLD')

    def test_expired_hold_then_cancel_releases_owned_lease(self):
        task=self.submit();started=self.coord.dispatch(self.fresh(self.a))
        with sqlite_test_connection(self.path) as db:
            db.execute('UPDATE cplus_e2_leases SET expires_at=1')
        self.coord.verify(task['task_id'],started['lease_id'],started['generation'],self.fresh(self.a,6))
        self.assertEqual(self.coord.cancel_task(task['task_id'],self.who)['state'],'CANCELLED')
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute('SELECT task_id FROM cplus_e2_leases').fetchone()[0],'')

    def test_synthetic_multi_actor_1_3_5_10_sequential_read_isolation(self):
        for count in (1,3,5,10):
            actors=[]
            for _ in range(count):
                actor=copy.deepcopy(self.a)
                actor['actor_id']=str(uuid4())
                actors.append((actor,self.submit(actor)))
            for actor,task in actors:
                reserved=self.coord.dispatch(self.fresh(actor))
                self.assertEqual(reserved['task_id'],task['task_id'])
                self.assertEqual(self.coord.verify(task['task_id'],reserved['lease_id'],
                    reserved['generation'],self.fresh(actor,10))['state'],'READ_ONLY_VERIFIED')
        # Synthetic isolation only. Not a real Minecraft MSPT benchmark.

    def test_corrupt_task_blocks_fifo_without_leasing(self):
        a=self.submit();self.submit()
        with sqlite_test_connection(self.path) as db:
            db.execute('UPDATE cplus_e1_tasks SET graph_json=? WHERE task_id=?',('[]',a['task_id']))
        with self.assertRaisesRegex(GraphConflict,'head not ready'):
            self.coord.dispatch(self.fresh(self.a))
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_e2_leases').fetchone()[0],0)

if __name__=='__main__':unittest.main()
