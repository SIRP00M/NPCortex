"""Registry v2 transport tests: options come from Forge, not a second Python planner."""
import copy
import unittest
from unittest.mock import Mock
from test_agentic_brain_v085 import state, ROOT
from npcortex_backend.brain import validate_state, validate_brain_choice, allowed_brain_choices, eligible_tool_refs
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore


def registered():
    s=state()
    s['skills']={'version':2,'catalog':[
        {'id':'pause','target':'none','description':'Preserve goal'},
        {'id':'navigate_3d','target':'block','description':'Walk, pillar and bridge to an observed block'},
        {'id':'mine','target':'block','description':'Mine observed block'},
        {'id':'approach_drop','target':'drop','description':'Approach a real entity'},
    ],'choices':['pause','navigate_3d:b1','mine:b0','approach_drop:d0']}
    s['failure_memory']=[{'attempt':0,'reason':'No path from previous pose','count':2}]
    return s


class RegistryV2Tests(unittest.TestCase):
    def test_nav_is_transported_without_python_skill_definition(self):
        s=validate_state(registered())
        self.assertEqual(validate_brain_choice({'choice':'navigate_3d:b1','reason':'high branch'},s),
                         {'tool':'navigate_3d','target':'b1','reason':'high branch'})
        self.assertEqual(eligible_tool_refs(s)['navigate_3d'],['b1'])

    def test_new_registered_skill_needs_no_python_planner_change(self):
        s=registered(); s['skills']['catalog'].append({'id':'inspect','target':'block','description':'Read block state'})
        s['skills']['choices'].append('inspect:b0')
        self.assertEqual(validate_brain_choice({'choice':'inspect:b0','reason':'observe'},validate_state(s))['tool'],'inspect')

    def test_only_exact_server_pairs_can_execute(self):
        s=validate_state(registered())
        for choice in ('mine:b1','navigate_3d:b0','approach_drop:b0','navigate_3d:b9','/give','pause:b0'):
            with self.subTest(choice=choice), self.assertRaises(ValueError):
                validate_brain_choice({'choice':choice,'reason':'x'},s)

    def test_server_suppressed_failure_is_not_readded_by_python(self):
        s=registered(); s['skills']['choices'].remove('mine:b0')
        self.assertNotIn('mine:b0',allowed_brain_choices(validate_state(s)))

    def test_exhaustion_and_pause_override_old_offers(self):
        for field,value in [('calls',80),('failures',8),('stalls',5),('same_failures',3),('paused',True)]:
            s=registered(); s[field]=value
            self.assertEqual(allowed_brain_choices(validate_state(s)),['pause'])

    def test_malformed_manifest_fails_closed(self):
        cases=[]
        for choice in ['missing:b0','mine:d0','mine:b9','pause:','navigate_3d']:
            s=registered(); s['skills']['choices'].append(choice); cases.append(s)
        s=registered(); s['skills']['choices'].remove('pause'); cases.append(s)
        s=registered(); s['skills']['catalog'].append(copy.deepcopy(s['skills']['catalog'][0])); cases.append(s)
        s=registered(); s['skills']['version']=True; cases.append(s)
        s=registered(); s['skills']['choices'].append('mine:b0'); cases.append(s)
        s=registered(); s['skills']['catalog'][0]['execute']='arbitrary code'; cases.append(s)
        s=registered(); s.pop('failure_memory'); cases.append(s)
        for s in cases:
            with self.subTest(s=s), self.assertRaises(ValueError): validate_state(s)

    def test_bounded_failure_history(self):
        s=registered(); s['failure_memory']*=9
        with self.assertRaises(ValueError): validate_state(s)
        s=registered(); s['failure_memory'][0]['reason']='x'*181
        with self.assertRaises(ValueError): validate_state(s)

    def test_end_to_end_service_prompt_and_schema_use_registry(self):
        provider=Mock(spec=['chat_structured'])
        provider.chat_structured.return_value='{"choice":"navigate_3d:b1","reason":"verified route"}'
        service=ChatService(ROOT/'character-packs','qwen3:8b',provider,SessionStore())
        reply=service.brain_step('yui','owner','Finish tree','npc',registered())
        self.assertEqual(reply['tool'],'navigate_3d')
        messages,schema=provider.chat_structured.call_args.args
        self.assertEqual(schema['properties']['choice']['enum'],registered()['skills']['choices'])
        self.assertIn('failure_memory',messages[-1]['content'])
        self.assertEqual(provider.chat_structured.call_count,1)

    def test_legacy_transport_still_works(self):
        self.assertEqual(validate_brain_choice({'choice':'mine:b0','reason':'old mod'},validate_state(state()))['tool'],'mine')
