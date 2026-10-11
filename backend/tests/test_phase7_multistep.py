"""Phase 7.1 / v0.5.3: bounded multi-action planning regression tests.

These validate the Python response shape and intent fallback. Actual transfers
must still be verified with the Forge Minecraft server.
"""
from copy import deepcopy
import http.client
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend' / 'src'))
from npcortex_backend.agent import parse_agent_result, augment_simple_handoff, sanitize_observation
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.server import make_handler

WORLD = {
    'dimension': 'minecraft:overworld', 'biome': 'minecraft:plains',
    'time': 'day', 'weather': 'clear',
    'player': {'distance': 2, 'health': 20, 'holding': 'minecraft:air'},
    'npc': {'following': False, 'health': 20, 'holding': 'minecraft:air',
            'inventory': [], 'holding_count': 0},
    'blocks': {'below': 'minecraft:grass_block', 'feet': 'minecraft:air', 'ahead': 'minecraft:air'},
    'nearby': [],
    'ground_items': [{'id': 'minecraft:emerald', 'count': 1, 'distance': 1.1}],
}


def response(steps, action=None, target=None):
    first = steps[0] if steps else {'action': 'none', 'target_item': ''}
    return json.dumps({'reply': 'I will try that.', 'action': action or first['action'],
                       'target_item': first['target_item'] if target is None else target,
                       'steps': steps})


