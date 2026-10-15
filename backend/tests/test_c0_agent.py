"""C0 contract/agent tests: real SQLite, deterministic model, mock Forge facts."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from threading import Thread
from unittest.mock import Mock
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4
import json
import sqlite3
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'backend'/'src'))
from npcortex_backend.identity import IdentityError
from npcortex_backend.world_store import WorldStore, RegistryConflictError, SCHEMA_VERSION
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.server import make_http_server
from npcortex_backend.ollama import ModelError
from npcortex_backend.agent_runtime import PlannerDecisionError
from sqlite_test_utils import sqlite_test_connection


def uid(): return str(uuid4())


class FakePlanner:
    def __init__(self):
        self.responses = []
        self.prompts = []

    def chat_json(self, messages):
        self.prompts.append(messages)
        if self.responses:
            value = self.responses.pop(0)
            if isinstance(value, BaseException):
                raise value
            return value
        return json.dumps({'action':'use_skill','skill_id':'break_block','args':{'position':[1,64,1]}})

    def chat(self, messages):
        return self.chat_json(messages)


class C0Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.db_path = Path(self.tmp.name)/'npcortex.sqlite3'
        self.ai = FakePlanner()
        self.service = self.make_service()
        self.world = self.service.register_world('C0 Test')
        self.actor = self.service.register_actor(self.world['world_id'],'minecraft:overworld','yui')
        self.goal = self.create_goal()

    def make_service(self, ai=None):
        return ChatService(ROOT/'character-packs','mock',ai or self.ai,
                           SessionStore(),world_store=WorldStore(self.db_path))

    def create_goal(self, criteria=None, **kwargs):
        return self.service.create_agent_goal(
            world_id=self.world['world_id'], dimension='minecraft:overworld',
            actor_id=self.actor['actor_id'], objective='Clear block at 1 64 1',
            success_criteria=criteria or [{'kind':'block_is','position':[1,64,1],'block_id':'minecraft:air'}],
            **kwargs)

    def snap(self, block='minecraft:oak_log', skills=None, inv=None):
        return {'world_id':self.world['world_id'],'dimension_id':'minecraft:overworld',
                'actor_id':self.actor['actor_id'],'position':[0,64,0],
                'inventory': inv or {'minecraft:oak_log':0,'minecraft:dirt':2},
                'blocks':{'1,64,1':block},'available_skills':skills if skills is not None else ['break_block','move_to','place_block','pickup_item']}

    def observe(self,snap=None,goal=None,oid=None):
        return self.service.agent_observe((goal or self.goal)['goal_id'],oid or uid(),snap or self.snap())

    def decide(self,goal=None,rid=None):
        return self.service.agent_decide((goal or self.goal)['goal_id'],rid or uid())

    def result(self,action_id, snap=None, status='succeeded',rid=None,oid=None):
        return self.service.agent_result(action_id,rid or uid(),status,oid or uid(),snap or self.snap('minecraft:air'))

    def test_schema_v3_to_v4_preserves_existing_b1_b2_b3_data(self):
        guide=self.service.register_actor(self.world['world_id'],'minecraft:overworld','guide')
        legacy=self.service.chat_actor(self.world['world_id'],'minecraft:overworld',self.actor['actor_id'],'legacy','Old history')
        dialogue=self.service.create_dialogue(self.world['world_id'],'minecraft:overworld',
                    [self.actor['actor_id'],guide['actor_id']],[],'Before C0 migration')
        run=self.service.create_social_run(dialogue['dialogue_id'],2)
        with self.service.world_store.database.write() as db:
            for table in ('agent_actions','agent_decisions','agent_observations','agent_goals'):
                db.execute(f'DROP TABLE {table}')
            db.execute('PRAGMA user_version=3')
        migrated=WorldStore(self.db_path)
        self.assertEqual(migrated.get_conversation(legacy['conversation_id'])['messages'][0]['content'],'Old history')
        with migrated.database.read() as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0],4)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM dialogues').fetchone()[0],1)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM social_runs').fetchone()[0],1)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM agent_goals').fetchone()[0],0)
            self.assertEqual(db.execute('PRAGMA integrity_check').fetchone()[0],'ok')
        WorldStore(self.db_path)  # idempotent second startup

    def test_register_goal_is_durable_and_does_not_touch_social(self):
        self.assertEqual(self.goal['status'],'pending')
        self.assertEqual(SCHEMA_VERSION,4)
        with sqlite_test_connection(self.db_path) as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0],4)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM social_runs').fetchone()[0],0)
        other = self.make_service()
        self.assertEqual(other.get_agent_goal(self.goal['goal_id'])['objective'],self.goal['objective'])

    def test_actor_world_identity_checked_before_persist(self):
        with self.assertRaises(RegistryConflictError):
            self.service.create_agent_goal(world_id=uid(),dimension='minecraft:overworld',
                actor_id=self.actor['actor_id'], objective='x',success_criteria=self.goal['success_criteria'])
        with self.assertRaises(IdentityError):
            self.service.create_agent_goal(world_id=self.world['world_id'],dimension='minecraft:overworld',
                actor_id=self.actor['actor_id'],objective='x',success_criteria=[{'kind':'invent','foo':'bar'}])

    def test_goal_registration_is_idempotent_with_fixed_id(self):
        arguments = dict(world_id=self.world['world_id'],dimension='minecraft:overworld',
                         actor_id=self.actor['actor_id'], objective='Fixed goal',
                         success_criteria=self.goal['success_criteria'],goal_id=uid())
        a=self.service.create_agent_goal(**arguments)
        b=self.service.create_agent_goal(**arguments)
        self.assertEqual(a['goal_id'],b['goal_id'])
        with self.assertRaises(RegistryConflictError):
            self.service.create_agent_goal(**{**arguments,'objective':'Different'})

    def test_bad_sensor_identity_and_unobserved_target_rejected(self):
        changed=self.snap(); changed['actor_id']=uid()
        with self.assertRaises(IdentityError): self.observe(changed)
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['snapshot_seq'],0)
        self.observe(self.snap())
        self.ai.responses=['{"action":"use_skill","skill_id":"break_block","args":{"position":[5,64,5]}}']*2
        with self.assertRaises(PlannerDecisionError): self.decide()
        self.assertEqual(len(self.service.get_agent_goal(self.goal['goal_id'])['actions']),0)

    def test_observation_replay_and_conflict_and_goal_already_done(self):
        oid=uid(); a=self.observe(oid=oid); self.assertFalse(a['replayed'])
        b=self.observe(oid=oid); self.assertTrue(b['replayed'])
        with self.assertRaises(RegistryConflictError): self.observe(self.snap('minecraft:air'),oid=oid)
        self.observe(self.snap('minecraft:air'))
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['status'],'completed')
        with self.assertRaises(RegistryConflictError): self.decide()

    def test_qwen_uses_only_advertised_skills(self):
        self.observe(self.snap(skills=['move_to']))
        self.ai.responses=['{"action":"use_skill","skill_id":"break_block","args":{"position":[1,64,1]}}']*2
        with self.assertRaises(PlannerDecisionError): self.decide()
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['decisions_made'],0)

    def test_model_invalid_retries_twice_no_hallucinated_fallback(self):
        self.observe()
        self.ai.responses=['not JSON','{"action":"use_skill","skill_id":"fly","args":{}}']
        with self.assertRaises(PlannerDecisionError): self.decide()
        self.assertEqual(len(self.ai.prompts),2)
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['actions'],[])

    def test_action_pending_persist_and_replayed_after_restart(self):
        self.observe(); rid=uid()
        first=self.decide(rid=rid)
        later=self.make_service(FakePlanner()).agent_decide(self.goal['goal_id'],rid)
        self.assertEqual(first['action_id'],later['action_id'])
        self.assertTrue(later['replayed'])
        self.assertEqual(len(self.ai.prompts),1)
        with self.assertRaises(RegistryConflictError): self.decide()
        with self.assertRaises(RegistryConflictError): self.observe()

    def test_succeeded_claim_without_effect_is_unverified(self):
        self.observe()
        action=self.decide()['action_id']
        response=self.result(action,self.snap(),status='succeeded')
        self.assertFalse(response['verified'])
        self.assertEqual(response['action_status'],'unverified')
        self.assertEqual(response['goal']['status'],'active')

    def test_verified_action_and_goal_completion(self):
        self.observe(); action=self.decide()['action_id']
        response=self.result(action,self.snap('minecraft:air'))
        self.assertTrue(response['verified'])
        self.assertEqual(response['action_status'],'succeeded')
        self.assertEqual(response['goal']['status'],'completed')
        self.assertEqual(len(response['goal']['actions']),1)

    def test_executor_failed_does_not_count_as_verified_even_if_effect_seen(self):
        self.observe(); action=self.decide()['action_id']
        response=self.result(action,self.snap('minecraft:air'),status='failed')
        self.assertEqual(response['action_status'],'failed')
        self.assertFalse(response['verified'])
        # A goal can independently be observed achieved despite the executor's failure flag.
        self.assertEqual(response['goal']['status'],'completed')

    def test_result_idempotency_conflict(self):
        self.observe(); action=self.decide()['action_id']; rid=uid(); oid=uid()
        a=self.result(action,rid=rid,oid=oid)
        b=self.make_service().agent_result(action,rid,'succeeded',oid,self.snap('minecraft:air'))
        self.assertTrue(b['replayed'])
        with self.assertRaises(RegistryConflictError): self.result(action,rid=uid(),oid=uid())
        self.assertEqual(a['goal']['snapshot_seq'],b['goal']['snapshot_seq'])

    def test_result_requires_fresh_observation_uuid(self):
        oid=uid();self.observe(oid=oid);action=self.decide()['action_id']
        with self.assertRaises(RegistryConflictError):self.result(action,oid=oid)
        self.assertIsNotNone(self.service.get_agent_goal(self.goal['goal_id'])['pending_action_id'])

    def test_model_failure_no_action_and_retry(self):
        self.observe();self.ai.responses=[ModelError('unavailable')]
        with self.assertRaises(ModelError): self.decide()
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['decisions_made'],0)
        self.assertIsNotNone(self.decide()['action_id'])

    def test_three_invalid_model_decisions_block_goal_without_actions(self):
        self.observe()
        for i in range(3):
            self.ai.responses=['invalid','invalid']
            with self.assertRaises(PlannerDecisionError): self.decide()
        goal=self.service.get_agent_goal(self.goal['goal_id'])
        self.assertEqual(goal['planner_failures'],3)
        self.assertEqual(goal['status'],'blocked')
        self.assertEqual(goal['actions'],[])
        # A new verified sensor update allows planning again.
        self.observe()
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['planner_failures'],0)

    def test_three_provider_failures_block_without_false_completion(self):
        self.observe()
        for i in range(3):
            self.ai.responses=[ModelError('mock failure')]
            with self.assertRaises(ModelError):self.decide()
        goal=self.service.get_agent_goal(self.goal['goal_id'])
        self.assertEqual(goal['status'],'blocked')
        self.assertEqual(goal['decisions_made'],0)

    def test_blocked_decision_is_not_success(self):
        self.observe()
        self.ai.responses=['{"action":"blocked","reason":"No safe approach"}']
        result=self.decide(); self.assertEqual(result['decision']['action'],'blocked')
        self.assertIsNone(result['action_id'])
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['status'],'blocked')
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['last_error'],'No safe approach')
        self.observe(self.snap())
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['status'],'active')

    def test_decision_budget_failed_if_not_completed(self):
        other=self.create_goal(max_decisions=1)
        self.observe(goal=other)
        action=self.decide(goal=other)['action_id']
        result=self.result(action,self.snap(),status='failed')
        self.assertEqual(result['goal']['status'],'failed')
        with self.assertRaises(RegistryConflictError):
            self.service.agent_observe(other['goal_id'],uid(),self.snap())

    def test_no_skill_available_cannot_create_action(self):
        self.observe(self.snap(skills=['not_a_skill']))
        with self.assertRaises(RegistryConflictError): self.decide()
        self.assertEqual(len(self.ai.prompts),0)

    def test_snapshot_strict_rejects_nan_huge_malformed(self):
        variants=[]
        a=self.snap();a['position']=[float('nan'),64,0];variants.append(a)
        b=self.snap();b['inventory']={'minecraft:dirt':True};variants.append(b)
        c=self.snap();c['blocks']={'not-coords':'minecraft:air'};variants.append(c)
        d=self.snap();d['available_skills']=['break_block']*2;variants.append(d)
        for value in variants:
            with self.subTest(snapshot=value):
                with self.assertRaises(IdentityError):self.observe(value)

    def test_two_actors_do_not_share_goals(self):
        another=self.service.register_actor(self.world['world_id'],'minecraft:overworld','yui')
        second=self.service.create_agent_goal(world_id=self.world['world_id'],dimension='minecraft:overworld',
                actor_id=another['actor_id'],objective='Other actor',success_criteria=self.goal['success_criteria'])
        self.observe()
        self.assertEqual(self.service.get_agent_goal(second['goal_id'])['snapshot_seq'],0)

    def test_concurrent_same_request_yields_one_action(self):
        self.observe();rid=uid()
        with ThreadPoolExecutor(max_workers=2) as pool:
            values=list(pool.map(lambda _:self.decide(rid=rid),[1,2]))
        self.assertEqual(values[0]['action_id'],values[1]['action_id'])
        self.assertEqual(len(self.service.get_agent_goal(self.goal['goal_id'])['actions']),1)

    def test_sqlite_failure_rolls_back_decision_and_action(self):
        self.observe()
        with self.service.world_store.database.write() as db:
            db.execute("CREATE TRIGGER fail_c0_action BEFORE INSERT ON agent_actions "
                       "BEGIN SELECT RAISE(ABORT,'simulated action insert failure'); END")
        with self.assertRaises(sqlite3.DatabaseError): self.decide()
        goal=self.service.get_agent_goal(self.goal['goal_id'])
        self.assertEqual(goal['decisions_made'],0)
        self.assertIsNone(goal['pending_action_id'])
        with self.service.world_store.database.read() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM agent_decisions').fetchone()[0],0)
        with self.service.world_store.database.write() as db:
            db.execute('DROP TRIGGER fail_c0_action')
        self.assertIsNotNone(self.decide()['action_id'])

    def test_recovery_after_restart_without_action_resend(self):
        self.observe()
        rid=uid(); issued=self.decide(rid=rid)
        restarted=self.make_service(FakePlanner())
        goal=restarted.get_agent_goal(self.goal['goal_id'])
        self.assertEqual(goal['pending_action_id'],issued['action_id'])
        with self.assertRaises(RegistryConflictError):
            restarted.agent_decide(self.goal['goal_id'],uid())
        completed=restarted.agent_result(issued['action_id'],uid(),'succeeded',uid(),self.snap('minecraft:air'))
        self.assertEqual(completed['goal']['status'],'completed')
        self.assertEqual(completed['goal']['decisions_made'],1)

    def test_complete_goal_with_position_and_inventory_observations(self):
        goals=[
            self.create_goal(criteria=[{'kind':'position_near','position':[2,64,2],'distance':1.5}]),
            self.create_goal(criteria=[{'kind':'inventory_at_least','item_id':'minecraft:oak_log','count':3}])
        ]
        snap1=self.snap();snap1['position']=[2,64,2]
        snap2=self.snap(inv={'minecraft:oak_log':3})
        for goal,snap in zip(goals,[snap1,snap2]):
            self.assertEqual(self.observe(snap,goal=goal)['goal']['status'],'completed')

    def test_skill_postconditions_and_preconditions(self):
        from npcortex_backend.agent_skills import SkillRegistry
        before=self.snap()
        cases=[('move_to',{'position':[5,64,5]},'position_near'),
               ('break_block',{'position':[1,64,1]},'block_is'),
               ('place_block',{'position':[2,64,2],'block_id':'minecraft:dirt'},'block_is'),
               ('pickup_item',{'item_id':'minecraft:oak_log'},'inventory_at_least')]
        before['blocks']['2,64,2']='minecraft:air'
        before['nearby_drops']=[{'item_id':'minecraft:oak_log','count':1,'position':[0,64,1]}]
        for sid,args,kind in cases:
            _,expected=SkillRegistry.prepare(sid,args,before)
            self.assertEqual(expected['kind'],kind)
        with self.assertRaises(IdentityError):
            SkillRegistry.prepare('place_block',{'position':[1,64,1],'block_id':'minecraft:dirt'},before)
        with self.assertRaises(IdentityError):
            SkillRegistry.prepare('pickup_item',{'item_id':'minecraft:dirt'},before)


class C0HTTPTests(C0Tests):
    def setUp(self):
        super().setUp()
        server = make_http_server(self.service,'127.0.0.1',0)
        self.addCleanup(server.server_close)
        thread=Thread(target=server.serve_forever,daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(),thread.join(timeout=4)))
        self.url=f'http://127.0.0.1:{server.server_port}'

    def post(self,path,data):
        req=Request(self.url+path,data=json.dumps(data).encode(),headers={'Content-Type':'application/json'},method='POST')
        try:
            with urlopen(req,timeout=5) as response:
                return response.status,json.load(response)
        except HTTPError as exc:
            return exc.code,json.load(exc)

    def test_http_c0_complete_flow_and_legacy(self):
        gid=self.goal['goal_id']
        status,obs=self.post(f'/v1/agent/goals/{gid}/observe',{'observation_id':uid(),'snapshot':self.snap()})
        self.assertEqual(status,200)
        status,d=self.post(f'/v1/agent/goals/{gid}/decide',{'request_id':uid()})
        self.assertEqual(status,200)
        status,result=self.post(f'/v1/agent/actions/{d["action_id"]}/result',
                                {'result_id':uid(),'status':'succeeded','observation_id':uid(),
                                 'snapshot':self.snap('minecraft:air')})
        self.assertEqual(status,200)
        self.assertEqual(result['goal']['status'],'completed')
        with urlopen(self.url+f'/v1/agent/goals/{gid}',timeout=5) as response:
            self.assertEqual(json.load(response)['status'],'completed')
        self.assertEqual(self.post('/v1/chat',{'character_id':'yui','player_id':'test','message':'hello'})[0],200)

    def test_http_validation_and_404(self):
        gid=self.goal['goal_id']
        self.assertEqual(self.post(f'/v1/agent/goals/{gid}/decide',{'request_id':'bad'})[0],400)
        self.assertEqual(self.post(f'/v1/agent/goals/{gid}/observe',{'snapshot':self.snap()})[0],400)
        self.assertEqual(self.post('/v1/agent/goals',{'world_id':'not uuid'})[0],400)
        self.assertEqual(self.post('/v1/agent/actions/'+uid()+'/result',
            {'result_id':uid(),'observation_id':uid(),'status':'failed','snapshot':self.snap()})[0],404)


if __name__=='__main__': unittest.main()
