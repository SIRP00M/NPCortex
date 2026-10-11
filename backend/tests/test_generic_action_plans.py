"""v0.5.4: generic bounded multi-step plans, not handoff-specific code."""
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
from npcortex_backend.agent import AGENT_POLICY, MAX_PLAN_STEPS, parse_agent_result, _validated_steps
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.server import make_handler

SWORD = 'minecraft:iron_sword'
STEPS = [
    {'action': 'pickup_nearby', 'target_item': SWORD},
    {'action': 'equip_item', 'target_item': SWORD},
    {'action': 'follow', 'target_item': ''},
]
WORLD = {
    'dimension': 'minecraft:overworld', 'biome': 'minecraft:plains',
    'time': 'day', 'weather': 'clear',
    'player': {'distance': 2.0, 'health': 20, 'holding': 'minecraft:air'},
    'npc': {'following': False, 'health': 20, 'holding': 'minecraft:air',
            'inventory': [], 'holding_count': 0},
    'blocks': {'below': 'minecraft:grass_block', 'feet': 'minecraft:air', 'ahead': 'minecraft:air'},
    'nearby': [], 'ground_items': [{'id': SWORD, 'count': 1, 'distance': 1}],
}


def answer(steps):
    first = steps[0] if steps else {'action': 'none', 'target_item': ''}
    return json.dumps({'reply': 'I will give that a try!',
                       'action': first['action'], 'target_item': first['target_item'],
                       'steps': steps})


class GenericPlanTest(unittest.TestCase):
    def test_pickup_equip_follow(self):
        result = parse_agent_result(answer(STEPS))
        self.assertEqual(result['steps'], STEPS)
        self.assertEqual(result['action'], 'pickup_nearby')

    def test_take_from_hand_equip_follow(self):
        steps = [
            {'action': 'take_offered_item', 'target_item': SWORD},
            {'action': 'equip_item', 'target_item': SWORD},
            {'action': 'follow', 'target_item': ''},
        ]
        self.assertEqual(parse_agent_result(answer(steps))['steps'], steps)

    def test_move_then_jump_then_follow(self):
        steps = [
            {'action': 'move_to_player', 'target_item': ''},
            {'action': 'jump', 'target_item': ''},
            {'action': 'follow', 'target_item': ''},
        ]
        self.assertEqual(parse_agent_result(answer(steps))['steps'], steps)

    def test_max_8_steps_and_reject_9(self):
        one = {'action': 'look_at_player', 'target_item': ''}
        self.assertEqual(MAX_PLAN_STEPS, 8)
        self.assertEqual(len(parse_agent_result(answer([one] * 8))['steps']), 8)
        self.assertEqual(parse_agent_result(answer([one] * 9))['steps'], [])

    def test_unknown_action_and_dangerous_item_target_blocked(self):
        self.assertIsNone(_validated_steps([{'action': 'run_command', 'target_item': ''}]))
        self.assertIsNone(_validated_steps([{'action': 'follow', 'target_item': SWORD}]))
        self.assertIsNone(_validated_steps([{'action': 'pickup_nearby', 'target_item': ''},
                                            {'action': 'equip_item', 'target_item': SWORD}]))

    def test_read_only_observation_prompt(self):
        self.assertIn('NEVER claim', AGENT_POLICY)
        self.assertIn('ordered array', AGENT_POLICY)
        self.assertIn('Each step', AGENT_POLICY)

    def test_http_agent_returns_3_step_plan(self):
        provider = Mock()
        provider.chat_structured.return_value = answer(STEPS)
        service = ChatService(ROOT / 'character-packs', 'qwen3:8b', provider, SessionStore())
        server = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(service))
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        try:
            conn = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
            conn.request('POST', '/v1/agent', body=json.dumps({
                'character_id': 'yui', 'player_id': 'player1', 'session_id': 'npc1',
                'message': 'Pick up that sword, equip it and follow me', 'world': WORLD,
            }), headers={'Content-Type': 'application/json'})
            res = conn.getresponse()
            self.assertEqual(res.status, 200)
            self.assertEqual(json.loads(res.read())['steps'], STEPS)
        finally:
            conn.close()
            server.shutdown()
            server.server_close()
            t.join(timeout=2)

    def test_java_catalog_and_runner_have_matching_limits(self):
        java = ROOT/'forge-mod/src/main/java/io/github/sirp00m/npcortex'
        bridge = (java/'ChatBridge.java').read_text()
        runner = (java/'ActionPlanRunner.java').read_text()
        actions = (java/'ActionCatalog.java').read_text()
        self.assertIn('MAX_PLAN_STEPS = 8;', bridge)
        self.assertIn('ActionPlanRunner::tick', (java/'NPCortex.java').read_text())
        for action in {step['action'] for step in STEPS}:
            self.assertIn('"' + action + '"', actions)
        self.assertIn('TIMEOUT_TICKS', runner)
        self.assertIn('ActionPlanRunner.begin', (java/'network/DialogueRequestPacket.java').read_text())


if __name__ == '__main__':
    unittest.main()
