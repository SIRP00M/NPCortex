"""Phase 5: user-configurable profiles and per-instance conversation isolation."""
import json
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))

from npcortex_backend.characters import CharacterError, load_character, list_characters
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore


class Phase5CharacterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        folder = self.root / "forest_guard"
        folder.mkdir()
        self.pack = folder / "character.json"
        self.payload = {
            "id": "forest_guard", "name": "Forest Guard", "language": "en",
            "system_prompt": "You are the Forest Guard.",
            "appearance": {"skin": "forest_guard", "model": "slim"},
        }
        self.pack.write_text(json.dumps(self.payload), encoding="utf8")

    def tearDown(self):
        self.tmp.cleanup()

    def test_profile_has_skin_without_leaking_system_prompt(self):
        char = load_character(self.root, "forest_guard")
        self.assertEqual(char.skin_model, "slim")
        meta = char.public_profile()
        self.assertEqual(meta["name"], "Forest Guard")
        self.assertEqual(meta["appearance"], {"skin": "forest_guard", "model": "slim"})
        self.assertNotIn("system_prompt", meta)

    def test_reject_skin_path_escape(self):
        self.payload["appearance"]["skin"] = "../../secrets"
        self.pack.write_text(json.dumps(self.payload), encoding="utf8")
        with self.assertRaises(CharacterError):
            load_character(self.root, "forest_guard")

    def test_reject_unknown_model(self):
        self.payload["appearance"]["model"] = "malicious"
        self.pack.write_text(json.dumps(self.payload), encoding="utf8")
        with self.assertRaises(CharacterError):
            load_character(self.root, "forest_guard")

    def test_list_ignores_invalid_packs(self):
        (self.root / "invalid_pack").mkdir()
        found = list_characters(self.root)
        self.assertEqual([item["id"] for item in found], ["forest_guard"])

    def test_different_npcs_do_not_share_history(self):
        provider = Mock()
        provider.chat.side_effect = ["Hello A", "Hello B", "I remember A"]
        service = ChatService(self.root, "qwen3:8b", provider, SessionStore())
        service.chat("forest_guard", "player-a", "Hi", "entity-a")
        service.chat("forest_guard", "player-a", "Hi", "entity-b")
        second_messages = provider.chat.call_args.args[0]
        self.assertEqual([m["role"] for m in second_messages], ["system", "user"])
        service.chat("forest_guard", "player-a", "Again", "entity-a")
        third_messages = provider.chat.call_args.args[0]
        self.assertEqual([m["role"] for m in third_messages], ["system", "user", "assistant", "user"])
        self.assertEqual(third_messages[2]["content"], "Hello A")

    def test_backwards_compatible_pack_without_appearance(self):
        self.payload.pop("appearance")
        self.pack.write_text(json.dumps(self.payload), encoding="utf8")
        c = load_character(self.root, "forest_guard")
        self.assertEqual((c.skin_id, c.skin_model), ("forest_guard", "wide"))

if __name__ == "__main__":
    unittest.main()
