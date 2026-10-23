"""Gate 1: read-only E1 graph and FIFO durability, no Minecraft effects."""
import copy
import sqlite3
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4
from concurrent.futures import ThreadPoolExecutor

from npcortex_backend.cplus_e_coordinator import ExtendedCoordinator, GraphConflict
from test_cplus_f2_observation import sample
from sqlite_test_utils import sqlite_test_connection


class Gate1Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.path=Path(self.tmp.name)/'world.db'
        self.coord=ExtendedCoordinator(self.path)
        self.actor=sample()
        self.a=str(uuid4());self.b=str(uuid4())
    def tearDown(self):self.tmp.cleanup()
    def submit(self,requester=None,request=None,obs=None,plan=None):
        return self.coord.submit_task(request or str(uuid4()),requester or self.a,
                                      obs or self.actor,plan)

    def test_durable_fifo_and_idempotency(self):
        rid=str(uuid4())
        first=self.submit(self.a,rid)
        second=self.submit(self.b)
        self.assertEqual([first['sequence'],second['sequence']],[1,2])
        self.assertEqual(first['requester_id'],self.a)
        self.assertEqual(second['requester_id'],self.b)
        self.assertEqual(first['state'],'QUEUED')
        self.assertEqual(len(first['nodes']),3)
        again=ExtendedCoordinator(self.path)
        self.assertEqual(again.submit_task(rid,self.a,self.actor)['task_id'],first['task_id'])
        self.assertEqual(again.task_queue(first['world_id'],first['actor_id'])['tasks'][1]['task_id'],second['task_id'])
        self.assertFalse(first['world_effects'])
        with self.assertRaisesRegex(GraphConflict,'conflicting'):
            again.submit_task(rid,self.b,self.actor)

    def test_invalid_graphs_rejected(self):
        examples=[[],[{'node_id':'bad','kind':'break_block','depends_on':[]}],
            [{'node_id':'a','kind':'observe_cells','depends_on':['b']}],
            [{'node_id':'a','kind':'observe_cells','depends_on':['a']}],
            [{'node_id':'a','kind':'observe_cells','depends_on':[]},
             {'node_id':'a','kind':'report_coverage','depends_on':['a']}]]
        for plan in examples:
            with self.subTest(plan=plan),self.assertRaises(GraphConflict):self.submit(plan=plan)

    def test_reject_false_allow_and_foreign_identity(self):
        bad=copy.deepcopy(self.actor);bad['permission']['decision']='ALLOW'
        with self.assertRaises(GraphConflict):self.submit(obs=bad)
        with self.assertRaises(GraphConflict):self.coord.task_queue('bad-uuid',self.actor['actor_id'])

    def test_cancel_only_requester_and_persist(self):
        task=self.submit()
        with self.assertRaisesRegex(GraphConflict,'requester mismatch'):
            self.coord.cancel_task(task['task_id'],self.b)
        self.assertEqual(self.coord.cancel_task(task['task_id'],self.a)['state'],'CANCELLED')
        self.assertEqual(ExtendedCoordinator(self.path).task_status(task['task_id'])['state'],'CANCELLED')
        self.assertEqual(self.submit()['sequence'],2)

    def test_bounded_and_concurrent_fifo(self):
        with ThreadPoolExecutor(max_workers=5) as pool:
            futures=[pool.submit(self.submit,self.a) for _ in range(16)]
            tasks=[f.result() for f in futures]
        self.assertEqual(sorted(t['sequence'] for t in tasks),list(range(1,17)))
        with self.assertRaisesRegex(GraphConflict,'queue full'):self.submit()
        other=copy.deepcopy(self.actor);other['actor_id']=str(uuid4())
        self.assertEqual(self.submit(obs=other)['sequence'],1)

    def test_corrupted_projection_fails_closed_without_replay(self):
        item=self.submit()
        with sqlite_test_connection(self.path) as db:
            db.execute('UPDATE cplus_e1_tasks SET graph_json=? WHERE task_id=?',
                       ('[]',item['task_id']))
        reloaded=ExtendedCoordinator(self.path).task_status(item['task_id'])
        self.assertEqual(reloaded['state'],'HOLD')
        self.assertEqual(reloaded['nodes'],[])
        self.assertFalse(reloaded['can_replay_world_actions'])

    def test_partial_projection_corruption_fails_closed(self):
        item=self.submit()
        with sqlite_test_connection(self.path) as db:
            db.execute('DELETE FROM cplus_e1_nodes WHERE task_id=? AND node_id=?',
                       (item['task_id'],'coverage'))
        self.assertEqual(ExtendedCoordinator(self.path).task_status(item['task_id'])['state'],'HOLD')

    def test_legacy_data_untouched(self):
        from npcortex_backend.cplus_e_graph import ReadOnlyGraph
        legacy=ReadOnlyGraph(self.path)
        old=legacy.submit(str(uuid4()),self.actor)
        self.assertEqual(ExtendedCoordinator(self.path).submit_task(str(uuid4()),self.a,self.actor)['sequence'],1)
        self.assertEqual(legacy.advance(old['task_id'])['state'],'READ_ONLY_VERIFIED')

    def test_synthetic_actor_isolation_1_3_5_10(self):
        for size in [1,3,5,10]:
            for _ in range(size):
                obs=copy.deepcopy(self.actor);obs['actor_id']=str(uuid4())
                self.assertEqual(self.submit(obs=obs)['sequence'],1)

if __name__=='__main__':unittest.main()
