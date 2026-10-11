"""After-action speech: factual outcomes, NPC identity and HTTP contract."""
import http.client
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend' / 'src'))
from npcortex_backend.memory import MemoryStore
from npcortex_backend.service import ChatService
from npcortex_backend.server import make_handler
from npcortex_backend.session import SessionStore


class ActionSpeechTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.memory = MemoryStore(Path(self.temp.name) / 'npcortex.sqlite3')
        self.provider = Mock()
        self.service = ChatService(ROOT / 'character-packs', 'qwen3:8b',
                                   self.provider, SessionStore(), self.memory)

    def tearDown(self):
        self.memory.close()
        self.temp.cleanup()

    def test_follow_speaks_in_character_after_confirmation(self):
        self.provider.chat.return_value = "Sure! I'll follow you!"
        result = self.service.action_reply('yui', 'p1', 'Yui, follow me!', 'npc1',
                                           'follow', '[Action confirmed: following owner.]')
        self.assertEqual(result['reply'], "Sure! I'll follow you!")
        messages = self.provider.chat.call_args.args[0]
        self.assertIn('Yui', messages[0]['content'])
        self.assertIn('ACTION REACTION ONLY', messages[0]['content'])
        self.assertIn('following owner', messages[-1]['content'])
        self.assertEqual(self.memory.history('p1', 'yui:npc1')[-1]['content'], result['reply'])
        self.assertEqual(self.memory.snapshot('p1', 'yui:npc1')['chats'], 1)
        self.assertEqual(self.memory.snapshot('p1', 'yui:npc2')['chats'], 0)

    def test_started_action_speech_does_not_claim_completion(self):
        self.provider.chat.return_value = "Okay, I'll try to get over there."
        self.service.action_reply('yui', 'p1', 'Come here', 'npc1',
                                  'move_to_player', '[Action started: moving toward owner.]')
        prompt = self.provider.chat.call_args.args[0][0]['content']
        self.assertIn('NEVER say it completed', prompt)

    def test_player_note_does_not_change_npc_identity(self):
        self.memory.add_note('p1', 'yui:npc1', 'My name is Kenji')
        self.provider.chat.return_value = 'Coming!'
        self.service.action_reply('yui', 'p1', 'Wait for me here', 'npc1',
                                  'stay', '[Action confirmed: staying here.]')
        system = self.provider.chat.call_args.args[0][0]['content']
        self.assertIn('NPC named "Yui"', system)
        self.assertIn('PLAYER NOTE: "My name is Kenji"', system)

    def test_invalid_action_or_non_game_status_rejected(self):
        for action, result in [('run_command', '[Action confirmed: fake]'),
                               ('follow', 'I claim success')]:
            with self.subTest(action=action, result=result):
                with self.assertRaises(ValueError):
                    self.service.action_reply('yui', 'p1', 'command', 'npc1', action, result)
        self.provider.chat.assert_not_called()

    def test_action_plan_does_not_duplicate_memory_record(self):
        self.provider.chat.return_value = 'Working on it.'
        self.service.action_reply('yui', 'p1', 'Drop emerald and follow me', 'npc1',
                                  'plan', '[Step 1/2] [Action confirmed: drop]\n[Plan complete: 2/2 steps confirmed.]')
        self.assertEqual(self.memory.snapshot('p1', 'yui:npc1')['chats'], 1)

    def test_http_endpoint_and_rejections(self):
        self.provider.chat.return_value = "Alright, I'll stay."
        httpd = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(self.service))
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        try:
            connection = http.client.HTTPConnection('127.0.0.1', httpd.server_port, timeout=5)
            base = {'character_id': 'yui', 'player_id': 'p1', 'session_id': 'npc1',
                    'message': 'Wait for me here', 'action': 'stay',
                    'result': '[Action confirmed: staying here.]'}
            def post(data):
                connection.request('POST', '/v1/action-reply', json.dumps(data),
                                   {'Content-Type': 'application/json'})
                response = connection.getresponse()
                return response.status, json.loads(response.read())
            code, payload = post(base)
            self.assertEqual(code, 200)
            self.assertEqual(payload['reply'], "Alright, I'll stay.")
            code, _ = post(dict(base, action='execute_system_command'))
            self.assertEqual(code, 400)
            code, _ = post(dict(base, session_id=None))
            self.assertEqual(code, 400)
            connection.close()
        finally:
            httpd.shutdown()
            httpd.server_close()
            thread.join(timeout=2)


if __name__ == '__main__':
    unittest.main()
