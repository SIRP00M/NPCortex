"""C0 planner reliability: actual contracts, HTTP classification and Ollama transport."""
from io import BytesIO
from pathlib import Path
from threading import Thread
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4
import json
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend' / 'src'))
from npcortex_backend.agent_runtime import PlannerDecisionError
from npcortex_backend.agent_skills import SkillRegistry
from npcortex_backend.ollama import OllamaProvider, ModelError
from npcortex_backend.server import make_http_server
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.world_store import WorldStore


def uid():
    return str(uuid4())


class ControlledPlanner:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.prompts = []
        self.schemas = []

    def chat_structured(self, messages, schema):
        self.prompts.append(messages)
        self.schemas.append(schema)
        reply = self.responses.pop(0) if self.responses else '{"action":"blocked","reason":"no feasible skill"}'
        if isinstance(reply, Exception):
            raise reply
        return reply

    def chat(self, messages):
        return self.chat_structured(messages, None)


class C0PlannerReliabilityTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.db = Path(temp.name) / 'npcortex.sqlite3'
        self.ai = ControlledPlanner()
        self.svc = ChatService(ROOT / 'character-packs', 'mock', self.ai,
                               SessionStore(), world_store=WorldStore(self.db))
        self.world = self.svc.register_world('C0 Planner Test')
        self.actor = self.svc.register_actor(self.world['world_id'], 'minecraft:overworld', 'yui')
        self.goal = self.svc.create_agent_goal(
            world_id=self.world['world_id'], dimension='minecraft:overworld',
            actor_id=self.actor['actor_id'], objective='Break oak log at 1,64,1',
            success_criteria=[{'kind':'block_is', 'position':[1,64,1], 'block_id':'minecraft:air'}])
        self.snapshot = {
            'world_id':self.world['world_id'], 'dimension_id':'minecraft:overworld',
            'actor_id':self.actor['actor_id'], 'position':[0,64,0],
            'inventory':{'minecraft:dirt':2},
            'blocks':{'1,64,1':'minecraft:oak_log', '2,64,2':'minecraft:air'},
            'available_skills':['break_block'], 'nearby_entities':[], 'nearby_drops':[]}
        self.svc.agent_observe(self.goal['goal_id'], uid(), self.snapshot)

    def decide(self):
        return self.svc.agent_decide(self.goal['goal_id'], uid())

    def test_dynamic_schema_only_has_advertised_skill_and_blocked(self):
        schema, descriptions = SkillRegistry.planner_contract(self.snapshot)
        self.assertEqual(set(descriptions), {'break_block'})
        self.assertEqual(len(schema['oneOf']), 2)
        self.assertEqual(schema['oneOf'][1]['properties']['skill_id']['const'], 'break_block')
        self.assertEqual(descriptions['break_block']['observed_argument_examples'], [{'position':[1,64,1]}])
        self.assertNotIn('move_to', json.dumps(schema))
        self.assertNotIn('place_block', json.dumps(schema))

    def test_success_uses_schema_and_grounded_example_and_no_false_verification(self):
        self.ai.responses = ['{"action":"use_skill","skill_id":"break_block","args":{"position":[1,64,1]}}']
        decision = self.decide()
        self.assertIsNotNone(decision['action_id'])
        self.assertEqual(decision['decision']['skill_id'], 'break_block')
        self.assertEqual(self.ai.schemas[0]['oneOf'][1]['properties']['skill_id']['const'], 'break_block')
        prompt = self.ai.prompts[0][1]['content']
        self.assertIn('observed_argument_examples', prompt)
        self.assertIn('"position":[1,64,1]', prompt)
        self.assertEqual(self.svc.get_agent_goal(self.goal['goal_id'])['status'], 'active')

    def test_rejected_args_trigger_specific_feedback_then_valid_action(self):
        self.ai.responses = [
            '{"action":"use_skill","skill_id":"break_block","args":{"coordinates":[1,64,1]}}',
            '{"action":"use_skill","skill_id":"break_block","args":{"position":[1,64,1]}}',
        ]
        decision = self.decide()
        self.assertIsNotNone(decision['action_id'])
        self.assertEqual(len(self.ai.prompts), 2)
        self.assertIn('invalid_skill_arguments', self.ai.prompts[1][-1]['content'])
        self.assertIn('unsupported skill arguments', self.ai.prompts[1][-1]['content'])
        self.assertEqual(self.svc.get_agent_goal(self.goal['goal_id'])['planner_failures'], 0)

    def test_invalid_skill_does_not_create_action_and_tracks_failure(self):
        self.ai.responses = [
            '{"action":"use_skill","skill_id":"fly","args":{}}',
            '{"action":"use_skill","skill_id":"move_to","args":{"position":[1,64,1]}}',
        ]
        with self.assertRaises(PlannerDecisionError):
            self.decide()
        goal = self.svc.get_agent_goal(self.goal['goal_id'])
        self.assertEqual(goal['last_error'], 'invalid_model_decision')
        self.assertEqual(goal['planner_failures'], 1)
        self.assertEqual(goal['actions'], [])
        self.assertEqual(goal['decisions_made'], 0)

    def test_schema_does_not_weaken_server_preconditions(self):
        self.ai.responses = ['{"action":"use_skill","skill_id":"break_block","args":{"position":[9,64,9]}}'] * 2
        with self.assertRaises(PlannerDecisionError):
            self.decide()
        self.assertEqual(self.svc.get_agent_goal(self.goal['goal_id'])['actions'], [])
        self.assertIn('must be observed', self.ai.prompts[1][-1]['content'])

    def test_transport_errors_are_distinct_from_invalid_decisions(self):
        self.ai.responses = [ModelError('temporary offline')]
        with self.assertRaises(ModelError):
            self.decide()
        goal = self.svc.get_agent_goal(self.goal['goal_id'])
        self.assertEqual(goal['last_error'], 'model_unavailable')
        self.assertEqual(goal['planner_failures'], 1)
        self.assertEqual(goal['actions'], [])

    def test_http_invalid_decision_has_distinct_code(self):
        self.ai.responses = ['not JSON', 'not JSON']
        server = make_http_server(self.svc, '127.0.0.1', 0)
        self.addCleanup(server.server_close)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda:(server.shutdown(), thread.join(timeout=4)))
        url = f'http://127.0.0.1:{server.server_port}/v1/agent/goals/{self.goal["goal_id"]}/decide'
        req = Request(url, data=json.dumps({'request_id':uid()}).encode(),
                      headers={'Content-Type':'application/json'}, method='POST')
        with self.assertRaises(HTTPError) as raised:
            urlopen(req, timeout=5)
        with raised.exception as exc:
            response = json.load(exc)
        self.assertEqual(raised.exception.code, 502)
        self.assertEqual(response['code'], 'planner_invalid_decision')
        self.assertNotIn('not JSON', json.dumps(response))

    def test_schema_scopes_all_four_skills_and_valid_examples(self):
        snap = dict(self.snapshot)
        snap['available_skills'] = ['move_to','break_block','place_block','pickup_item']
        snap['nearby_drops'] = [{'item_id':'minecraft:oak_log','count':1,'position':[0,64,1]}]
        schema, contracts = SkillRegistry.planner_contract(snap)
        self.assertEqual(len(schema['oneOf']), 5)
        self.assertEqual(contracts['place_block']['observed_argument_examples'][0]['block_id'],'minecraft:dirt')
        self.assertEqual(contracts['pickup_item']['observed_argument_examples'], [{'item_id':'minecraft:oak_log'}])
        self.assertEqual(contracts['move_to']['observed_argument_examples'], [])


