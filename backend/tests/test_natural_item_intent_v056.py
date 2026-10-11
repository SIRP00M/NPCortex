"""English item-intent regressions: conservative, grounded, one-step rewrites."""
import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))
from npcortex_backend.agent import reconcile_single_item_intent, sanitize_observation
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore


def scene(held="minecraft:iron_sword", bag=("minecraft:emerald",),
          ground=("minecraft:diamond",), player_held="minecraft:stone_axe"):
    return sanitize_observation({
        "dimension": "minecraft:overworld", "biome": "minecraft:plains",
        "time": "day", "weather": "clear",
        "player": {"distance": 2, "health": 20, "holding": player_held},
        "npc": {"following": True, "health": 20, "holding": held,
                "inventory": [{"slot": i, "id": item, "count": 1} for i, item in enumerate(bag)]},
        "blocks": {"below": "minecraft:grass_block", "feet": "minecraft:air", "ahead": "minecraft:air"},
        "nearby": [], "ground_items": [{"id": item, "count": 1, "distance": 1} for item in ground],
    })


class NaturalItemIntentTests(unittest.TestCase):
    def setUp(self):
        self.result = {"reply": "Here you go!", "action": "none", "target_item": "", "steps": []}
        self.world = scene()

    def check_action(self, sentence, action, item="", world=None):
        parsed = reconcile_single_item_intent(sentence, self.world if world is None else world,
                                              copy.deepcopy(self.result), "Yui")
        self.assertEqual(parsed["steps"], [{"action": action, "target_item": item}], sentence)
        self.assertNotEqual(parsed["reply"], "Here you go!")

    def test_pickup_synonyms(self):
        for phrase in ("Yui, pick up the diamond", "Hey Yui, grab that diamond!",
                       "Yui, could you please collect the diamond from the ground?"):
            with self.subTest(phrase=phrase):
                self.check_action(phrase, "pickup_nearby", "minecraft:diamond")

    def test_equipment(self):
        self.check_action("Yui, equip the emerald", "equip_item", "minecraft:emerald")
        self.check_action("Please hold the emerald", "equip_item", "minecraft:emerald")
        self.check_action("Yui, wield the iron sword", "equip_item", "minecraft:iron_sword")

    def test_transfers(self):
        self.check_action("Yui, give me one emerald", "give_item_to_owner", "minecraft:emerald")
        self.check_action("Yui, pass me the emerald", "give_item_to_owner", "minecraft:emerald")
        self.check_action("Yui, take the stone axe from my hand", "take_offered_item", "minecraft:stone_axe")
        self.check_action("Yui, accept my axe from my hand", "take_offered_item", "minecraft:stone_axe")

    def test_drop_source(self):
        self.check_action("Yui, drop the iron sword", "drop_held_item")
        self.check_action("Yui, toss down your emerald", "drop_stored_item", "minecraft:emerald")

    def test_modded_id(self):
        custom = scene(held="tacz:modded_rifle", bag=(), ground=())
        self.check_action("Yui, drop the modded rifle", "drop_held_item", world=custom)

    def test_multi_step_does_not_override(self):
        for phrase in ("Yui, pick up the diamond and give it back to me",
                       "Yui, grab the diamond, then follow me"):
            self.assertEqual(reconcile_single_item_intent(phrase, self.world, self.result, "Yui"),
                             self.result)
        multi = {"reply":"ok", "action":"pickup_nearby", "target_item":"minecraft:diamond",
                 "steps":[{"action":"pickup_nearby","target_item":"minecraft:diamond"},
                          {"action":"follow","target_item":""}]}
        self.assertEqual(reconcile_single_item_intent("Yui, pick up the diamond", self.world, multi, "Yui"), multi)

    def test_questions_negations_quotes_are_not_commands(self):
        for phrase in ("Yui, don't drop the sword", "Yui, what does drop the sword mean?",
                       "I told you to 'drop the sword' yesterday", "Yui, would you drop the sword and run?",
                       "Yui, never give the emerald to me"):
            with self.subTest(phrase=phrase):
                self.assertEqual(reconcile_single_item_intent(phrase, self.world, self.result, "Yui"), self.result)

    def test_ambiguous_item_must_ask(self):
        custom = scene(held="minecraft:iron_sword", bag=("minecraft:diamond_sword",), ground=())
        actual = reconcile_single_item_intent("Yui, drop the sword", custom, self.result, "Yui")
        self.assertEqual(actual["action"], "none")
        self.assertIn("Which item", actual["reply"])

    def test_absent_item_does_not_hallucinate(self):
        self.assertEqual(reconcile_single_item_intent("Yui, drop the netherite sword", self.world, self.result, "Yui"), self.result)

    def test_service_integration(self):
        mock = Mock()
        mock.chat_structured.return_value = json.dumps(self.result)
        service = ChatService(ROOT / "character-packs", "qwen3:8b", mock, SessionStore())
        result = service.agent("yui", "someone", "Yui, please drop the iron sword", "yui-uuid", scene())
        self.assertEqual(result["action"], "drop_held_item")


if __name__ == "__main__":
    unittest.main()
