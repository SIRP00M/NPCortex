"""World-interaction contract: validated, bounded, opt-in and server executed."""
import json
import unittest
from pathlib import Path
from unittest.mock import Mock
import http.client
import threading
from http.server import ThreadingHTTPServer
from npcortex_backend.service import ChatService
from npcortex_backend.server import make_handler
from npcortex_backend.session import SessionStore
from npcortex_backend.agent import (parse_agent_result, sanitize_observation,
                                    _validated_steps, AGENT_POLICY, MAX_PLAN_STEPS)

ROOT = Path(__file__).resolve().parents[2]
J = ROOT / 'forge-mod/src/main/java/io/github/sirp00m/npcortex'


def response(steps):
    first = steps[0] if steps else {"action": "none", "target_item": ""}
    return json.dumps({"reply": "I'll try that safely.", "action": first['action'],
                       "target_item": first['target_item'], "steps": steps})


class WorldInteractionTests(unittest.TestCase):
    def test_four_step_world_plan(self):
        actions = [("mine_nearby", "minecraft:oak_log"),
                   ("pickup_nearby", "minecraft:oak_log"),
                   ("craft_item", "minecraft:oak_planks"),
                   ("place_block", "minecraft:oak_planks")]
        steps = [{"action": a, "target_item": i} for a, i in actions]
        self.assertEqual(parse_agent_result(response(steps))['steps'], steps)
        self.assertEqual(MAX_PLAN_STEPS, 8)

    def test_all_new_actions_require_exact_target(self):
        for action in ("mine_nearby", "place_block", "craft_item"):
            self.assertIsNone(_validated_steps([{"action": action, "target_item": ""}]))
            self.assertIsNone(_validated_steps([{"action": action, "target_item": "../../etc/passwd"}]))
            self.assertIsNotNone(_validated_steps([{"action": action, "target_item": "minecraft:oak_log"}]))

    def test_never_accept_extra_permissions_or_coordinates(self):
        result = json.loads(response([{"action":"mine_nearby","target_item":"minecraft:dirt"}]))
        result['steps'][0]['x'] = 500
        self.assertEqual(parse_agent_result(json.dumps(result))['steps'], [])

    def test_bounded_observations(self):
        world = {'player':{}, 'npc':{}, 'blocks': {}, 'nearby':[], 'ground_items':[],
                 'mineable_blocks': ['minecraft:oak_log', 'minecraft:dirt'],
                 'allowed_placeable_blocks': ['minecraft:oak_planks'],
                 'supported_recipes': ['minecraft:stick']}
        obs = sanitize_observation(world)
        self.assertEqual(obs['mineable_blocks'], ['minecraft:oak_log', 'minecraft:dirt'])
        self.assertEqual(obs['supported_recipes'], ['minecraft:stick'])
        world['mineable_blocks'] = ['minecraft:dirt']*17
        with self.assertRaises(ValueError):
            sanitize_observation(world)
        world['mineable_blocks'] = ['bad id']
        with self.assertRaises(ValueError):
            sanitize_observation(world)

    def test_existing_world_without_new_keys_remains_compatible(self):
        world = {'player':{}, 'npc':{}, 'blocks': {}, 'nearby':[], 'ground_items':[]}
        obs = sanitize_observation(world)
        self.assertEqual(obs['mineable_blocks'], [])

    def test_http_agent_four_step_chain(self):
        steps = [{"action": a, "target_item": t} for a, t in (
            ("mine_nearby", "minecraft:oak_log"),
            ("pickup_nearby", "minecraft:oak_log"),
            ("craft_item", "minecraft:oak_planks"),
            ("place_block", "minecraft:oak_planks"))]
        provider = Mock()
        provider.chat_structured.return_value = response(steps)
        service = ChatService(ROOT/'character-packs', 'qwen3:8b', provider, SessionStore())
        server = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(service))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        body = {
            "character_id": "yui", "player_id": "player-test", "session_id": "npc-1",
            "message": "Yui, mine that oak log, collect it, craft planks and place one.",
            "world": {'player': {}, 'npc': {}, 'blocks': {}, 'nearby': [],
                      'ground_items': [], 'mineable_blocks': ['minecraft:oak_log'],
                      'allowed_placeable_blocks': ['minecraft:oak_planks'],
                      'supported_recipes': ['minecraft:oak_planks']},
        }
        try:
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
            connection.request('POST', '/v1/agent', json.dumps(body),
                               {'Content-Type': 'application/json'})
            result = connection.getresponse()
            self.assertEqual(result.status, 200)
            self.assertEqual(json.loads(result.read())['steps'], steps)
            connection.close()
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

    def test_server_authority_and_singleplayer_opt_in(self):
        java = (J/'WorldBlockActions.java').read_text()
        self.assertIn('WORLD_EDITS_ENABLED.get()', java)
        self.assertIn('.isSingleplayer()', java)
        self.assertIn('hasPermissions(2)', java)
        self.assertIn('level.isLoaded', java)
        self.assertIn('WorldInteractionPolicy.breakable', java)
        self.assertIn('WorldInteractionPolicy.placeable', java)
        self.assertIn('getBlockEntity(p) != null', java)
        self.assertIn('level.getEntities((net.minecraft.world.entity.Entity) null,', java)
        self.assertIn('new AABB(candidate)', java)

    def test_java_and_python_catalog_agree(self):
        cat = (J/'ActionCatalog.java').read_text()
        actions = (J/'WorldActions.java').read_text()
        for action in ('mine_nearby', 'place_block', 'craft_item'):
            self.assertIn('"'+action+'"', cat)
            self.assertIn('case "'+action+'"', actions)
        self.assertIn('mine_nearby', AGENT_POLICY)
        self.assertIn('place_block', AGENT_POLICY)

if __name__ == '__main__':
    unittest.main()
