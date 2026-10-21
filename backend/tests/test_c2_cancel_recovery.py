"""P1-C Fix #1 durable cancellation, HTTP contract, and restart/no replay tests."""
from pathlib import Path
import sys
import tempfile
import json
import unittest
from uuid import uuid4
from threading import Thread
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen
from urllib.error import HTTPError

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'backend'/'src'))
from npcortex_backend.world_store import WorldStore,RegistryConflictError
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.server import make_http_server


def uid(): return str(uuid4())

class Planner:
    def chat(self,messages):
        return json.dumps({'action':'use_skill','skill_id':'move_to','args':{'position':[3,64,0]}})
    def chat_json(self,messages): return self.chat(messages)

class CancellationTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.path=Path(self.tmp.name)/'store.db'
        self.service=self.new_service()
        self.world=self.service.register_world('Cancel test')
        self.actor=self.service.register_actor(self.world['world_id'],'minecraft:overworld','yui')
        self.kw=dict(world_id=self.world['world_id'],dimension='minecraft:overworld',
                     actor_id=self.actor['actor_id'],objective='Move',
                     success_criteria=[{'kind':'position_near','position':[3,64,0],'distance':0.35}],max_decisions=1)
        self.goal=self.service.create_agent_goal(**self.kw)
        self.c=dict(world_id=self.world['world_id'],actor_id=self.actor['actor_id'])

    def new_service(self):
        return ChatService(ROOT/'character-packs','mock',Planner(),SessionStore(),world_store=WorldStore(self.path))

    def snapshot(self):
        return dict(world_id=self.world['world_id'],dimension_id='minecraft:overworld',
                    actor_id=self.actor['actor_id'],position=[0,64,0],inventory={},blocks={},
                    available_skills=['move_to'])

    def pending(self):
        gid=self.goal['goal_id']
        self.service.agent_observe(gid,uid(),self.snapshot())
        return self.service.agent_decide(gid,uid())['action_id']

    def cancel(self, **args):
        return self.service.cancel_agent_goal(self.goal['goal_id'],
                     args.get('world_id',self.c['world_id']), args.get('actor_id',self.c['actor_id']),
                     args.get('action_id'))

    def test_cancel_after_action_persisted_idempotent_across_restart(self):
        action=self.pending()
        done=self.cancel(action_id=action)
        self.assertTrue(done['cancelled'])
        self.assertTrue(done['terminal'])
        self.assertIsNone(done['pending_action_id'])
        self.assertFalse(done['replayed'])
        again=self.new_service().cancel_agent_goal(self.goal['goal_id'],**self.c,action_id=action)
        self.assertTrue(again['replayed'])
        with self.new_service().world_store.database.read() as db:
            state=db.execute('SELECT status,verdict FROM agent_actions WHERE action_id=?',(action,)).fetchone()
            self.assertEqual(tuple(state),('failed','operator_cancelled'))
            self.assertIsNone(db.execute('SELECT pending_action_id FROM agent_goals WHERE goal_id=?',
                                         (self.goal['goal_id'],)).fetchone()[0])
        with self.assertRaises(RegistryConflictError):
            self.service.agent_result(action,uid(),'succeeded',uid(),self.snapshot())
        with self.assertRaises(RegistryConflictError):
            self.service.agent_decide(self.goal['goal_id'],uid())

    def test_cancel_before_decision_prevents_late_decisions(self):
        self.service.agent_observe(self.goal['goal_id'],uid(),self.snapshot())
        done=self.cancel()
        self.assertTrue(done['cancelled']);self.assertIsNone(done['action_id'])
        with self.assertRaises(RegistryConflictError):self.service.agent_decide(self.goal['goal_id'],uid())

    def test_cancel_before_observation_prevents_goal_activation(self):
        self.cancel()
        with self.assertRaises(RegistryConflictError):
            self.service.agent_observe(self.goal['goal_id'],uid(),self.snapshot())

    def test_wrong_identity_or_action_denied_without_mutation(self):
        action=self.pending()
        for fields in ({'actor_id':uid()},{'world_id':uid()},{'action_id':uid()}):
            with self.assertRaises(RegistryConflictError): self.cancel(**fields)
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['pending_action_id'],action)

    def test_terminal_success_is_not_rewritten_as_cancel(self):
        self.service.agent_observe(self.goal['goal_id'],uid(),self.snapshot())
        self.service.agent_observe(self.goal['goal_id'],uid(),{**self.snapshot(),'position':[3,64,0]})
        result=self.cancel()
        self.assertEqual(result['status'],'completed')
        self.assertFalse(result['cancelled'])
        self.assertEqual(self.service.get_agent_goal(self.goal['goal_id'])['status'],'completed')

    def test_cancel_allows_new_goal_after_ack(self):
        self.pending(); self.cancel()
        next_goal=self.new_service().create_agent_goal(**{**self.kw,'goal_id':uid()})
        self.assertEqual(next_goal['status'],'pending')

    def test_cancel_result_race_never_leaves_pending_action(self):
        action=self.pending()
        def attempt_cancel():
            return self.new_service().cancel_agent_goal(self.goal['goal_id'],**self.c)
        def attempt_result():
            try:
                return self.new_service().agent_result(action,uid(),'succeeded',uid(),
                    {**self.snapshot(),'position':[3,64,0]})
            except RegistryConflictError:
                return 'cancel_won'
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures=[pool.submit(attempt_cancel),pool.submit(attempt_result)]
            for future in futures: future.result(timeout=10)
        goal=self.service.get_agent_goal(self.goal['goal_id'])
        self.assertIn(goal['status'],('completed','failed'))
        self.assertIsNone(goal['pending_action_id'])
        self.assertNotEqual(goal['actions'][0]['status'],'pending')

    def test_cancel_http_contract_validation_and_replay(self):
        server=make_http_server(self.service,'127.0.0.1',0)
        self.addCleanup(server.server_close)
        thread=Thread(target=server.serve_forever,daemon=True);thread.start()
        self.addCleanup(lambda:(server.shutdown(),thread.join(timeout=4)))
        url=f'http://127.0.0.1:{server.server_port}/v1/agent/goals/{self.goal["goal_id"]}/cancel'
        def post(d):
            req=Request(url,data=json.dumps(d).encode(),headers={'Content-Type':'application/json'},method='POST')
            try:
                with urlopen(req,timeout=5) as res:return res.status,json.load(res)
            except HTTPError as err:return err.code,json.load(err)
        self.assertEqual(post({})[0],400)
        self.assertEqual(post({**self.c,'extra':2})[0],400)
        self.assertEqual(post({**self.c,'actor_id':uid()})[0],409)
        good=post(self.c)
        self.assertEqual(good[0],200)
        self.assertTrue(good[1]['cancelled'])
        again=post(self.c)
        self.assertTrue(again[1]['replayed'])

if __name__=='__main__': unittest.main()
