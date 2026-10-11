"""Run from repo root: python -m unittest discover -s backend/tests -v"""
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))

from npcortex_backend.characters import CharacterError, load_character
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore

PACKS_DIR = ROOT / "character-packs"

class CharactersTest(unittest.TestCase):
    def test_load_yui(self):
        c = load_character(PACKS_DIR, "yui")
        self.assertEqual(c.character_id, "yui")
        self.assertIn("English", c.system_prompt)

    def test_prevents_directory_traversal(self):
        with self.assertRaises(CharacterError):
            load_character(PACKS_DIR, "../../secrets")

    def test_unknown_character_has_explicit_error(self):
        with self.assertRaises(CharacterError):
            load_character(PACKS_DIR, "unknown_npc")

class SessionTest(unittest.TestCase):
    def test_isolated_and_bounded_histories(self):
        sessions = SessionStore(max_messages=4)
        for i in range(3):
            sessions.add_exchange("player-a", "yui", f"question-{i}", f"reply-{i}")
        self.assertEqual(len(sessions.get("player-a", "yui")), 4)
        self.assertEqual(sessions.get("player-b", "yui"), [])
        self.assertEqual(sessions.get("player-a", "guide"), [])

class ServiceTest(unittest.TestCase):
    def test_chat_uses_character_and_memory(self):
        provider = Mock()
        provider.chat.side_effect = ["Let's explore!", "Yes, I remember."]
        svc = ChatService(PACKS_DIR, "qwen3:8b", provider, SessionStore())
        self.assertEqual(svc.chat("yui", "player-1", "Explore?")["reply"], "Let's explore!")
        svc.chat("yui", "player-1", "Remember that?")
        messages = provider.chat.call_args.args[0]
        self.assertEqual([m["role"] for m in messages], ["system", "user", "assistant", "user"])
        self.assertIn("Yui", messages[0]["content"])

if __name__ == "__main__":
    unittest.main()
