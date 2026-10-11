"""Regression: player-authored notes must never be presented as NPC autobiography."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend' / 'src'))
from npcortex_backend.memory import MemoryStore
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore

WORLD = {
    'dimension': 'minecraft:overworld', 'biome': 'minecraft:plains',
    'time': 'day', 'weather': 'clear',
    'player': {'distance': 2, 'health': 20, 'holding': 'minecraft:air'},
    'npc': {'following': False, 'health': 20, 'holding': 'minecraft:air',
            'inventory': [], 'holding_count': 0},
    'blocks': {'below': 'minecraft:grass_block', 'feet': 'minecraft:air', 'ahead': 'minecraft:air'},
    'nearby': [], 'ground_items': [],
}

class IdentityBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = MemoryStore(Path(self.temp.name) / 'memory.sqlite3')
        self.provider = Mock()
        self.service = ChatService(ROOT / 'character-packs', 'qwen3:8b',
                                   self.provider, SessionStore(), self.store)
        self.service.change_memory('player1', 'yui:npc1', 'add', 'My name is Kenji')

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def assert_identity(self, messages):
        system = messages[0]
        self.assertEqual(system['role'], 'system')
        content = system['content']
        self.assertIn('You are the NPC named "Yui"', content)
        self.assertIn('the PLAYER is named Kenji', content)
        self.assertIn('First-person words in a saved note', content)
        self.assertIn('PLAYER NOTE: "My name is Kenji"', content)
        self.assertNotIn('\n- My name is Kenji', content)

    def test_chat_notes_are_player_attributed(self):
        self.provider.chat.return_value = 'Hello, Kenji!'
        answer = self.service.chat('yui', 'player1', 'What is my name?', 'npc1')
        self.assertEqual(answer['character_name'], 'Yui')
        self.assert_identity(self.provider.chat.call_args.args[0])

    def test_agent_notes_are_player_attributed(self):
        self.provider.chat_structured.return_value = json.dumps({
            'reply': 'You are Kenji!', 'action': 'none', 'target_item': '', 'steps': []
        })
        answer = self.service.agent('yui', 'player1', 'Who am I?', 'npc1', WORLD)
        self.assertEqual(answer['action'], 'none')
        self.assert_identity(self.provider.chat_structured.call_args.args[0])

    def test_works_for_existing_sqlite_notes_after_restart(self):
        self.store.close()
        self.store = MemoryStore(Path(self.temp.name) / 'memory.sqlite3')
        self.service.memory = self.store
        self.provider.chat.return_value = 'Welcome back!'
        self.service.chat('yui', 'player1', 'Who are you?', 'npc1')
        self.assert_identity(self.provider.chat.call_args.args[0])

    def test_clear_chat_history_only_for_selected_npc(self):
        self.store.record('player1', 'yui:npc1', 'yui', 'My name is Yui!', 'Okay!')
        self.store.record('player1', 'yui:npc2', 'yui', 'Other NPC chat', 'Hello!')
        self.service.change_memory('player1', 'yui:npc1', 'clear_history')
        self.assertEqual(self.store.history('player1', 'yui:npc1'), [])
        self.assertEqual(len(self.store.history('player1', 'yui:npc2')), 2)
        self.assertEqual(self.store.snapshot('player1', 'yui:npc1')['notes'][0]['text'],
                         'My name is Kenji')
        self.assertEqual(self.store.snapshot('player1', 'yui:npc1')['chats'], 1)

    def test_no_notes_still_fix_role_boundary(self):
        self.provider.chat.return_value = 'I am Yui.'
        self.service.chat('yui', 'someone-else', 'What is your name?', 'npc2')
        text = self.provider.chat.call_args.args[0][0]['content']
        self.assertIn('You are the NPC named "Yui"', text)
        self.assertNotIn('PLAYER NOTE:', text)

if __name__ == '__main__':
    unittest.main()
