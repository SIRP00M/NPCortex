"""G3 Fix #2: empty status is HTTP 200/NONE, never fake completion.

All stateful fixtures use isolated temporary SQLite databases.  No Minecraft
world, production database, or movement executor is touched by these tests.
"""
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from uuid import uuid4

from npcortex_backend.cplus_e3_g2_motion import GuardedMotionStore
from npcortex_backend.cplus_e_graph import GraphConflict
from test_cplus_e3_g2_motion import body, ack
from test_cplus_e3_g2_http import G2HTTP
from test_cplus_e3_g3_http import G3LifecycleFixture


class G3EmptyStatusStore(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = Path(self.temp.name) / 'empty_status.db'
        self.store = GuardedMotionStore(self.db)
        self.world = str(uuid4())
        self.actor = str(uuid4())

    def tearDown(self):
        self.temp.cleanup()

    def assert_empty(self, store, world=None, actor=None):
        world = world or self.world
        actor = actor or self.actor
        state = store.status(world, actor)
        self.assertEqual(state, {
            'world_id': world, 'actor_id': actor, 'world_effects': False,
            'state': 'NONE', 'execution_mode': 'GUARDED_C2',
            'completion_verified': False,
        })
        self.assertIs(type(state['completion_verified']), bool)
        return state

    def test_new_world_empty_database_never_returns_completion(self):
        self.assert_empty(self.store)
        self.assert_empty(self.store)
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_e3_g2_moves').fetchone()[0], 0)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_e3_g3_terminals').fetchone()[0], 0)

    def test_empty_restart_still_none_and_no_auto_replay(self):
        before = self.assert_empty(self.store)
        restarted = GuardedMotionStore(self.db)
        self.assertEqual(self.assert_empty(restarted), before)
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_e3_g2_moves').fetchone()[0], 0)

    def test_other_actor_and_world_none_when_attempt_exists(self):
        request = body()
        row = self.store.reserve(request)
        self.assertEqual(row['state'], 'RESERVED')
        self.assert_empty(self.store)
        self.assert_empty(self.store, request['world_id'], str(uuid4()))
        self.assert_empty(self.store, str(uuid4()), request['actor_id'])
        self.assertEqual(self.store.status(request['world_id'], request['actor_id'])['state'], 'RESERVED')
        # A status query for a different actor must not release the actor fence.
        with self.assertRaises(GraphConflict):
            self.store.reserve(body(request['snapshot']))

    def test_existing_accepted_restart_remains_hold(self):
        request = body()
        self.store.reserve(request)
        accepted = self.store.report(ack(request))
        self.assertEqual(accepted['state'], 'C2_ACCEPTED')
        self.assertFalse(accepted['completion_verified'])
        restarted = GuardedMotionStore(self.db)
        state = restarted.status(request['world_id'], request['actor_id'])
        self.assertEqual(state['state'], 'HOLD')
        self.assertFalse(state['completion_verified'])
        self.assert_empty(restarted)
        with self.assertRaises(GraphConflict):
            restarted.reserve(body(request['snapshot']))

    def test_durable_completion_and_restart_not_misreported_as_none(self):
        request = body()
        self.store.reserve(request)
        goal_id = str(uuid4())
        self.store.report(ack(request, goal_id=goal_id))
        candidate = G3LifecycleFixture(request, goal_id).candidate()
        completed = self.store.finalize(candidate)
        self.assertEqual(completed['state'], 'COMPLETED')
        self.assertTrue(completed['completion_verified'])
        restarted = GuardedMotionStore(self.db)
        state = restarted.status(request['world_id'], request['actor_id'])
        self.assertEqual(state['state'], 'COMPLETED')
        self.assertTrue(state['completion_verified'])
        self.assert_empty(restarted)

    def test_durable_cancel_not_misreported_as_complete(self):
        request = body()
        self.store.reserve(request)
        goal_id = str(uuid4())
        self.store.report(ack(request, goal_id=goal_id))
        identity = {k: request[k] for k in (
            'task_id', 'attempt_id', 'requester_id', 'world_id', 'actor_id', 'session_id')}
        identity['goal_id'] = goal_id
        self.assertEqual(self.store.cancel_begin(identity)['state'], 'CANCEL_PENDING')
        cancelled = self.store.cancel_ack({**identity, 'action_id': None,
            'c2_state': 'CANCELLED', 'c1_stopped': True})
        self.assertEqual(cancelled['state'], 'CANCELLED')
        self.assertFalse(cancelled['completion_verified'])
        restarted = GuardedMotionStore(self.db)
        state = restarted.status(request['world_id'], request['actor_id'])
        self.assertEqual(state['state'], 'CANCELLED')
        self.assertFalse(state['completion_verified'])

    def test_invalid_world_actor_ids_rejected_before_lookup(self):
        for world, actor in [('not-uuid', self.actor), (self.world, 'not-uuid')]:
            with self.subTest(world=world, actor=actor):
                with self.assertRaises(GraphConflict):
                    self.store.status(world, actor)


class G3EmptyStatusHTTP(G2HTTP):
    """Real authenticated loopback transport; inherits G2 reserve/ACK tests."""

    def test_fresh_http_status_returns_200_and_explicit_none(self):
        world, actor = self.b['world_id'], self.b['actor_id']
        code, payload = self.post('/v1/cplus/e3/status',
                                  {'world_id': world, 'actor_id': actor})
        self.assertEqual(code, 200)
        self.assertEqual(payload, {
            'world_id': world, 'actor_id': actor, 'world_effects': False,
            'state': 'NONE', 'execution_mode': 'GUARDED_C2',
            'completion_verified': False,
        })

    def test_new_world_status_does_not_modify_existing_attempt(self):
        request = self.b
        self.assertEqual(self.post('/v1/cplus/e3/reserve', request)[0], 200)
        code, absent = self.post('/v1/cplus/e3/status', {
            'world_id': str(uuid4()), 'actor_id': request['actor_id']})
        self.assertEqual(code, 200)
        self.assertEqual(absent['state'], 'NONE')
        self.assertFalse(absent['completion_verified'])
        code, current = self.post('/v1/cplus/e3/status', {
            'world_id': request['world_id'], 'actor_id': request['actor_id']})
        self.assertEqual(code, 200)
        self.assertEqual(current['state'], 'RESERVED')
        self.assertFalse(current['completion_verified'])

    def test_status_endpoint_requires_auth_and_valid_identity(self):
        payload = {'world_id': self.b['world_id'], 'actor_id': self.b['actor_id']}
        self.assertEqual(self.post('/v1/cplus/e3/status', payload, 'bad-token')[0], 403)
        self.assertEqual(self.post('/v1/cplus/e3/status', {'world_id': 'invalid',
                            'actor_id': self.b['actor_id']})[0], 409)
        self.assertEqual(self.post('/v1/cplus/e3/status', {'world_id': self.b['world_id'],
                            'actor_id': self.b['actor_id'], 'force': True})[0], 400)


if __name__ == '__main__':
    unittest.main()