class OllamaStructuredOutputTests(unittest.TestCase):
    @staticmethod
    def fake_reply():
        return BytesIO(json.dumps({'message':{'content':'{"action":"blocked","reason":"n/a"}'}}).encode())

    def test_c0_uses_schema_and_deterministic_options(self):
        provider = OllamaProvider('http://127.0.0.1:11434', 'qwen3:8b')
        requests = []
        def fake_open(req, timeout):
            requests.append(json.loads(req.data))
            return self.fake_reply()
        with patch('npcortex_backend.ollama.urlopen', side_effect=fake_open):
            provider.chat_structured([{'role':'user','content':'test'}], {'type':'object','oneOf':[]})
        self.assertEqual(requests[0]['format'], {'type':'object','oneOf':[]})
        self.assertEqual(requests[0]['options']['temperature'], 0)
        self.assertEqual(requests[0]['options']['num_predict'], 256)
        self.assertEqual(requests[0]['think'], False)

    def test_existing_b3_json_mode_unchanged(self):
        provider = OllamaProvider('http://127.0.0.1:11434', 'qwen3:8b')
        requests = []
        def fake_open(req, timeout):
            requests.append(json.loads(req.data))
            return self.fake_reply()
        with patch('npcortex_backend.ollama.urlopen', side_effect=fake_open):
            provider.chat_json([{'role':'user','content':'test'}])
            provider.chat([{'role':'user','content':'test'}])
        self.assertEqual(requests[0]['format'],'json')
        self.assertNotIn('temperature', requests[0]['options'])
        self.assertEqual(requests[0]['options']['num_predict'], 160)
        self.assertNotIn('format', requests[1])


if __name__ == '__main__':
    unittest.main()
