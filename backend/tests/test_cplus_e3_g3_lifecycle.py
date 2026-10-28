"""G3 big sprint: exact durable evidence / cancel fencing. Synthetic Forge witness,
not proof of actual Minecraft gameplay or C1/C2 runtime integration."""
import copy
from contextlib import closing
import tempfile
import unittest
import sqlite3
from pathlib import Path
from uuid import uuid4

from npcortex_backend.cplus_e3_g2_motion import GuardedMotionStore
from npcortex_backend.cplus_e_graph import GraphConflict
from test_cplus_e3_g2_motion import body, ack
from test_cplus_e3_g3_terminal import fixture


class G3Lifecycle(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.db=Path(self.tmp.name)/'world.db'
        self.store=GuardedMotionStore(self.db)
        self.b=body()
        self.accepted=self.store.reserve(self.b)
        self.goal=str(uuid4())
        self.accepted=self.store.report(ack(self.b,goal_id=self.goal))
    def tearDown(self):self.tmp.cleanup()
    def candidate(self):
        c,_=fixture()
        for key in ('task_id','request_id','attempt_id','requester_id','world_id',
                    'dimension_id','session_id','actor_id','observation_id'):
            c[key]=self.accepted[key]
        c['goal_id']=self.goal
        c['action_id']=str(uuid4())
        c['target']=self.accepted['target']
        c['tolerance']=self.accepted['tolerance']
        c['c1']['position']={'x':c['target']['x']+0.5,'y':float(c['target']['y']),'z':c['target']['z']+0.5}  # block centre, where C2 stops
        c['c2']['goal_id']=c['c0']['goal_id']=self.goal
        c['c2']['action_id']=c['c0']['action_id']=c['action_id']
        return c
    def identity(self):
        return {k:self.accepted[k] for k in ('task_id','attempt_id','requester_id',
                                            'world_id','actor_id','session_id','goal_id')}
    def test_verified_completion_durable_and_actor_can_start_new(self):
        c=self.candidate()
        out=self.store.finalize(c)
        self.assertEqual(out['state'],'COMPLETED')
        self.assertTrue(out['durable_ack'])
        self.assertTrue(out['release_actor_fence'])
        self.assertTrue(out['completion_verified'])
        self.assertEqual(self.store.finalize(c),out)
        second=self.store.reserve(body(self.b['snapshot']))
        self.assertEqual(second['state'],'RESERVED')
        self.assertEqual(self.store.status(self.b['world_id'],self.b['actor_id'])['state'],'RESERVED')
        self.assertEqual(GuardedMotionStore(self.db).status(self.b['world_id'],self.b['actor_id'])['state'],'HOLD')
        # sqlite3.Connection.__exit__ commits but does not close the handle.
        # Explicit close is necessary before TemporaryDirectory cleanup on Windows.
        with closing(sqlite3.connect(self.db)) as conn, conn as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_e3_g3_terminals').fetchone()[0],1)
            self.assertEqual(db.execute("SELECT state FROM cplus_e3_g2_moves WHERE task_id=?",
                                        (self.b['task_id'],)).fetchone()[0],'COMPLETED')
        # Windows refuses a rename while a SQLite handle is still open.
        # This also proves that the test fixture has closed its own reader.
        probe=self.db.with_suffix('.probe')
        self.db.rename(probe)
        probe.rename(self.db)
    def test_wrong_evidence_never_releases_fence(self):
        c=self.candidate()
        for path,value in [(['action_id'],str(uuid4())),(['goal_id'],str(uuid4())),
                           (['world_id'],str(uuid4())),(['session_id'],str(uuid4())),
                           (['c1','position','x'],999),(['c0','verified'],False),
                           (['c2','local_verified'],False),(['world_effects'],True),
                           (['requester_id'],str(uuid4()))]:
            with self.subTest(path=path):
                bad=copy.deepcopy(c)
                leaf=bad
                for k in path[:-1]:leaf=leaf[k]
                leaf[path[-1]]=value
                with self.assertRaises(GraphConflict):self.store.finalize(bad)
                self.assertEqual(self.store.status(self.b['world_id'],self.b['actor_id'])['state'],'C2_ACCEPTED')
    def test_conflicting_duplicate_terminal_is_rejected(self):
        c=self.candidate()
        self.store.finalize(c)
        forged=copy.deepcopy(c);forged['c1']['position']['z']+=0.1
        with self.assertRaises(GraphConflict):self.store.finalize(forged)
    def test_restart_hold_never_reinterpreted_as_complete(self):
        held=GuardedMotionStore(self.db)
        self.assertEqual(held.status(self.b['world_id'],self.b['actor_id'])['state'],'HOLD')
        with self.assertRaises(GraphConflict):held.finalize(self.candidate())
        with self.assertRaises(GraphConflict):held.reserve(body(self.b['snapshot']))
    def test_cancel_begin_blocks_repeated_movement_then_terminal_ack(self):
        identity=self.identity()
        state=self.store.cancel_begin(identity)
        self.assertEqual(state['state'],'CANCEL_PENDING')
        self.assertFalse(state['release_actor_fence'])
        self.assertEqual(self.store.cancel_begin(identity),state)
        with self.assertRaises(GraphConflict):self.store.reserve(body(self.b['snapshot']))
        ack_body={**identity,'action_id':str(uuid4()),'c2_state':'CANCELLED','c1_stopped':True}
        out=self.store.cancel_ack(ack_body)
        self.assertEqual(out['state'],'CANCELLED')
        self.assertTrue(out['release_actor_fence'])
        self.assertFalse(out['completion_verified'])
        self.assertEqual(self.store.cancel_ack(ack_body),out)
        self.assertEqual(self.store.reserve(body(self.b['snapshot']))['state'],'RESERVED')
    def test_cancel_wrong_goal_requester_and_unstopped_rejected(self):
        identity=self.identity()
        for k in ('goal_id','actor_id','requester_id','session_id'):
            bad=copy.deepcopy(identity);bad[k]=str(uuid4())
            with self.assertRaises(GraphConflict):self.store.cancel_begin(bad)
        self.store.cancel_begin(identity)
        for changed in [{'c1_stopped':False},{'c2_state':'CANCEL_PENDING'},
                        {'goal_id':str(uuid4())},{'requester_id':str(uuid4())}]:
            bad={**identity,'action_id':None,'c2_state':'CANCELLED','c1_stopped':True,**changed}
            with self.assertRaises(GraphConflict):self.store.cancel_ack(bad)
        self.assertEqual(self.store.status(self.b['world_id'],self.b['actor_id'])['state'],'CANCEL_PENDING')
    def test_cancel_pending_restart_becomes_hold_no_auto_ack(self):
        identity=self.identity()
        self.store.cancel_begin(identity)
        restarted=GuardedMotionStore(self.db)
        self.assertEqual(restarted.status(self.b['world_id'],self.b['actor_id'])['state'],'HOLD')
        with self.assertRaises(GraphConflict):restarted.cancel_ack({**identity,
            'action_id':None,'c2_state':'CANCELLED','c1_stopped':True})
    def test_different_actor_not_blocked(self):
        snap=copy.deepcopy(self.b['snapshot'])
        snap['actor_id']=str(uuid4())
        snap['observation_id']=str(uuid4())
        snap['permission']['evidence_id']=snap['observation_id']
        other=body(snap)
        self.assertEqual(self.store.reserve(other)['state'],'RESERVED')
    def test_original_g2_schema_migration_preserves_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'old.db'
            # Equivalent to G2 baseline schema: ensure old CHECK & unique-index
            # survive upgrade as original data, NOT reset/removed.
            old=self.b.copy()
            # Close file handles before Windows removes the temporary database.
            with closing(sqlite3.connect(path)) as conn, conn as db:
                db.execute('''CREATE TABLE cplus_e3_g2_moves (
                    task_id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL,
                    attempt_id TEXT UNIQUE NOT NULL, requester_id TEXT NOT NULL,
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL,
                    session_id TEXT NOT NULL, actor_id TEXT NOT NULL,
                    observation_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
                    target_json TEXT NOT NULL, tolerance REAL NOT NULL,
                    goal_id TEXT NOT NULL DEFAULT '',
                    state TEXT NOT NULL CHECK(state IN ('RESERVED','C2_ACCEPTED','HOLD')),
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)''')
                db.execute('''INSERT INTO cplus_e3_g2_moves VALUES
                    (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)''',
                    (old['task_id'],old['request_id'],old['attempt_id'],old['requester_id'],
                     old['world_id'],old['dimension_id'],old['session_id'],old['actor_id'],
                     old['snapshot']['observation_id'],'old-hash','{"x":1,"y":2,"z":3}',.75,
                     self.goal,'C2_ACCEPTED',1,1))
            restored=GuardedMotionStore(path)
            self.assertEqual(restored.status(old['world_id'],old['actor_id'])['state'],'HOLD')
            # Close file handles before Windows removes the temporary database.
            with closing(sqlite3.connect(path)) as conn, conn as db:
                self.assertEqual(db.execute('SELECT count(*) FROM cplus_e3_g2_moves').fetchone()[0],1)
                ddl=db.execute("SELECT sql FROM sqlite_master WHERE name='cplus_e3_g2_moves'").fetchone()[0]
                self.assertIn('CANCEL_PENDING',ddl)
                self.assertEqual(db.execute("SELECT count(*) FROM sqlite_master WHERE name='cplus_e3_g2_active_actor'").fetchone()[0],1)
            probe=path.with_suffix('.probe')
            path.rename(probe)
            probe.rename(path)

if __name__=='__main__':unittest.main()
