"""Equipment hand/bag intent regressions for v0.5.5-alpha."""
import copy
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.agent import (AGENT_POLICY, reconcile_explicit_held_drop,
                                     sanitize_observation)
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from unittest.mock import Mock

ITEM = "minecraft:iron_sword"
WORLD = {
    "dimension": "minecraft:overworld", "biome": "minecraft:plains",
    "time": "day", "weather": "clear",
    "player": {"distance": 2, "health": 20, "holding": "minecraft:air"},
    "npc": {"following": True, "health": 20, "holding": ITEM, "inventory": [], "holding_count": 1},
    "blocks": {"below": "minecraft:grass_block", "feet": "minecraft:air", "ahead": "minecraft:air"},
    "nearby": [], "ground_items": [],
}


class EquippedItemIntentTests(unittest.TestCase):
    def setUp(self):
        self.snapshot = sanitize_observation(WORLD)
        self.proposed = {"reply": "Here you go!", "action": "give_item_to_owner",
                         "target_item": ITEM,
                         "steps": [{"action": "give_item_to_owner", "target_item": ITEM}]}

    def test_drop_named_equipped_sword(self):
        result = reconcile_explicit_held_drop("Yui, drop the sword!", self.snapshot, self.proposed)
        self.assertEqual(result["steps"], [{"action": "drop_held_item", "target_item": ""}])
        self.assertNotIn("Here you go", result["reply"])

    def test_drop_exact_full_item_name(self):
        result = reconcile_explicit_held_drop("Please drop the iron sword.", self.snapshot, self.proposed)
        self.assertEqual(result["action"], "drop_held_item")

    def test_not_when_user_says_give(self):
        self.assertEqual(reconcile_explicit_held_drop("Give me the iron sword", self.snapshot, self.proposed), self.proposed)

    def test_no_swap_of_different_equipped_item(self):
        self.assertEqual(reconcile_explicit_held_drop("Drop the axe", self.snapshot, self.proposed), self.proposed)

    def test_dont_drop_is_not_action(self):
        self.assertEqual(reconcile_explicit_held_drop("Don't drop the sword", self.snapshot, self.proposed), self.proposed)

    def test_no_rewrite_of_multi_step(self):
        multi = copy.deepcopy(self.proposed)
        multi["steps"].append({"action": "follow", "target_item": ""})
        self.assertEqual(reconcile_explicit_held_drop("Drop the sword", self.snapshot, multi), multi)

    def test_backend_roundtrip_with_incorrect_model_action(self):
        provider = Mock()
        provider.chat_structured.return_value = json.dumps(self.proposed)
        service = ChatService(ROOT / "character-packs", "qwen3:8b", provider, SessionStore())
        result = service.agent("yui", "player", "Drop the sword", "npc", WORLD)
        self.assertEqual(result["steps"], [{"action": "drop_held_item", "target_item": ""}])

    def test_observation_keeps_hand_and_empty_bag_distinct(self):
        self.assertEqual(self.snapshot["npc"]["holding"], ITEM)
        self.assertEqual(self.snapshot["npc"]["inventory"], [])

    def test_agent_policy_uses_equipped_item_and_stay(self):
        self.assertIn("npc.holding", AGENT_POLICY)
        self.assertIn('"stay here"', AGENT_POLICY)


if __name__ == "__main__":
    unittest.main()
