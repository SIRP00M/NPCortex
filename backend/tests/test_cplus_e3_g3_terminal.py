"""G3-1 schema/identity negative cases only; no live movement or release."""
import copy
import json
from pathlib import Path
from uuid import uuid4
import unittest
from npcortex_backend.cplus_e3_g3_terminal import validate_terminal_candidate, FIELDS
from npcortex_backend.cplus_e_graph import GraphConflict

FIX=Path(__file__).resolve().parents[2]/'docs/contracts/e3_g3_terminal_candidate_v1.json'


def fixture():
    data=json.loads(FIX.read_text('utf-8'))
    return data['candidate'],data['admitted']


class G3TerminalContract(unittest.TestCase):
    def setUp(self): self.c,self.a=fixture()
    def invalid(self, path, value, in_admission=False):
        c,a=copy.deepcopy(self.c),copy.deepcopy(self.a)
        current=a if in_admission else c
        for field in path[:-1]: current=current[field]
        current[path[-1]]=value
        with self.assertRaises(GraphConflict):validate_terminal_candidate(c,a)

    def test_contract_only_never_unlocks(self):
        result=validate_terminal_candidate(self.c,self.a)
        self.assertEqual(result['state'],'DRY_RUN_VALIDATED')
        self.assertFalse(result['completion_verified'])
        self.assertFalse(result['durable_ack'])
        self.assertFalse(result['release_actor_fence'])
        self.assertEqual(set(self.c),FIELDS)

    def test_every_identity_wrong_or_missing(self):
        for key in ('task_id','request_id','attempt_id','requester_id','world_id',
                'dimension_id','session_id','actor_id','observation_id','goal_id'):
            with self.subTest(key=key):
                self.invalid([key],str(uuid4()) if key!='dimension_id' else 'minecraft:the_nether')
                broken=copy.deepcopy(self.c);broken.pop(key)
                with self.assertRaises(GraphConflict):validate_terminal_candidate(broken,self.a)

    def test_action_correlation_and_foreign_proofs(self):
        self.invalid(['action_id'],str(uuid4()))
        self.invalid(['c0','action_id'],str(uuid4()))
        self.invalid(['c2','action_id'],str(uuid4()))
        self.invalid(['c0','goal_id'],str(uuid4()))
        self.invalid(['c2','goal_id'],str(uuid4()))
        self.invalid(['goal_id'],str(uuid4()))

    def test_no_forged_or_incomplete_proofs(self):
        for path,v in [(['c1','active'],True),(['c1','phase'],'MOVING'),
                       (['c2','state'],'RUNNING'),(['c2','local_verified'],False),
                       (['c0','verified'],False),(['c0','status'],'running'),
                       (['world_effects'],True),(['outcome'],'CANCELLED')]:
            self.invalid(path,v)

    def test_position_and_tolerance(self):
        self.invalid(['c1','position','x'],120)
        self.invalid(['c1','position','y'],float('nan'))
        self.invalid(['target','x'],102)
        self.invalid(['tolerance'],True)
        self.invalid(['tolerance'],1.5)
        self.invalid(['target','z'],1.2)
        self.invalid(['target','z'],201,in_admission=True)
        self.invalid(['tolerance'],0.25,in_admission=True)

    def test_stale_hold_and_extra_fields_fail(self):
        self.invalid(['state'],'HOLD',in_admission=True)
        self.invalid(['goal_id'],None,in_admission=True)
        self.invalid(['schema_version'],2)
        self.invalid(['schema_version'],1.0)
        self.invalid(['dimension_id'],'minecraft:'+('a'*121))
        self.invalid(['action_kind'],'break_block')
        self.invalid(['node_id'],'walk')
        self.invalid(['unexpected'],True)
        self.invalid(['c0','extra'],True)

    def test_existing_g2_durable_attempt_never_unlocked_by_preview(self):
        import tempfile
        from npcortex_backend.cplus_e3_g2_motion import GuardedMotionStore
        from test_cplus_e3_g2_motion import body, ack
        with tempfile.TemporaryDirectory() as directory:
            store=GuardedMotionStore(Path(directory)/'g2.db')
            request=body()
            store.reserve(request)
            goal=str(uuid4())
            admitted=store.report(ack(request,goal_id=goal))
            c,_=fixture()
            for key in ('task_id','request_id','attempt_id','requester_id','world_id',
                        'dimension_id','session_id','actor_id','observation_id'):
                c[key]=admitted[key]
            c['goal_id']=goal
            c['action_id']=str(uuid4())
            c['c2']['goal_id']=c['c0']['goal_id']=goal
            c['c2']['action_id']=c['c0']['action_id']=c['action_id']
            c['target']=admitted['target']
            c['tolerance']=admitted['tolerance']
            c['c1']['position']={k:float(v) for k,v in admitted['target'].items()}
            result=validate_terminal_candidate(c,admitted)
            self.assertTrue(result['contract_valid'])
            self.assertFalse(result['completion_verified'])
            self.assertEqual(store.status(request['world_id'],request['actor_id'])['state'],
                             'C2_ACCEPTED')
            with self.assertRaises(GraphConflict):store.reserve(body(request['snapshot']))
            self.assertEqual(GuardedMotionStore(store.db).status(request['world_id'],
                request['actor_id'])['state'],'HOLD')

    def test_result_data_is_pure(self):
        before=json.dumps((self.c,self.a),sort_keys=True)
        validate_terminal_candidate(self.c,self.a)
        self.assertEqual(json.dumps((self.c,self.a),sort_keys=True),before)

if __name__=='__main__':unittest.main()
