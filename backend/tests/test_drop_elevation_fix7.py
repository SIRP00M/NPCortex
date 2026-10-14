"""Regression: a ground drop below a temporary bridge must be visible to Qwen.

This tests the *observation contract*, not autonomous task success in Minecraft.
"""
import unittest
import json
from unittest.mock import Mock
from test_general_material_brain import with_materials
from test_agentic_brain_v085 import ROOT
from npcortex_backend.brain import validate_state, allowed_brain_choices
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore


class DropElevationFix7Tests(unittest.TestCase):
    def observation(self):
        state=with_materials()
        state['skills']['version']=3
        state['frontiers']=[]
        state['access']={
            'temporary_blocks':12,'borrowed_goal_blocks':12,
            'last_search':'SEARCH_BUDGET','explored_regions':4,
        }
        state['scaffold']=0
        state['mined']=20
        state['collected']=2
        state['tree'].update(active=True,remaining=0,size=20,completed=0)
        state['drops'][0]['dy']=-7
        state['skills']['catalog'].append({
            'id':'descend','target':'none','description':'Reverse recorded supports safely',
        }) if not any(i['id']=='descend' for i in state['skills']['catalog']) else None
        state['skills']['choices'].append('descend')
        return state

    def test_below_drop_is_accepted_with_descend_offered(self):
        state=validate_state(self.observation())
        self.assertEqual(state['drops'][0]['dy'],-7)
        self.assertIn('descend',allowed_brain_choices(state))

    def test_real_brain_service_passes_height_to_qwen(self):
        provider = Mock(spec=['chat_structured'])
        provider.chat_structured.return_value = {
            'choice':'descend','reason':'Ground drops are below my temporary bridge',
        }
        service = ChatService(ROOT / 'character-packs', 'qwen3:8b', provider, SessionStore())
        reply = service.brain_step('yui','owner','Harvest all oak', 'npc', self.observation())
        self.assertEqual(reply['tool'],'descend')
        messages, schema = provider.chat_structured.call_args.args
        observation = json.loads(messages[-1]['content'].removeprefix('READ-ONLY DATA: '))['server_observation']
        self.assertEqual(observation['drops'][0]['dy'],-7)
        self.assertIn('descend',schema['properties']['choice']['enum'])

    def test_upward_and_same_height_offsets(self):
        for offset in (-40,-1,0,1,40):
            with self.subTest(offset=offset):
                state=self.observation(); state['drops'][0]['dy']=offset
                self.assertEqual(validate_state(state)['drops'][0]['dy'],offset)

    def test_invalid_height_does_not_reach_qwen(self):
        for bad in (True,3.5,'-7',None,-41,41):
            with self.subTest(value=bad):
                state=self.observation();state['drops'][0]['dy']=bad
                with self.assertRaises(ValueError): validate_state(state)

    def test_unknown_drop_keys_still_rejected(self):
        state=self.observation();state['drops'][0]['command']='/tp'
        with self.assertRaises(ValueError): validate_state(state)

    def test_legacy_observation_without_height_still_supported(self):
        state=self.observation();del state['drops'][0]['dy']
        self.assertEqual(validate_state(state)['drops'][0]['ref'],'d0')

    def test_java_emits_relative_height_from_item_block(self):
        source=(ROOT/'forge-mod/src/main/java/io/github/sirp00m/npcortex/BrainObservation.java').read_text()
        self.assertIn('obj.addProperty("dy", found.get(i).blockPosition().getY() - center.getY())',source)


if __name__=='__main__': unittest.main()
