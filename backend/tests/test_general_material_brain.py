"""The AI can select grounded reusable resource and 3D loot skills, never coordinates."""
import copy
import unittest
from test_runtime_registry_v2 import registered
from npcortex_backend.brain import allowed_brain_choices, validate_brain_choice, validate_state


def with_materials():
    state = registered()
    state["material_targets"] = [{
        "ref": "m0", "id": "minecraft:dirt", "dx": 2, "dy": -1, "dz": 1,
        "reachable": False, "ground_stand_possible": True,
    }]
    state["skills"]["catalog"].extend([
        {"id": "approach_material", "target": "item", "description": "Find safe soil stand"},
        {"id": "mine_material", "target": "item", "description": "Excavate verified soil"},
        {"id": "navigate_3d_drop", "target": "drop", "description": "3D reachable item"},
    ])
    state["skills"]["choices"].extend(["approach_material:m0", "navigate_3d_drop:d0"])
    return state


class GeneralMaterialBrainTests(unittest.TestCase):
    def test_material_skill_is_accepted_without_a_python_skill_rule(self):
        s = validate_state(with_materials())
        self.assertEqual(validate_brain_choice({"choice": "approach_material:m0", "reason": "collect dirt"}, s),
                         {"tool": "approach_material", "target": "m0", "reason": "collect dirt"})
        self.assertIn("navigate_3d_drop:d0", allowed_brain_choices(s))

    def test_mining_material_only_if_offered(self):
        s = with_materials()
        s["material_targets"][0]["reachable"] = True
        s["skills"]["choices"].remove("approach_material:m0")
        s["skills"]["choices"].append("mine_material:m0")
        valid = validate_state(s)
        self.assertEqual(validate_brain_choice({"choice": "mine_material:m0", "reason": "need support"}, valid)["tool"],
                         "mine_material")
        with self.assertRaises(ValueError):
            validate_brain_choice({"choice": "mine_material:m1", "reason": "invented"}, valid)

    def test_material_ref_cannot_be_used_as_goal_log(self):
        s=with_materials();s["skills"]["choices"].append("mine:m0")
        with self.assertRaisesRegex(ValueError,"Ungrounded"):
            validate_state(s)

    def test_goal_ref_cannot_be_used_as_material_source(self):
        s=with_materials();s["skills"]["choices"].append("mine_material:b0")
        with self.assertRaisesRegex(ValueError,"Ungrounded"):
            validate_state(s)

    def test_unknown_bad_types_or_nonsoil_rejected(self):
        samples=[]
        for key,value in [("id","minecraft:diamond_ore"),("reachable",1),
                          ("dx",50),("ref","m99")]:
            s=with_materials();s["material_targets"][0][key]=value;samples.append(s)
        s=with_materials();s["material_targets"][0]["command"]="/setblock";samples.append(s)
        s=with_materials();s["material_targets"]={"m0": "minecraft:dirt"};samples.append(s)
        for s in samples:
            with self.subTest(s=s), self.assertRaises(ValueError):
                validate_state(s)

    def test_legacy_observation_and_v2_without_materials_still_allowed(self):
        self.assertNotIn("material_targets", validate_state(registered()))
        s=with_materials();s["skills"]["choices"].append("navigate_3d_drop:m0")
        with self.assertRaises(ValueError): validate_state(s)


if __name__ == "__main__": unittest.main()
