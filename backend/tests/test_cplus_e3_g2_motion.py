"""E3-D G2 durable one-target admission and restart fail-closed tests."""
import copy
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4
from npcortex_backend.cplus_e3_g2_motion import GuardedMotionStore
from npcortex_backend.cplus_e_coordinator import ExtendedCoordinator
from npcortex_backend.cplus_e_graph import GraphConflict
from test_cplus_f2_observation import sample


def body(snapshot=None):
    snap=snapshot or sample()
    return {'schema_version':1,'request_id':str(uuid4()),'task_id':str(uuid4()),
            'attempt_id':str(uuid4()),'requester_id':str(uuid4()),
            'world_id':snap['world_id'],'dimension_id':snap['dimension_id'],
            'session_id':snap['session_id'],'actor_id':snap['actor_id'],
            'node_id':'move','action_kind':'move_to',
            'target':dict(zip(('x','y','z'),snap['center'])),
            'tolerance':0.75,'snapshot':snap}


def ack(b, state='C2_ACCEPTED', goal_id=None):
    a={key:b[key] for key in ('task_id','attempt_id','world_id','actor_id','session_id')}
    a.update(state=state,goal_id=(goal_id or str(uuid4())) if state=='C2_ACCEPTED' else None)
    return a


class G2Motion(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.db=Path(self.temp.name)/'world.db'
        self.store=GuardedMotionStore(self.db)
        self.b=body()
    def tearDown(self): self.temp.cleanup()
    def test_reserve_and_matching_C2_ack_never_completion(self):
        r=self.store.reserve(self.b)
        self.assertEqual(r['state'],'RESERVED')
        self.assertTrue(r['can_execute'])
        self.assertFalse(r['completion_verified'])
        a=ack(self.b)
        result=self.store.report(a)
        self.assertEqual(result['state'],'C2_ACCEPTED')
        self.assertTrue(result['c2_goal_accepted'])
        self.assertFalse(result['motion_started'])
        self.assertFalse(result['completion_verified'])
        self.assertEqual(self.store.report(a),result)
    def test_duplicate_idempotent_but_modified_rejected(self):
        r=self.store.reserve(self.b)
        self.assertEqual(self.store.reserve(self.b),r)
        changed=copy.deepcopy(self.b);changed['target']['x']+=1
        with self.assertRaises(GraphConflict):self.store.reserve(changed)
    def test_one_active_actor_and_no_skip(self):
        self.store.reserve(self.b)
        with self.assertRaises(GraphConflict):self.store.reserve(body(self.b['snapshot']))
    def test_e1_queue_blocks_g2_movement(self):
        ExtendedCoordinator(self.db).submit_task(str(uuid4()),str(uuid4()),self.b['snapshot'])
        with self.assertRaises(GraphConflict):self.store.reserve(self.b)
    def test_session_restart_holds_not_replayed(self):
        self.store.reserve(self.b)
        restarted=GuardedMotionStore(self.db)
        self.assertEqual(restarted.status(self.b['world_id'],self.b['actor_id'])['state'],'HOLD')
        with self.assertRaises(GraphConflict):restarted.reserve(body(self.b['snapshot']))
    def test_ack_wrong_id_fails_and_reservation_stays(self):
        self.store.reserve(self.b)
        altered=ack(self.b);altered['actor_id']=str(uuid4())
        with self.assertRaises(GraphConflict):self.store.report(altered)
        self.assertEqual(self.store.status(self.b['world_id'],self.b['actor_id'])['state'],'RESERVED')
    def test_no_fake_completion(self):
        self.store.reserve(self.b)
        wrong=ack(self.b);wrong['state']='COMPLETED'
        with self.assertRaises(GraphConflict):self.store.report(wrong)
    def test_hold_on_C2_reject(self):
        self.store.reserve(self.b)
        held=self.store.report(ack(self.b,'HOLD'))
        self.assertEqual(held['state'],'HOLD')
        self.assertFalse(held['motion_started'])
        self.assertEqual(held['goal_id'],None)
    def test_wrong_world_or_action_rejected(self):
        for key,value in [('world_id',str(uuid4())),('action_kind','break_block')]:
            changed=copy.deepcopy(self.b);changed[key]=value
            with self.assertRaises(GraphConflict):self.store.reserve(changed)
    def test_many_actor_isolation_synthetic(self):
        # Synthetic only; not real game performance validation.
        for size in (1,3,5,10):
            with self.subTest(size=size):
                with tempfile.TemporaryDirectory() as tmp:
                    store=GuardedMotionStore(Path(tmp)/'actors.db')
                    submitted=[]
                    for _ in range(size):
                        snap=copy.deepcopy(self.b['snapshot'])
                        snap['actor_id']=str(uuid4())
                        snap['observation_id']=str(uuid4())
                        snap['permission']['evidence_id']=snap['observation_id']
                        b=body(snap)
                        row=store.reserve(b)
                        self.assertEqual(row['actor_id'],snap['actor_id'])
                        submitted.append(row['actor_id'])
                    self.assertEqual(len(set(submitted)),size)
                    for actor in submitted:
                        self.assertEqual(store.status(self.b['world_id'],actor)['state'],'RESERVED')
    def test_e2_dispatching_blocks_motion(self):
        from npcortex_backend.cplus_e2_dispatcher import ReadOnlyDispatcher
        ReadOnlyDispatcher(self.db)
        task=ExtendedCoordinator(self.db).submit_task(str(uuid4()),str(uuid4()),self.b['snapshot'])
        with self.store.connection() as db:
            db.execute("UPDATE cplus_e1_tasks SET state='CANCELLED' WHERE task_id=?",(task['task_id'],))
            db.execute("""INSERT INTO cplus_e2_runs(task_id,lease_id,generation,
                resource_key,actor_id,session_id,center_json,start_tick,
                start_observation_id,attempt_id,state,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                (task['task_id'],str(uuid4()),1,'r',self.b['actor_id'],self.b['session_id'],
                 '[100,64,200]',1,str(uuid4()),str(uuid4()),'DISPATCHING',1))
        with self.assertRaises(GraphConflict): self.store.reserve(self.b)
