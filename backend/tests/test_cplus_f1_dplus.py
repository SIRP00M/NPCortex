"""Synthetic C-F1 D+ correlation, compensation and crash-window contracts."""
import tempfile
import sqlite3
import unittest
from pathlib import Path
from uuid import uuid4

from npcortex_backend.cplus_f1_effects import Observation, resolve_effect, classify_failure
from npcortex_backend.cplus_f1_projection import CPlusF1Projection, CPlusF1Conflict
from sqlite_test_utils import sqlite_test_connection

FP = "a" * 64


def event(stage="FENCE", **overrides):
    row = {
        "event_id": str(uuid4()), "world_id": str(uuid4()), "actor_id": str(uuid4()),
        "task_id": str(uuid4()), "action_id": str(uuid4()), "attempt_id": str(uuid4()),
        "parent_event_id": None, "epoch": 1, "stage": stage, "fingerprint": FP,
        "evidence_id": "",
    }
    row.update(overrides)
    return row


class EffectAwareTests(unittest.TestCase):
    def test_external_consumption_no_fake_credit(self):
        self.assertEqual(resolve_effect(Observation(True,20,8,4,0,"inv-receipt")),
                         ("EXTERNALLY_CONSUMED","REPLAN_REMAINING",8,12,"inv-receipt"))

    def test_partial_and_completed(self):
        self.assertEqual(resolve_effect(Observation(True,20,8,0,0,"inv"))[0],"PARTIAL")
        self.assertEqual(resolve_effect(Observation(True,20,20,0,0,"inv"))[1],"COMPLETE")

    def test_unaccounted_holds(self):
        self.assertEqual(resolve_effect(Observation(True,20,8,4,8,"tracked"))[1],"HOLD_FOR_EVIDENCE")
        self.assertEqual(resolve_effect(Observation(False,20,8,4,0,""))[2],0)

    def test_effect_aware_failure_guard(self):
        self.assertEqual(classify_failure("EXECUTING", True, "NONE"), "IN_DOUBT")
        self.assertEqual(classify_failure("VERIFYING", True, "RECONCILED_OBSERVATION"), "IN_DOUBT")
        self.assertEqual(classify_failure("EXECUTING", True, "VERIFIED_NO_EFFECT"), "FAILED")
        with self.assertRaises(ValueError): classify_failure("COMPLETED", True, "NONE")

    def test_invalid_proof_rejected(self):
        for args in [(True,20,8,4,9,"a"),(True,20,8,4,0,""),(True,20,21,0,0,"receipt"),
                     (True,20,True,0,0,"receipt")]:
            with self.subTest(args=args), self.assertRaises(ValueError): Observation(*args)


