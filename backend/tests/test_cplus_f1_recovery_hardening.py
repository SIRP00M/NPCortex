"""C-F1 H3/H4/H5: diagnostic transport crash/fault contracts only.
No executor, world action, or automatic replay is exercised.
"""
import sqlite3
import json
import subprocess
import sys
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from uuid import uuid4

from npcortex_backend.cplus_f1_bridge import DurableBridge, BridgeConflict
from test_cplus_f1_bridge import fixture
from sqlite_test_utils import sqlite_test_connection


class CrashBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='npcortex-f1-recovery-')
        self.path = Path(self.temp.name) / 'bridge.sqlite3'
        self.bridge = DurableBridge(self.path)

    def tearDown(self):
        self.temp.cleanup()

    def test_commit_then_lost_http_ack_does_not_duplicate_inbox(self):
        event = fixture()
        first = self.bridge.receive(event)
        restarted = DurableBridge(self.path)  # synthetic backend process restart
        second = restarted.receive(event)       # HTTP ACK was lost
        self.assertTrue(second['duplicate'])
        self.assertEqual(second['receipt_id'], first['receipt_id'])
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_f1_bridge_inbox').fetchone()[0], 1)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM cplus_f1_bridge_receipts').fetchone()[0], 1)
        self.assertEqual(restarted.status(event['event']['world_id'])['sequence'], 1)

    def test_backend_process_kill_after_commit_before_response(self):
        wire = fixture()
        # Real child process exits immediately after SQLite commit, before HTTP ACK.
        script = ("import os,json,sys;"
                  "from npcortex_backend.cplus_f1_bridge import DurableBridge;"
                  "DurableBridge(sys.argv[1]).receive(json.loads(sys.argv[2]));"
                  "os._exit(73)")
        outcome = subprocess.run([sys.executable, '-c', script, str(self.path), json.dumps(wire)],
                                 capture_output=True, text=True, timeout=15)
        self.assertEqual(outcome.returncode,73, outcome.stderr)
        restarted = DurableBridge(self.path)
        again = restarted.receive(wire)
        self.assertTrue(again['duplicate'])
        self.assertEqual(restarted.status(wire['event']['world_id'])['sequence'],1)

    def test_backend_process_kill_before_commit_has_no_ack(self):
        wire = fixture()
        script = "import os;os._exit(73)"
        outcome = subprocess.run([sys.executable, '-c', script], timeout=15)
        self.assertEqual(outcome.returncode,73)
        self.assertEqual(DurableBridge(self.path).status(wire['event']['world_id'])['sequence'],0)
        self.assertFalse(self.bridge.recovery(wire['event']['world_id'])['can_replay_world_actions'])

    def test_confirmation_lost_then_restarted_is_idempotent(self):
        event = fixture()
        ack = self.bridge.receive(event)
        confirm = {'world_id':event['event']['world_id'], 'sequence':1,
                   'event_id':event['event']['event_id'], 'receipt_id':ack['receipt_id']}
        self.assertFalse(self.bridge.confirm(confirm)['duplicate'])
        new = DurableBridge(self.path)
        self.assertTrue(new.confirm(confirm)['duplicate'])
        self.assertEqual(new.recovery(confirm['world_id'])['receipt_confirmations_pending'], 0)

    def test_wrong_world_and_broken_sequence_cannot_advance_cursor(self):
        event = fixture()
        self.bridge.receive(event)
        e=event['event']
        wrong = fixture(sequence=3, previous=event['record_hash'],
                        world_id=e['world_id'], actor_id=e['actor_id'])
        with self.assertRaises(BridgeConflict):
            self.bridge.receive(wrong)
        self.assertEqual(self.bridge.status(e['world_id'])['sequence'], 1)
        other = fixture(world_id=str(uuid4()))
        self.bridge.receive(other)
        self.assertEqual(self.bridge.status(other['event']['world_id'])['sequence'], 1)
        self.assertEqual(self.bridge.status(e['world_id'])['sequence'], 1)

    def test_sqlite_write_failure_cannot_return_false_ack(self):
        event = fixture()
        with patch.object(self.bridge, 'connection', side_effect=sqlite3.OperationalError('simulated disk full')):
            with self.assertRaisesRegex(sqlite3.OperationalError,'disk full'):
                self.bridge.receive(event)
        self.assertEqual(self.bridge.status(event['event']['world_id'])['sequence'], 0)
        self.assertEqual(self.bridge.recovery(event['event']['world_id'])['receipt_confirmations_pending'], 0)

    def test_unresolved_intent_requires_recovery_not_replay(self):
        event=fixture()
        self.bridge.receive(event)
        e=event['event']
        intent=fixture('INTENT',2,event['record_hash'], world_id=e['world_id'],actor_id=e['actor_id'])
        self.bridge.receive(intent)
        new=DurableBridge(self.path)
        summary=new.recovery(e['world_id'])
        self.assertEqual(summary['classification']['PREPARED'],1)
        self.assertFalse(summary['can_replay_world_actions'])
        self.assertFalse(summary['has_authoritative_world_observation'])

    def test_synthetic_one_three_five_ten_npc_isolation_load(self):
        # Bounded throughput smoke test, NOT real tick/MSPT measurements.
        start=time.perf_counter()
        for count in (1, 3, 5, 10):
            world=str(uuid4())
            previous='0'*64
            for sequence in range(1,count+1):
                wire=fixture('FENCE',sequence,previous,world_id=world,actor_id=str(uuid4()))
                self.bridge.receive(wire)
                previous=wire['record_hash']
            self.assertEqual(self.bridge.status(world)['sequence'],count)
            self.assertEqual(self.bridge.recovery(world)['classification']['IN_DOUBT'],0)
        elapsed_ms=int((time.perf_counter()-start)*1000)
        print('C-F1 synthetic 1/3/5/10 NPC isolation duration_ms=',elapsed_ms)


if __name__ == '__main__':
    unittest.main()
