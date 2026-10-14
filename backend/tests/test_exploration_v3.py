import copy
import unittest
from unittest.mock import Mock
from test_general_material_brain import with_materials
from test_agentic_brain_v085 import ROOT
from npcortex_backend.brain import (validate_state, validate_brain_choice, eligible_tool_refs,
                                    allowed_brain_choices, FRONTIER_HORIZONTAL_HORIZON)
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore


def extended():
    s=with_materials()
    s['skills']['version']=3
    s['skills']['catalog'].extend([
        {'id':'explore','target':'waypoint','description':'Inspect observed frontier'},
        {'id':'return_to_work','target':'none','description':'Retain structures and return'},
    ])
    s['frontiers']=[{'ref':'e0','dx':8,'dy':1,'dz':0}]
    s['access']={'temporary_blocks':90,'borrowed_goal_blocks':8,'last_search':'PARTIAL','explored_regions':70}
    s['scaffold']=80;s['owner_distance']=5000;s['calls']=1000
    s['targets'][0]['dx']=4000;s['targets'][0]['dy']=80
    s['material_targets'][0]['dx']=10
    s['skills']['choices']+=['explore:e0','return_to_work']
    return s


class ExplorationV3Tests(unittest.TestCase):
    def test_harvest_no_path_recovery_guidance_matches_skill_contract(self):
        from npcortex_backend.brain import BRAIN_POLICY
        self.assertIn('descend during HARVEST', BRAIN_POLICY)
        self.assertIn('re-observe tree.remaining', BRAIN_POLICY)
        self.assertIn('blocked access approach', BRAIN_POLICY)


    def test_extended_distance_height_and_lifetime_calls(self):
        s=validate_state(extended())
        self.assertIn('explore:e0',allowed_brain_choices(s))
        self.assertEqual(validate_brain_choice({'choice':'explore:e0','reason':'search next region'},s)['target'],'e0')

    def test_material_prompt_refs_do_not_raise_keyerror(self):
        s=validate_state(extended());refs=eligible_tool_refs(s)
        self.assertEqual(refs['approach_material'],['m0'])
        self.assertEqual(refs['explore'],['e0'])

    def test_real_service_constructs_v3_enum_and_translates(self):
        for choice,expected in [('explore:e0','explore'),('approach_material:m0','approach_material')]:
            provider=Mock(spec=['chat_structured'])
            provider.chat_structured.return_value={'choice':choice,'reason':'grounded action'}
            service=ChatService(ROOT/'character-packs','qwen3:8b',provider,SessionStore())
            reply=service.brain_step('yui','owner','Finish work','npc',extended())
            self.assertEqual(reply['tool'],expected)
            self.assertIn(choice,provider.chat_structured.call_args.args[1]['properties']['choice']['enum'])

    def test_ref_kind_confusion_never_authorized(self):
        for choice in ['mine:e0','explore:m0','approach_material:e0','explore:e7','return_to_work:e0']:
            s=extended();s['skills']['choices'].append(choice)
            with self.subTest(choice=choice), self.assertRaises(ValueError):validate_state(s)

    def test_search_status_and_access_counts_checked(self):
        for key,value in [('borrowed_goal_blocks',91),('temporary_blocks',-1),('last_search','guaranteed'),('explored_regions',True)]:
            s=extended();s['access'][key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):validate_state(s)

    def test_java_walk_frontier_horizon_matches_python_validator(self):
        # Catch future Java navigation budget changes before they reach a live game.
        java = (ROOT / 'forge-mod/src/main/java/io/github/sirp00m/npcortex/Navigation3DAccess.java').read_text(encoding='utf-8')
        import re
        found = re.search(r'\},\s*start,\s*(\d+),\s*32,\s*1400\);', java)
        self.assertIsNotNone(found, 'Update test when the Java frontier search call changes')
        self.assertEqual(FRONTIER_HORIZONTAL_HORIZON, int(found.group(1)))

    def test_sprint2_fix4_real_java_12_block_frontier_reaches_qwen(self):
        # Navigation3DAccess.terrainRoutes uses radius=12 in X/Z, whereas
        # the previous backend rejected anything past +/-8 before Qwen ran.
        # Model stays mocked; this verifies first-turn request validation.
        observed = extended()
        observed.update(mined=0, calls=0, failures=0, same_failures=0,
                        stalls=0, scaffold=0, owner_distance=3.0)
        observed['frontiers'] = [
            {'ref': 'e0', 'dx': 12, 'dy': 0, 'dz': 0},
            {'ref': 'e1', 'dx': -11, 'dy': 1, 'dz': 12},
        ]
        observed['skills']['choices'].remove('explore:e0')
        observed['skills']['choices'].extend(['explore:e0', 'explore:e1'])
        validated = validate_state(observed)
        self.assertIn('explore:e0', allowed_brain_choices(validated))
        provider = Mock(spec=['chat_structured'])
        provider.chat_structured.return_value = {'choice': 'explore:e0', 'reason': 'check safe frontier'}
        service = ChatService(ROOT / 'character-packs', 'qwen3:8b', provider, SessionStore())
        reply = service.brain_step('yui', 'owner', 'Harvest entire oak', 'npc', observed)
        self.assertEqual(reply['tool'], 'explore')
        provider.chat_structured.assert_called_once()

    def test_frontier_outside_java_radius_rejected_with_field_name(self):
        for key, value in (('dx', 13), ('dx', -13), ('dz', 13), ('dz', -13)):
            observed = extended()
            observed['frontiers'][0][key] = value
            with self.subTest(key=key, value=value), self.assertRaisesRegex(
                    ValueError, rf'frontiers\[0\]\.{key}'):
                validate_state(observed)

    def test_frontiers_are_frozen_bounded_data(self):
        for key,value in [('dx',100),('dy',5),('ref','e9'),('dz',True)]:
            s=extended();s['frontiers'][0][key]=value
            with self.subTest(key=key), self.assertRaises(ValueError):validate_state(s)
        s=extended();s['frontiers'][0]['command']='/tp'
        with self.assertRaises(ValueError):validate_state(s)

    def test_unknown_missing_fields_rejected(self):
        s=extended();del s['access']
        with self.assertRaises(ValueError):validate_state(s)
        s=extended();s['unexpected']=1
        with self.assertRaises(ValueError):validate_state(s)

    def test_progress_watchdogs_remain(self):
        for key,value in [('paused',True),('failures',8),('stalls',5),('same_failures',3)]:
            s=extended();s[key]=value
            self.assertEqual(allowed_brain_choices(validate_state(s)),['pause'])

    def test_old_versions_do_not_accept_extended_payload(self):
        s=extended();s['skills']['version']=2
        with self.assertRaises(ValueError):validate_state(s)

    def test_nonfinite_distance_rejected(self):
        for value in [float('inf'),float('nan'),-1,True]:
            s=extended();s['owner_distance']=value
            with self.subTest(value=value),self.assertRaises(ValueError):validate_state(s)