class MultiStepTest(unittest.TestCase):
    def test_pickup_then_give_two_steps(self):
        raw = response([
            {'action': 'pickup_nearby', 'target_item': 'minecraft:emerald'},
            {'action': 'give_item_to_owner', 'target_item': 'minecraft:emerald'},
        ])
        result = parse_agent_result(raw)
        self.assertEqual(len(result['steps']), 2)
        self.assertEqual(result['steps'][1]['action'], 'give_item_to_owner')

    def test_pickup_then_drop_allowed(self):
        result = parse_agent_result(response([
            {'action': 'pickup_nearby', 'target_item': 'minecraft:emerald'},
            {'action': 'drop_stored_item', 'target_item': 'minecraft:emerald'},
        ]))
        self.assertEqual(len(result['steps']), 2)

    def test_general_sequences_not_restricted_to_handoff(self):
        for actions in [
            [{'action': 'jump', 'target_item': ''},
             {'action': 'give_item_to_owner', 'target_item': 'minecraft:emerald'}],
            [{'action': 'pickup_nearby', 'target_item': 'minecraft:emerald'},
             {'action': 'equip_item', 'target_item': 'minecraft:emerald'},
             {'action': 'follow', 'target_item': ''}],
            [{'action': 'pickup_nearby', 'target_item': 'minecraft:emerald'}] * 8,
        ]:
            with self.subTest(actions=actions):
                result = parse_agent_result(response(actions))
                self.assertEqual(len(result['steps']), len(actions))

    def test_unbounded_or_ambiguous_plan_is_safe_noop(self):
        for actions in [
            [{'action': 'pickup_nearby', 'target_item': 'minecraft:emerald'}] * 9,
            [{'action': 'pickup_nearby', 'target_item': ''},
             {'action': 'equip_item', 'target_item': 'minecraft:emerald'}],
            [{'action': 'follow', 'target_item': 'minecraft:emerald'}],
        ]:
            with self.subTest(actions=actions):
                self.assertEqual(parse_agent_result(response(actions))['steps'], [])

    def test_plan_primary_mismatch_recovered_from_steps(self):
        raw = response([{'action': 'pickup_nearby', 'target_item': 'minecraft:emerald'}], action='stay')
        result = parse_agent_result(raw)
        self.assertEqual(result['action'], 'pickup_nearby')
        self.assertEqual(result['steps'][0]['target_item'], 'minecraft:emerald')

    def test_legacy_action_works_as_one_step(self):
        for name in ('follow', 'stay', 'jump'):
            output = parse_agent_result(json.dumps({'reply': 'Sure', 'action': name}))
            self.assertEqual(output['steps'], [{'action': name, 'target_item': ''}])
        output = parse_agent_result('{"reply":"Hi!","action":"none"}')
        self.assertEqual(output['steps'], [])

    def test_narrow_english_fallback_only_for_grounded_request(self):
        old_model = {'reply': 'I will pick it up.', 'action': 'pickup_nearby',
                     'target_item': 'minecraft:emerald',
                     'steps': [{'action':'pickup_nearby', 'target_item':'minecraft:emerald'}]}
        world = sanitize_observation(WORLD)
        converted = augment_simple_handoff('Yui, pick up that emerald and bring it to me', world, old_model)
        self.assertEqual([x['action'] for x in converted['steps']],
                         ['pickup_nearby', 'give_item_to_owner'])
        self.assertEqual(old_model['steps'][0]['action'], 'pickup_nearby')
        self.assertEqual(len(old_model['steps']), 1)  # input not mutated
        not_requested = augment_simple_handoff('Just pick up the emerald', world, old_model)
        self.assertEqual(len(not_requested['steps']), 1)
        missing = deepcopy(world)
        missing['ground_items'] = []
        self.assertEqual(len(augment_simple_handoff('Pick up emerald and bring it to me',
                                                   missing, old_model)['steps']), 1)

    def test_retry_after_pickup_gives_stored_item(self):
        world = sanitize_observation(WORLD)
        world['ground_items'] = []
        world['npc']['inventory'] = [{'slot': 0, 'id': 'minecraft:emerald', 'count': 1}]
        model = {'reply': 'I will pick that up.', 'action': 'pickup_nearby',
                 'target_item': 'minecraft:emerald',
                 'steps': [{'action': 'pickup_nearby', 'target_item': 'minecraft:emerald'}]}
        result = augment_simple_handoff('Pick up that emerald and bring it to me', world, model)
        self.assertEqual(result['action'], 'give_item_to_owner')
        self.assertEqual(len(result['steps']), 1)

    def test_different_item_or_negation_no_autoplan(self):
        world = sanitize_observation(WORLD)
        old = {'reply': 'Picking up', 'action': 'pickup_nearby', 'target_item': 'minecraft:diamond',
               'steps': [{'action': 'pickup_nearby', 'target_item': 'minecraft:diamond'}]}
        self.assertEqual(len(augment_simple_handoff('Pick up emerald and give it back', world, old)['steps']),1)
        old['target_item'] = 'minecraft:emerald'
        self.assertEqual(len(augment_simple_handoff("Don't pick up emerald and give it to me", world, old)['steps']),1)

    def test_http_agent_new_plan_with_mocked_llm(self):
        provider = Mock()
        provider.chat_structured.return_value = response([
            {'action': 'pickup_nearby', 'target_item': 'minecraft:emerald'},
            {'action': 'give_item_to_owner', 'target_item': 'minecraft:emerald'},
        ])
        service = ChatService(ROOT / 'character-packs','qwen3:8b',provider,SessionStore())
        server = ThreadingHTTPServer(('127.0.0.1',0),make_handler(service))
        thread = threading.Thread(target=server.serve_forever,daemon=True)
        thread.start()
        try:
            conn = http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=5)
            request = {'character_id':'yui','player_id':'player1','session_id':'npc1',
                       'message':'Yui, pick up that emerald and bring it to me','world':WORLD}
            conn.request('POST','/v1/agent',body=json.dumps(request),
                         headers={'Content-Type':'application/json'})
            resp=conn.getresponse()
            result=json.loads(resp.read())
            self.assertEqual(resp.status,200)
            self.assertEqual(len(result['steps']),2)
            self.assertEqual(result['steps'][1]['target_item'],'minecraft:emerald')
            conn.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_http_agent_fallback_to_plan_for_old_llm_answer(self):
        provider = Mock()
        provider.chat_structured.return_value = json.dumps({
            'reply':'I will pick that up.', 'action':'pickup_nearby',
            'target_item':'minecraft:emerald'})
        service=ChatService(ROOT/'character-packs','qwen3:8b',provider,SessionStore())
        result=service.agent('yui','player1','Pick up the emerald and bring it to me',
                             'npc1',WORLD)
        self.assertEqual([x['action'] for x in result['steps']],
                         ['pickup_nearby','give_item_to_owner'])


if __name__ == '__main__':
    unittest.main()