class WindowsSQLiteFixtureLifecycleTests(unittest.TestCase):
    """Prove that test fixture connections close before Windows directory cleanup."""

    def test_connection_closes_before_temp_directory_cleanup(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "fixture.sqlite3"
            with sqlite_test_connection(db_path) as db:
                db.execute("CREATE TABLE lifecycle (value INTEGER)")
                db.execute("INSERT INTO lifecycle VALUES (7)")
            # A committed transaction is not enough: Windows requires close().
            with self.assertRaises(sqlite3.ProgrammingError):
                db.execute("SELECT value FROM lifecycle")
            with sqlite_test_connection(db_path) as reopened:
                self.assertEqual(reopened.execute("SELECT value FROM lifecycle").fetchone()[0], 7)
        self.assertFalse(db_path.exists())

    def test_connection_closes_and_rolls_back_on_exception(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "fixture.sqlite3"
            with sqlite_test_connection(db_path) as db:
                db.execute("CREATE TABLE lifecycle (value INTEGER)")
            with self.assertRaisesRegex(ValueError, "forced rollback"):
                with sqlite_test_connection(db_path) as failing_db:
                    failing_db.execute("INSERT INTO lifecycle VALUES (9)")
                    raise ValueError("forced rollback")
            with self.assertRaises(sqlite3.ProgrammingError):
                failing_db.execute("SELECT value FROM lifecycle")
            with sqlite_test_connection(db_path) as reopened:
                self.assertEqual(reopened.execute("SELECT COUNT(*) FROM lifecycle").fetchone()[0], 0)
        self.assertFalse(db_path.exists())


class ProjectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db = Path(self.temp.name) / "npcortex.sqlite3"
        with sqlite_test_connection(self.db) as c:
            c.execute("PRAGMA user_version=4")
            c.execute("CREATE TABLE legacy_save (payload TEXT)")
            c.execute("INSERT INTO legacy_save VALUES ('keep-existing-rows')")
        self.p = CPlusF1Projection(self.db)
        self.f = event()

    def tearDown(self):
        self.temp.cleanup()

    def append(self, stage, evidence="", **changes):
        vals = {**self.f, "event_id": str(uuid4()), "stage": stage, "evidence_id": evidence}
        vals.update(changes)
        return self.p.record(vals)

    def test_additive_schema_keeps_v4(self):
        with sqlite_test_connection(self.db) as db:
            self.assertEqual(db.execute("PRAGMA user_version").fetchone()[0],4)
            self.assertEqual(db.execute("SELECT payload FROM legacy_save").fetchone()[0],"keep-existing-rows")

    def test_duplicate_event_and_ack(self):
        self.assertTrue(self.p.record(self.f))
        self.assertFalse(self.p.record(self.f))
        self.assertEqual(self.p.pending_outbox(),[self.f["event_id"]])
        self.assertTrue(self.p.acknowledge(self.f["event_id"],"forge-ledger-1"))
        self.assertFalse(self.p.acknowledge(self.f["event_id"],"forge-ledger-1"))
        self.assertEqual(self.p.pending_outbox(),[])
        with self.assertRaises(CPlusF1Conflict): self.p.acknowledge(self.f["event_id"],"different")
        with self.assertRaises(CPlusF1Conflict): self.p.acknowledge(str(uuid4()),"bad")

    def test_conflicting_event_rejected(self):
        self.p.record(self.f)
        bad = {**self.f,"fingerprint":"b"*64}
        with self.assertRaises(CPlusF1Conflict): self.p.record(bad)

    def test_missing_intent_and_stale_epoch_rejected(self):
        with self.assertRaises(CPlusF1Conflict): self.append("EXECUTION_STARTED")
        self.p.record(self.f)
        with self.assertRaises(CPlusF1Conflict): self.append("INTENT",epoch=2)
        self.append("INTENT")
        with self.assertRaises(CPlusF1Conflict): self.append("EXECUTION_STARTED",epoch=2)

    def test_durable_progress_no_fake_success(self):
        self.p.record(self.f)
        self.append("INTENT")
        with self.assertRaises(CPlusF1Conflict): self.append("VERIFIED", "fake-proof")
        self.append("EXECUTION_STARTED")
        with self.assertRaises(CPlusF1Conflict): self.append("CANCELLED", "cancel-does-not-undo")
        self.append("IN_DOUBT")
        with self.assertRaises(CPlusF1Conflict): self.append("VERIFIED", "still-not-verified")
        self.append("RECONCILED", "server-state-read")
        self.append("VERIFIED", "verified-world-receipt")
        with self.assertRaises(CPlusF1Conflict): self.append("EXECUTION_STARTED")

    def test_restart_and_epoch_fencing(self):
        self.p.record(self.f)
        self.p = CPlusF1Projection(self.db)
        with self.assertRaises(CPlusF1Conflict): self.p.record({**self.f,"event_id":str(uuid4())})
        self.append("FENCE",epoch=2)
        with self.assertRaises(CPlusF1Conflict): self.append("INTENT",epoch=1)
        self.append("INTENT",epoch=2)

    def test_wrong_actor_same_action_rejected(self):
        self.p.record(self.f)
        self.append("INTENT")
        with self.assertRaises(CPlusF1Conflict): self.append("OBSERVED_PARTIAL", "fake-proof",actor_id=str(uuid4()))

    def test_same_transaction_outbox(self):
        self.p.record(self.f)
        with sqlite_test_connection(self.db) as db:
            self.assertEqual(db.execute("SELECT COUNT(*) FROM cplus_f1_events").fetchone()[0],1)
            self.assertEqual(db.execute("SELECT COUNT(*) FROM cplus_f1_outbox").fetchone()[0],1)


if __name__ == "__main__": unittest.main()
