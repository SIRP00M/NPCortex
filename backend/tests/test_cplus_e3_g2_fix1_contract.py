"""E3-D G2 Fix #1: response identity parity and fail-closed HTTP regression.

Actual Java validator is exercised separately by Gradle verifyG2Identity.
These tests prove the real Python backend response contract and ACK outcomes.
"""
import copy
import unittest
from uuid import uuid4
from test_cplus_e3_g2_http import G2HTTP
from test_cplus_e3_g2_motion import ack


class G2Fix1Protocol(G2HTTP):
    # Base class tests are intentionally inherited; every case has a fresh DB.
    def test_nested_request_top_level_reserve_response_identity(self):
        self.assertNotIn('observation_id', self.b)
        code, r = self.post('/v1/cplus/e3/reserve', self.b)
        self.assertEqual(code, 200)
        for key in ('task_id', 'request_id', 'attempt_id', 'requester_id',
                    'world_id', 'dimension_id', 'session_id', 'actor_id'):
            self.assertEqual(r[key], self.b[key])
        self.assertEqual(r['observation_id'], self.b['snapshot']['observation_id'])
        for key in ('world_id', 'dimension_id', 'session_id', 'actor_id'):
            self.assertEqual(r[key], self.b['snapshot'][key])
        self.assertEqual(r['state'], 'RESERVED')
        self.assertTrue(r['can_execute'])
        self.assertFalse(r['world_effects'])
        self.assertFalse(r['completion_verified'])

    def test_report_ack_matches_original_request_and_observation(self):
        self.assertEqual(self.post('/v1/cplus/e3/reserve', self.b)[0], 200)
        a = ack(self.b)
        code, r = self.post('/v1/cplus/e3/report', a)
        self.assertEqual(code, 200)
        for key in ('task_id', 'request_id', 'attempt_id', 'requester_id',
                    'world_id', 'dimension_id', 'session_id', 'actor_id'):
            self.assertEqual(r[key], self.b[key])
        self.assertEqual(r['observation_id'], self.b['snapshot']['observation_id'])
        self.assertEqual(r['goal_id'], a['goal_id'])
        self.assertEqual(r['state'], 'C2_ACCEPTED')
        self.assertTrue(r['c2_goal_accepted'])
        self.assertFalse(r['world_effects'])
        self.assertFalse(r['completion_verified'])
        self.assertFalse(r['motion_started'])

    def test_invalid_ack_identity_rejected_and_reservation_unchanged(self):
        self.assertEqual(self.post('/v1/cplus/e3/reserve', self.b)[0], 200)
        original = ack(self.b)
        for field in ('task_id', 'attempt_id', 'world_id', 'actor_id', 'session_id'):
            with self.subTest(field=field):
                changed = dict(original)
                changed[field] = str(uuid4())
                code, _ = self.post('/v1/cplus/e3/report', changed)
                self.assertEqual(code, 409)
                status = self.post('/v1/cplus/e3/status', {
                    'world_id': self.b['world_id'], 'actor_id': self.b['actor_id']})[1]
                self.assertEqual(status['state'], 'RESERVED')
                self.assertFalse(status['c2_goal_accepted'])

    def test_report_completion_forgery_rejected(self):
        self.assertEqual(self.post('/v1/cplus/e3/reserve', self.b)[0], 200)
        forged = ack(self.b)
        forged['state'] = 'COMPLETED'
        self.assertEqual(self.post('/v1/cplus/e3/report', forged)[0], 409)
        status = self.post('/v1/cplus/e3/status', {
            'world_id': self.b['world_id'], 'actor_id': self.b['actor_id']})[1]
        self.assertEqual(status['state'], 'RESERVED')
        self.assertFalse(status['completion_verified'])

    def test_mismatched_snapshot_identity_rejected_without_reserve(self):
        for key in ('world_id', 'dimension_id', 'session_id', 'actor_id'):
            with self.subTest(field=key):
                altered = copy.deepcopy(self.b)
                altered['snapshot'][key] = str(uuid4())
                self.assertNotEqual(self.post('/v1/cplus/e3/reserve', altered)[0], 200)
        # Original unchanged request remains admissible.
        self.assertEqual(self.post('/v1/cplus/e3/reserve', self.b)[0], 200)

    def test_bad_observation_id_fails_closed(self):
        bad = copy.deepcopy(self.b)
        bad['snapshot']['permission']['evidence_id'] = str(uuid4())
        self.assertNotEqual(self.post('/v1/cplus/e3/reserve', bad)[0], 200)
        self.assertEqual(self.post('/v1/cplus/e3/reserve', self.b)[0], 200)

    def test_duplicate_reserve_does_not_duplicate_attempt(self):
        code, first = self.post('/v1/cplus/e3/reserve', self.b)
        self.assertEqual(code, 200)
        code, second = self.post('/v1/cplus/e3/reserve', self.b)
        self.assertEqual(code, 200)
        self.assertEqual(first, second)
        changed = copy.deepcopy(self.b)
        changed['snapshot']['observation_id'] = str(uuid4())
        changed['snapshot']['permission']['evidence_id'] = changed['snapshot']['observation_id']
        self.assertEqual(self.post('/v1/cplus/e3/reserve', changed)[0], 409)
