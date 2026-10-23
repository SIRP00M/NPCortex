"""E3-D G1: dry-run only, no hidden C2 dispatch."""
import copy
import sqlite3
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4
from npcortex_backend.cplus_e3_preview import MovePreviewStore, GraphConflict
from npcortex_backend.cplus_e2_dispatcher import ReadOnlyDispatcher
from test_cplus_f2_observation import sample

class E3G1PreviewTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db=Path(self.tmp.name)/'world.db'
        self.store=MovePreviewStore(self.db)
        self.obs=sample()
        self.payload={'schema_version':1, 'request_id':str(uuid4()),
            'task_id':str(uuid4()),'requester_id':str(uuid4()),
            'actor_id':self.obs['actor_id'],'world_id':self.obs['world_id'],
            'dimension_id':self.obs['dimension_id'],'session_id':self.obs['session_id'],
            'node_id':'move','attempt_id':str(uuid4()),'action_kind':'move_to',
            'target':dict(zip(('x','y','z'),self.obs['center'])), 'tolerance':0.75,
            'snapshot':self.obs}
    def test_preview_persists_without_admitting_or_executing(self):
        got=self.store.preview(self.payload)
        self.assertEqual(got['state'],'PREVIEW_ONLY')
        self.assertFalse(got['can_execute'])
        self.assertFalse(got['motion_started'])
        self.assertFalse(got['world_effects'])
        self.assertEqual(MovePreviewStore(self.db).preview(self.payload),got)
        self.assertEqual(ReadOnlyDispatcher(self.db).task_queue(self.obs['world_id'],self.obs['actor_id'])['tasks'],[])
        with self.store.connection() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_e3_g1_move_previews').fetchone()[0],1)
    def test_duplicate_request_id_modified_payload_rejected(self):
        self.store.preview(self.payload)
        updated=copy.deepcopy(self.payload)
        updated['target']['x']+=1
        with self.assertRaisesRegex(GraphConflict,'mismatched'):
            self.store.preview(updated)
    def test_duplicate_task_and_attempt_rejected(self):
        self.store.preview(self.payload)
        for key in ('task_id','attempt_id'):
            duplicate=copy.deepcopy(self.payload)
            duplicate['request_id']=str(uuid4());duplicate['task_id']=str(uuid4());duplicate['attempt_id']=str(uuid4())
            duplicate[key]=self.payload[key]
            with self.subTest(key=key),self.assertRaises(GraphConflict): self.store.preview(duplicate)
    def test_identity_and_stale_snapshot_rejected(self):
        for key in ('world_id','actor_id','dimension_id','session_id'):
            v=copy.deepcopy(self.payload);v[key]=str(uuid4()) if key!='dimension_id' else 'minecraft:the_nether'
            with self.subTest(key=key),self.assertRaises(GraphConflict):self.store.preview(v)
        v=copy.deepcopy(self.payload);v['snapshot']['permission']['decision']='ALLOW'
        with self.assertRaises(GraphConflict):self.store.preview(v)
    def test_coordinates_bounds_types_nan_and_mismatch(self):
        for value in (2**25,1.0,'1',True,None):
            v=copy.deepcopy(self.payload);v['target']['x']=value
            with self.subTest(value=value),self.assertRaises(GraphConflict):self.store.preview(v)
        v=copy.deepcopy(self.payload);v['target']['x']+=7
        with self.assertRaisesRegex(GraphConflict,'local'):self.store.preview(v)
        for tolerance in (float('nan'),0,4,True):
            v=copy.deepcopy(self.payload);v['tolerance']=tolerance
            with self.subTest(tolerance=tolerance),self.assertRaises(GraphConflict):self.store.preview(v)
    def test_graph_and_protocol_restrictions(self):
        for key,value in [('node_id','break'),('action_kind','break_block'),('schema_version',2)]:
            v=copy.deepcopy(self.payload);v[key]=value
            with self.subTest(key=key),self.assertRaises(GraphConflict):self.store.preview(v)
        v=copy.deepcopy(self.payload);v['authorization']='ALLOW'
        with self.assertRaises(GraphConflict):self.store.preview(v)
    def test_restart_no_replay_and_readonly_integrity(self):
        self.store.preview(self.payload)
        second=MovePreviewStore(self.db)
        self.assertEqual(second.preview(self.payload)['execution_mode'],'DRY_RUN')
        with second.connection() as db:
            self.assertEqual(db.execute('SELECT state FROM cplus_e3_g1_move_previews').fetchone()[0],'PREVIEW_ONLY')
