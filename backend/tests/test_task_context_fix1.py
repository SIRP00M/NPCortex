import copy
import json
import unittest
from unittest.mock import Mock

from test_exploration_v3 import extended
from test_agentic_brain_v085 import ROOT
from npcortex_backend.brain import validate_state, allowed_brain_choices
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore


def with_context():
    state = extended()
    state['task_context'] = {
        'work_anchor': {'dx': -40, 'dy': 0, 'dz': 8},
        'usable_blocks': 3, 'material_mined': 1, 'decisions_since_goal_progress': 9,
        'recent_actions': [{
            'skill': 'navigate_material', 'result': 'Reached soil; no material mined by walking.',
            'reason': 'Obtain support blocks', 'mined': 2, 'collected': 2, 'usable_blocks': 3,
        }],
    }
    state['material_targets'][0]['reachable'] = True
    state['skills']['choices'].remove('approach_material:m0')
    state['skills']['choices'].append('mine_material:m0')
    return state


class TaskContextFix1Tests(unittest.TestCase):
    def test_arrival_context_reaches_model_without_overriding_its_choice(self):
        # Verify the real service contract, not model quality (provider is mocked).
        for choice in ['mine_material:m0', 'return_to_work', 'explore:e0']:
            provider = Mock(spec=['chat_structured'])
            provider.chat_structured.return_value = {'choice': choice, 'reason': 'Revised plan'}
            service = ChatService(ROOT/'character-packs', 'qwen3:8b', provider, SessionStore())
            state = with_context()
            reply = service.brain_step('yui', 'owner', 'Finish harvesting', 'npc', state)
            self.assertEqual(reply['tool'], choice.split(':')[0])
            messages, schema = provider.chat_structured.call_args.args
            prompt = json.loads(messages[-1]['content'].removeprefix('READ-ONLY DATA: '))
            self.assertEqual(prompt['server_observation']['task_context'], state['task_context'])
            self.assertIn(choice, schema['properties']['choice']['enum'])

    def test_context_is_evidence_not_an_automatic_exploration_ban(self):
        state = with_context()
        state['task_context']['decisions_since_goal_progress'] = 100000
        self.assertIn('explore:e0', allowed_brain_choices(validate_state(state)))

    def test_old_v3_payload_remains_compatible(self):
        self.assertNotIn('task_context', validate_state(extended()))

    def test_context_cannot_smuggle_extra_instructions_or_bad_types(self):
        for key, value in [('usable_blocks', True), ('material_mined', -1),
                           ('decisions_since_goal_progress', -1), ('recent_actions', {})]:
            state = with_context(); state['task_context'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): validate_state(state)
        for anchor in [{'dx': 0, 'dy': 0, 'dz': 0, 'command': '/tp'},
                       {'dx': 60000001, 'dy': 0, 'dz': 0}, {'dx': False, 'dy': 0, 'dz': 0}]:
            state = with_context(); state['task_context']['work_anchor'] = anchor
            with self.assertRaises(ValueError): validate_state(state)

    def test_recent_results_are_bounded_and_not_executable_refs(self):
        for key, value in [('skill', '/tp'), ('result', 'x'*181), ('reason', 'x'*181),
                           ('mined', 193), ('usable_blocks', 1025)]:
            state = with_context(); state['task_context']['recent_actions'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): validate_state(state)
        state = with_context(); state['task_context']['recent_actions'] *= 5
        with self.assertRaises(ValueError): validate_state(state)
        state = with_context(); state['task_context']['recent_actions'][0]['skill'] = 'navigate_3d'
        validate_state(state)

    def test_near_maximum_observation_fits_http_transport(self):
        from npcortex_backend.server import MAX_BODY
        state = with_context()
        for category, prefix in [('targets', 'b'), ('drops', 'd')]:
            state[category] = [copy.deepcopy(state[category][0]) for _ in range(16)]
            for i, entry in enumerate(state[category]): entry['ref'] = f'{prefix}{i}'
        state['task_context']['recent_actions'] *= 4
        for entry in state['task_context']['recent_actions']:
            entry['reason'] = 'r'*180; entry['result'] = 's'*180
        validate_state(state)
        request = {'character_id': 'yui', 'player_id': 'p'*36, 'session_id': 'n'*36,
                   'message': 'm'*400, 'world': state}
        self.assertLess(len(json.dumps(request).encode()), MAX_BODY)
