"""C2-P0 service-side contract fixture; mocked sensor/AI, not a Minecraft gameplay test."""
import json
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.world_store import WorldStore, RegistryConflictError
from npcortex_backend.agent_skills import SkillRegistry

ROOT=Path(__file__).resolve().parents[2]
def uid(): return str(uuid4())

class Model:
    def chat_structured(self, messages, schema):
        return json.dumps({'action':'use_skill','skill_id':'move_to','args':{'position':[4.0,64.0,0.0]}})

class EmbodiedContractTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        path=Path(self.tmp.name)/'world.sqlite3'
        self.service=ChatService(ROOT/'character-packs','test-model',Model(),SessionStore(),
                                 world_store=WorldStore(path))
        self.world=self.service.register_world('C2 World',uid())
        self.actor=self.service.register_actor(self.world['world_id'],'minecraft:overworld','yui',uid())
        self.goal=self.service.create_agent_goal(world_id=self.world['world_id'],
            dimension='minecraft:overworld',actor_id=self.actor['actor_id'],
            objective='Move to verified coordinates',
            success_criteria=[{'kind':'position_near','position':[4.0,64.0,0.0],'distance':1.0}],
            max_decisions=1)
    def snap(self,x):
        return {'world_id':self.world['world_id'],'dimension_id':'minecraft:overworld',
                'actor_id':self.actor['actor_id'],'position':[x,64.0,0.0],
                'inventory':{'minecraft:diamond_sword':1},'blocks':{},'available_skills':['move_to']}
    def begin(self):
        gid=self.goal['goal_id']
        self.service.agent_observe(gid,uid(),self.snap(0))
        return self.service.agent_decide(gid,uid())['action_id']
    def test_no_goal_completion_before_real_result(self):
        aid=self.begin()
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['status'],'active')
        result=self.service.agent_result(aid,uid(),'succeeded',uid(),self.snap(4))
        self.assertTrue(result['verified'])
        self.assertEqual(result['goal']['status'],'completed')
        self.assertEqual(result['action_status'],'succeeded')
    def test_false_executor_success_is_not_verified(self):
        aid=self.begin()
        result=self.service.agent_result(aid,uid(),'succeeded',uid(),self.snap(0))
        self.assertFalse(result['verified'])
        self.assertEqual(result['action_status'],'unverified')
        self.assertNotEqual(result['goal']['status'],'completed')
    def test_duplicate_result_same_receipt_is_replay_not_reexecution(self):
        aid=self.begin();rid=uid();oid=uid();snapshot=self.snap(4)
        first=self.service.agent_result(aid,rid,'succeeded',oid,snapshot)
        second=self.service.agent_result(aid,rid,'succeeded',oid,snapshot)
        self.assertFalse(first['replayed']);self.assertTrue(second['replayed'])
        with self.assertRaises(RegistryConflictError):
            self.service.agent_result(aid,uid(),'succeeded',uid(),snapshot)
    def test_precision_goal_uses_exact_radius_for_action_verification(self):
        # The old C0 default 1.5 radius would reject this valid short movement.
        goal = self.service.create_agent_goal(world_id=self.world['world_id'],
            dimension='minecraft:overworld',actor_id=self.actor['actor_id'],
            objective='Precision movement to verified coordinate',
            success_criteria=[{'kind':'position_near','position':[4,64,0],'distance':0.20}],
            max_decisions=1)
        gid = goal['goal_id']
        self.service.agent_observe(gid,uid(),self.snap(3.70))
        decision = self.service.agent_decide(gid,uid())
        aid = decision['action_id']
        self.assertIsNotNone(aid)
        action = self.service.get_agent_goal(gid)['actions'][0]
        self.assertAlmostEqual(action['expected_effect']['distance'],0.20)
        failed = self.service.agent_result(aid,uid(),'succeeded',uid(),self.snap(3.75))
        self.assertFalse(failed['verified'])
        self.assertNotEqual(failed['goal']['status'],'completed')

    def test_precision_goal_verifies_after_reaching_goal(self):
        goal = self.service.create_agent_goal(world_id=self.world['world_id'],
            dimension='minecraft:overworld',actor_id=self.actor['actor_id'],
            objective='Reach precise nearby destination',
            success_criteria=[{'kind':'position_near','position':[4,64,0],'distance':0.35}],
            max_decisions=1)
        gid = goal['goal_id']
        self.service.agent_observe(gid,uid(),self.snap(3.60))
        aid = self.service.agent_decide(gid,uid())['action_id']
        receipt = self.service.agent_result(aid,uid(),'succeeded',uid(),self.snap(3.74))
        self.assertTrue(receipt['verified'])
        self.assertEqual(receipt['goal']['status'],'completed')

    def test_precision_tolerance_survives_allowed_ai_coordinate_rounding(self):
        # Forge authorizes a destination offset up to 0.05 from user goal.
        args, expected = SkillRegistry.prepare('move_to',
            {'position': [4.03, 64, 0]}, self.snap(3.6),
            [{'kind': 'position_near', 'position': [4,64,0], 'distance': 0.20}])
        self.assertEqual(args['position'], [4.03,64.0,0.0])
        self.assertAlmostEqual(expected['distance'], 0.20)

    def test_at_precision_goal_requires_no_new_decision(self):
        goal = self.service.create_agent_goal(world_id=self.world['world_id'],
            dimension='minecraft:overworld',actor_id=self.actor['actor_id'],
            objective='Stay within precision target',
            success_criteria=[{'kind':'position_near','position':[4,64,0],'distance':0.2}],
            max_decisions=1)
        observation=self.service.agent_observe(goal['goal_id'],uid(),self.snap(3.90))
        self.assertEqual(observation['goal']['status'],'completed')
        self.assertEqual(observation['goal']['actions'],[])

    def test_crash_state_remains_pending_without_auto_reexecution(self):
        aid=self.begin()
        restarted=ChatService(ROOT/'character-packs','test-model',Model(),SessionStore(),
                              world_store=WorldStore(self.service.world_store.db_path))
        self.assertEqual(restarted.get_agent_goal(self.goal['goal_id'])['pending_action_id'],aid)
        self.assertEqual(len(restarted.get_agent_goal(self.goal['goal_id'])['actions']),1)
        with self.assertRaises(RegistryConflictError):
            restarted.agent_decide(self.goal['goal_id'],uid())

if __name__=='__main__': unittest.main()
