"""Sprint2 Fix3: preserve Qwen agency while reducing v3 prompt pressure."""
import json
import unittest
from unittest.mock import Mock, patch

from test_agentic_brain_v085 import ROOT
from test_task_context_fix1 import with_context
from npcortex_backend.brain import BRAIN_POLICY, BRAIN_POLICY_V3
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.ollama import OllamaProvider


class CompactBrainPolicyTests(unittest.TestCase):
    def test_v3_policy_compact_but_contains_server_authority_and_tree_goal(self):
        self.assertGreater(len(BRAIN_POLICY), 10000)
        self.assertLess(len(BRAIN_POLICY_V3), 3500)
        self.assertIn('ALL logs', BRAIN_POLICY_V3)
        self.assertIn('Minecraft Forge executes', BRAIN_POLICY_V3)
        self.assertIn('skills.choices', BRAIN_POLICY_V3)

    def test_v3_knows_reversible_access_can_precede_drop_pickup(self):
        # The skill registry can offer descend while outstanding mined drops
        # remain; hard-coding "pickup everything before descent" fights recovery.
        self.assertIn('tree.remaining is zero', BRAIN_POLICY_V3)
        self.assertIn('descending BEFORE chasing hard-to-reach drops', BRAIN_POLICY_V3)
        self.assertIn('not a mandatory sequence', BRAIN_POLICY_V3)
        self.assertNotIn('Keep scaffold until committed logs/drops are complete', BRAIN_POLICY_V3)

    def test_v3_still_allows_qwen_to_choose_any_offered_action(self):
        for choice in ['mine:b0', 'mine_material:m0', 'explore:e0', 'return_to_work']:
            provider = Mock(spec=['chat_structured'])
            provider.chat_structured.return_value = json.dumps({'choice': choice, 'reason': 'Observed need'})
            svc = ChatService(ROOT/'character-packs', 'qwen3:8b', provider, SessionStore())
            result = svc.brain_step('yui', 'owner', 'Chop whole oak tree', 'npc', with_context())
            self.assertEqual(result['tool'], choice.split(':')[0])
            messages, schema = provider.chat_structured.call_args.args
            self.assertEqual(schema['properties']['choice']['enum'], with_context()['skills']['choices'])
            self.assertIn('ALL logs', messages[0]['content'])
            self.assertNotIn('Legacy tool notes', messages[0]['content'])
            prompt = json.loads(messages[-1]['content'].removeprefix('READ-ONLY DATA: '))
            self.assertEqual(prompt['server_observation']['task_context'], with_context()['task_context'])
            self.assertNotIn('candidate_refs_by_skill', prompt)
            self.assertNotIn('allowed_choices', prompt)

    def test_ollama_brain_uses_larger_context_without_changing_chat_defaults(self):
        fake = Mock()
        fake.__enter__ = Mock(return_value=fake)
        fake.__exit__ = Mock(return_value=None)
        fake.read.return_value = json.dumps({'message':{'content':'{"choice":"pause","reason":"test"}'}}).encode()
        provider=OllamaProvider('http://127.0.0.1:11434','qwen3:8b')
        with patch('npcortex_backend.ollama.urlopen', return_value=fake) as urlopen:
            provider.chat_brain([{'role':'user','content':'test'}], {'type':'object'})
            body=json.loads(urlopen.call_args.args[0].data.decode())
            self.assertEqual(body['options']['num_ctx'],8192)
            self.assertEqual(body['options']['num_predict'],320)
            self.assertFalse(body['think'])
        with patch('npcortex_backend.ollama.urlopen', return_value=fake) as urlopen:
            provider.chat([{'role':'user','content':'test'}])
            body=json.loads(urlopen.call_args.args[0].data.decode())
            self.assertEqual(body['options']['num_ctx'],4096)


if __name__ == '__main__':
    unittest.main()
