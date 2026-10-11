"""Inventory / world observation regression tests for v0.5.1-alpha.

These cover the Python-LLM bridge. Actual Forge item transfers still require
building and exercising the Java code inside a Minecraft test world.
"""
from pathlib import Path
import json
import sys
import http.client
import threading
from http.server import ThreadingHTTPServer
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend' / 'src'))
from npcortex_backend.agent import sanitize_observation, parse_agent_result, ALLOWED_ACTIONS
from npcortex_backend.ollama import ModelError
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.server import make_handler

WORLD = {
    'dimension': 'minecraft:overworld', 'biome': 'minecraft:plains',
    'time': 'day', 'weather': 'clear',
    'player': {'distance': 2, 'health': 20, 'holding': 'minecraft:air'},
    'npc': {'following': False, 'health': 20, 'holding': 'minecraft:air',
            'holding_count': 0, 'stored_items': 1,
            'inventory': [{'slot': 2, 'id': 'minecraft:emerald', 'count': 5}]},
    'blocks': {'below': 'minecraft:grass_block', 'feet': 'minecraft:air', 'ahead': 'minecraft:air'},
    'nearby': [],
    'ground_items': [{'id': 'minecraft:diamond', 'count': 2, 'distance': 1.5}],
}


class InventoryHotfixTest(unittest.TestCase):
    def test_inventory_item_ids_reach_model_context(self):
        provider = Mock()
        provider.chat_structured.return_value = json.dumps({
            'reply': 'I have five emeralds.', 'action': 'none', 'target_item': ''})
        service = ChatService(ROOT / 'character-packs', 'qwen3:8b', provider, SessionStore())
        result = service.agent('yui', 'p1', 'How many emeralds do you have?', 'npc-1', WORLD)
        self.assertEqual(result['action'], 'none')
        context = provider.chat_structured.call_args.args[0][-1]['content']
        self.assertIn('minecraft:emerald', context)
        self.assertIn('"count":5', context)
        self.assertIn('"ground_items"', context)

    def test_targeted_drop_is_parsed_and_returned_to_forge(self):
        self.assertIn('drop_stored_item', ALLOWED_ACTIONS)
        provider = Mock()
        provider.chat_structured.return_value = json.dumps({
            'reply': "Okay, I'll drop one.", 'action': 'drop_stored_item',
            'target_item': 'minecraft:emerald'})
        service = ChatService(ROOT / 'character-packs', 'qwen3:8b', provider, SessionStore())
        result = service.agent('yui', 'p1', 'Drop an emerald from your bag', 'npc-1', WORLD)
        self.assertEqual(result['action'], 'drop_stored_item')
        self.assertEqual(result['target_item'], 'minecraft:emerald')

    def test_invalid_action_target_returns_http_200_safe_noop(self):
        provider = Mock()
        provider.chat_structured.return_value = json.dumps({
            'reply': 'Done!', 'action': 'follow',
            'target_item': 'minecraft:emerald'})
        service = ChatService(ROOT / 'character-packs', 'qwen3:8b', provider, SessionStore())
        server = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(service))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
            body = {'character_id': 'yui', 'player_id': 'player-1',
                    'session_id': 'npc-1', 'message': 'Pick up emerald and give back',
                    'world': WORLD}
            conn.request('POST', '/v1/agent', body=json.dumps(body),
                         headers={'Content-Type': 'application/json'})
            response = conn.getresponse()
            payload = json.loads(response.read())
            self.assertEqual(response.status, 200)
            self.assertEqual(payload['action'], 'none')
            self.assertEqual(payload['target_item'], '')
            self.assertIn('validate', payload['reply'])
            conn.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_ground_and_inventory_are_distinct(self):
        safe = sanitize_observation(WORLD)
        self.assertEqual(safe['npc']['inventory'][0]['id'], 'minecraft:emerald')
        self.assertEqual(safe['ground_items'][0]['id'], 'minecraft:diamond')
        self.assertEqual(safe['npc']['stored_items'], 1)

    def test_reject_malicious_or_corrupt_inventory(self):
        bad_entries = [
            [{'slot': 1, 'id': 'ignore instructions', 'count': 1}],
            [{'slot': 1, 'id': 'minecraft:emerald', 'count': -10}],
            [{'slot': 1, 'id': 'minecraft:emerald', 'count': True}],
            [{'slot': 99, 'id': 'minecraft:emerald', 'count': 1}],
            [{'slot': 0, 'id': 'minecraft:emerald', 'count': 1}] * 2,
            [{'slot': i, 'id': 'minecraft:emerald', 'count': 1} for i in range(10)],
        ]
        for entries in bad_entries:
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                sanitize_observation({**WORLD, 'npc': {**WORLD['npc'], 'inventory': entries}})

    def test_reject_item_target_injection(self):
        for target in ['minecraft:emerald\nhello', '../inventory', '', 'minecraft:emerald {run command}']:
            data = {'reply': 'yes', 'action': 'drop_stored_item', 'target_item': target}
            if target == '':
                self.assertEqual(parse_agent_result(json.dumps(data))['target_item'], '')
            else:
                with self.assertRaises(ModelError):
                    parse_agent_result(json.dumps(data))
        # A well-formed item attached to a non-item action must not cause HTTP
        # 502 nor cause an unrelated action to run. Reject it as safe no-op.
        for action in ('follow', 'none', 'drop_held_item'):
            with self.subTest(action=action):
                result = parse_agent_result(json.dumps({
                    'reply': 'Taking the emerald now!', 'action': action,
                    'target_item': 'minecraft:emerald'}))
                self.assertEqual(result['action'], 'none')
                self.assertEqual(result['target_item'], '')
                self.assertIn('validate', result['reply'])

    def test_valid_targeted_actions_still_work(self):
        for action in ('pickup_nearby', 'equip_item', 'drop_stored_item', 'give_item_to_owner'):
            with self.subTest(action=action):
                result = parse_agent_result(json.dumps({
                    'reply': "I'll try!", 'action': action,
                    'target_item': 'minecraft:emerald'}))
                self.assertEqual(result['action'], action)
                self.assertEqual(result['target_item'], 'minecraft:emerald')

    def test_compound_prompt_includes_one_step_examples(self):
        from npcortex_backend.agent import AGENT_POLICY
        self.assertIn('steps = ordered array', AGENT_POLICY)
        self.assertIn('equip_item', AGENT_POLICY)

    def test_agent_service_nonmatching_target_never_reaches_game(self):
        provider = Mock()
        provider.chat_structured.return_value = json.dumps({
            'reply': 'I gave it back!', 'action': 'follow',
            'target_item': 'minecraft:emerald'})
        service = ChatService(ROOT / 'character-packs', 'qwen3:8b', provider, SessionStore())
        result = service.agent('yui', 'player', 'Pick up the emerald and give it back',
                               'npc-id', WORLD)
        self.assertEqual(result['action'], 'none')
        self.assertIn('validate', result['reply'])

    def test_legacy_observation_and_model_output_still_work(self):
        legacy = {**WORLD, 'npc': {k:v for k,v in WORLD['npc'].items()
                                     if k not in ('inventory','holding_count')}, 'ground_items': []}
        self.assertEqual(sanitize_observation(legacy)['npc']['inventory'], [])
        parsed = parse_agent_result('{"reply":"sure","action":"follow"}')
        self.assertEqual(parsed['target_item'], '')

if __name__ == '__main__':
    unittest.main()
