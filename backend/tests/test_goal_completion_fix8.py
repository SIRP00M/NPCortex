"""Sprint2 Fix8: strict evidence transport and grounded recovery choice."""
import copy
import unittest
from test_task_context_fix1 import with_context
from npcortex_backend.brain import validate_state, validate_brain_choice, BRAIN_POLICY_V3


def goal_state():
    state = with_context()
    state['task_context']['goal_completion'] = {
        'phase': 'recover_drops', 'missing_logs': 1, 'borrowed_logs': 0,
        'temporary_blocks': 0, 'tree_logs_remaining': 0,
        'verified_trees': 0, 'worksite_distance': 27,
    }
    state['skills']['catalog'].append({
        'id': 'recover_worksite', 'target': 'none',
        'description': 'Walk a grounded segment back toward verified tree root',
    })
    state['skills']['choices'] = ['pause', 'recover_worksite']
    state['targets'] = []
    state['drops'] = []
    state['tree'] = {'active': True, 'remaining': 0, 'size': 20,
                     'completed': 0, 'terrain_edits': 0}
    state['mined'] = 20
    state['collected'] = 19
    return state


class GoalCompletionFix8Tests(unittest.TestCase):
    def test_missing_log_is_explicit_and_worksite_action_is_grounded(self):
        state = validate_state(goal_state())
        self.assertEqual(state['task_context']['goal_completion']['missing_logs'], 1)
        self.assertEqual(validate_brain_choice({
            'choice': 'recover_worksite', 'reason': 'Return to root, search for real drop'
        }, state)['tool'], 'recover_worksite')
        self.assertNotIn('explore:e0', state['skills']['choices'])
        self.assertNotIn('mine_material:m0', state['skills']['choices'])
        self.assertIn('missing_logs', BRAIN_POLICY_V3)

    def test_fake_recovery_choice_not_allowed(self):
        state = goal_state()
        state['skills']['choices'].append('finish')
        # Catalog required for server-owned choices; cannot invent tools via model.
        with self.assertRaises(ValueError):
            validate_state(state)

    def test_invalid_ledger_payloads_rejected(self):
        for key, value in [('missing_logs', -1), ('missing_logs', True),
                           ('worksite_distance', 'near'), ('phase', 'instant_success'),
                           ('verified_trees', 5_000)]:
            state = goal_state()
            state['task_context']['goal_completion'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_state(state)
        state = goal_state()
        state['task_context']['goal_completion']['command'] = '/give @p oak_log 1'
        with self.assertRaises(ValueError):
            validate_state(state)

    def test_old_context_accepted(self):
        state = with_context()
        self.assertNotIn('goal_completion', validate_state(state)['task_context'])


if __name__ == '__main__': unittest.main()
