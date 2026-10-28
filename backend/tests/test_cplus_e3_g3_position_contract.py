"""E3/G3 arrival-position contract: shared cross-language vectors + legacy fail-closed policy.

The same docs/contracts/e3_g3_position_vectors_v1.json is replayed by the Java self-test
(CPlusE3G3TerminalContractSelfTest). Synthetic evidence only, not Minecraft gameplay."""
import copy
import json
import math
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

from npcortex_backend.cplus_e3_g2_motion import GuardedMotionStore
from npcortex_backend.cplus_e3_g3_terminal import arrival_point, validate_terminal_candidate
from npcortex_backend.cplus_e_graph import GraphConflict
from test_cplus_e3_g2_motion import body, ack
from test_cplus_e3_g3_terminal import fixture

VECTORS=json.loads((Path(__file__).resolve().parents[2]/'docs/contracts/e3_g3_position_vectors_v1.json')
                   .read_text('utf-8'))


def candidate_at(target, position):
    c,a=fixture()
    c['target']=dict(zip('xyz',target)); a['target']=dict(zip('xyz',target))
    c['c1']['position']=dict(zip('xyz',position))
    return c,a


class PositionContract(unittest.TestCase):
    def test_centre_helper_is_horizontal_only(self):
        self.assertEqual(arrival_point([7,-60,2]),[7.5,-60,2.5])
        self.assertEqual(arrival_point([-3,-60,-8]),[-2.5,-60,-7.5])   # x+0.5, never floor-based
        self.assertEqual(arrival_point([0,0,0]),[0.5,0,0.5])

    def test_shared_vectors(self):
        self.assertGreaterEqual(len(VECTORS['vectors']),30)
        self.assertEqual(VECTORS['tolerance'],0.75)
        for v in VECTORS['vectors']:
            with self.subTest(v['name']):
                c,a=candidate_at(v['target'],v['position'])
                if v['expect']=='PASS':
                    self.assertEqual(validate_terminal_candidate(c,a)['state'],'DRY_RUN_VALIDATED')
                else:
                    with self.assertRaises(GraphConflict) as ctx: validate_terminal_candidate(c,a)
                    self.assertIn('outside tolerance',str(ctx.exception))

    def test_vectors_match_an_independent_distance_calculation(self):
        # The vector file must not merely mirror the implementation: recompute from the rule text.
        for v in VECTORS['vectors']:
            with self.subTest(v['name']):
                tx,ty,tz=v['target']; px,py,pz=v['position']
                d2=(px-(tx+0.5))**2+(py-ty)**2+(pz-(tz+0.5))**2
                self.assertEqual('REJECT' if d2>0.75*0.75 else 'PASS',v['expect'],math.sqrt(d2))

    def test_tolerance_is_not_widened(self):
        c,a=candidate_at([7,-60,2],[7.5,-60,2.5])
        for bad in (0.7500001,0.8,1.0):
            c2,a2=copy.deepcopy(c),copy.deepcopy(a)
            c2['tolerance']=a2['tolerance']=bad
            with self.assertRaises(GraphConflict): validate_terminal_candidate(c2,a2)

    def test_identity_mismatch_still_rejected_even_at_the_centre(self):
        c,a=candidate_at([7,-60,2],[7.5,-60,2.5])
        for key in ('task_id','actor_id','world_id','goal_id'):
            bad=copy.deepcopy(c); bad[key]=str(uuid4())
            with self.assertRaises(GraphConflict): validate_terminal_candidate(bad,a)


class LegacyFailClosed(unittest.TestCase):
    """Pre-fix attempts keep a HOLD/COMPLETED outcome; the new rule can never re-judge them.
    No schema change: enforcement is the existing startup HOLD sweep + the C2_ACCEPTED/CANCEL_PENDING
    eligibility gate in finalize()."""
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.db=Path(self.tmp.name)/'world.db'
        store=GuardedMotionStore(self.db)
        self.b=body()
        self.accepted=store.reserve(self.b); self.goal=str(uuid4()); store.report(ack(self.b,goal_id=self.goal))
        self.store=store
    def tearDown(self): self.tmp.cleanup()

    def rev6_candidate(self):
        c,_=fixture()
        for k in ('task_id','request_id','attempt_id','requester_id','world_id','dimension_id',
                  'session_id','actor_id','observation_id'): c[k]=self.accepted[k]
        c['goal_id']=self.goal; c['action_id']=str(uuid4())
        t=self.accepted['target']; c['target']=dict(t); c['tolerance']=0.75
        # Rev6 p18 offsets from the integer corner: dx=0.560866438393732, dz=0.4999386555278003
        c['c1']['position']={'x':t['x']+0.560866438393732,'y':t['y'],'z':t['z']+0.4999386555278003}
        c['c2']['goal_id']=c['c0']['goal_id']=self.goal
        c['c2']['action_id']=c['c0']['action_id']=c['action_id']
        return c

    def test_new_attempt_with_the_rev6_position_completes(self):
        r=self.store.finalize(self.rev6_candidate())
        self.assertEqual(r['state'],'COMPLETED'); self.assertTrue(r['durable_ack'])

    def test_pre_fix_attempt_after_restart_is_hold_and_cannot_be_finalized(self):
        held=GuardedMotionStore(self.db)   # backend restart == the only way the new verifier is loaded
        self.assertEqual(held.status(self.b['world_id'],self.b['actor_id'])['state'],'HOLD')
        with self.assertRaises(GraphConflict) as ctx: held.finalize(self.rev6_candidate())
        self.assertIn('old HOLD must not be finalized',str(ctx.exception))
        self.assertEqual(held.status(self.b['world_id'],self.b['actor_id'])['state'],'HOLD')
        import sqlite3
        from contextlib import closing
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_e3_g3_terminals').fetchone()[0],0)

    def test_hold_can_never_be_reported_back_to_accepted(self):
        held=GuardedMotionStore(self.db)
        with self.assertRaises(GraphConflict):
            held.report(ack(self.b,goal_id=self.goal))

    def test_completed_row_is_not_rejudged(self):
        c=self.rev6_candidate(); first=self.store.finalize(c)
        again=GuardedMotionStore(self.db)
        self.assertEqual(again.finalize(c),first)           # identical replay: stored outcome, no re-verification
        forged=copy.deepcopy(c); forged['c1']['position']['x']=7.5
        with self.assertRaises(GraphConflict): again.finalize(forged)   # different evidence for a terminal row


if __name__=='__main__':
    unittest.main()
