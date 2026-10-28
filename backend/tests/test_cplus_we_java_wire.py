"""Wire compatibility: the EXACT bodies the Java WorldEffectFlow sends (fixtures/world_effect_wire_v1.json, produced and
shape-checked by WorldEffectFlowSelfTest) are accepted by the Python ledger, and the ledger replies satisfy the Java checks.
Also: the ledger opens on a copy of the committed v4 baseline database without altering any pre-existing object."""
import json
import shutil
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from npcortex_backend.cplus_e_graph import GraphConflict
from npcortex_backend.cplus_we_ledger import WorldEffectStore

FIXTURES = Path(__file__).parent / 'fixtures'
WIRE = json.loads((FIXTURES / 'world_effect_wire_v1.json').read_text('utf-8'))


class JavaWire(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.s = WorldEffectStore(Path(self.tmp.name) / 'w.db')

    def tearDown(self):
        self.tmp.cleanup()

    def echo_ok(self, sent, reply, state):
        """The checks WorldEffectFlow.echoes() applies to every reply."""
        self.assertEqual(reply['state'], state)
        for key in ('task_id', 'attempt_id', 'world_id', 'actor_id', 'session_id'):
            self.assertEqual(reply[key], sent[key], key)
        if 'requester_id' in sent:
            self.assertEqual(reply['requester_id'], sent['requester_id'])
        self.assertIs(reply['world_effects'], False)
        self.assertIs(reply['native_mutation'], False)

    def test_happy_chain(self):
        c = WIRE['happy']
        self.echo_ok(c['reserve'], self.s.reserve(c['reserve']), 'RESERVED')
        started = self.s.start(c['start'])
        self.echo_ok(c['start'], started, 'EXECUTING')
        self.assertIs(started['execution_started'], True)
        done = self.s.finalize(c['finalize'])
        self.echo_ok(c['finalize'], done, 'NO_EFFECT_VERIFIED')
        self.assertIs(done['durable_ack'], True)
        self.assertIs(done['release_actor_fence'], True)

    def test_cancel_chain(self):
        c = WIRE['cancelled']
        self.s.reserve(c['reserve'])
        done = self.s.cancel(c['cancel'])
        self.echo_ok(c['cancel'], done, 'CANCELLED_NO_EFFECT')

    def test_failed_chain(self):
        c = WIRE['failed']
        self.s.reserve(c['reserve']); self.s.start(c['start'])
        done = self.s.finalize(c['finalize'])
        self.echo_ok(c['finalize'], done, 'FAILED_NO_EFFECT')
        self.assertIs(done['durable_ack'], True)

    def test_hold_chain_then_reconcile_with_the_java_reconcile_body(self):
        c = WIRE['hold']
        self.s.reserve(c['reserve']); self.s.start(c['start'])
        held = self.s.hold(c['hold'])
        self.echo_ok(c['hold'], held, 'HOLD_IN_DOUBT')
        status = self.s.status(c['reserve']['world_id'], c['reserve']['actor_id'])
        self.assertIs(status['active'], True)
        body = dict(WIRE['reconcile'])
        for key in ('task_id', 'attempt_id', 'world_id', 'actor_id', 'requester_id'):
            body[key] = status[key]
        released = self.s.reconcile(body)
        self.assertEqual(released['state'], 'RECONCILED_NO_EFFECT')
        self.assertIs(released['release_actor_fence'], True)

    def test_status_body_shape(self):
        self.assertEqual(set(WIRE['status']), {'world_id', 'actor_id'})
        self.assertEqual(self.s.status(WIRE['status']['world_id'], WIRE['status']['actor_id'])['state'], 'NONE')

    def test_a_tampered_java_body_is_refused(self):
        c = WIRE['happy']
        for key in ('origin', 'action_kind'):
            bad = json.loads(json.dumps(c['reserve'])); bad[key] = 'break_block' if key == 'action_kind' else 'COMMAND'
            with self.assertRaises(GraphConflict):
                self.s.reserve(bad)
        bad = json.loads(json.dumps(c['reserve'])); bad['authorization']['decision'] = 'DENY'
        with self.assertRaises(GraphConflict):
            self.s.reserve(bad)

    def test_ledger_opens_on_the_committed_v4_baseline_without_touching_it(self):
        baseline = FIXTURES / 'v4_baseline.sqlite3'
        copy = Path(self.tmp.name) / 'v4.sqlite3'
        shutil.copyfile(baseline, copy)

        def snapshot(path):
            with closing(sqlite3.connect(path)) as db:
                objects = {r[0]: r[1] for r in db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")}
                rows = {t: db.execute(f'SELECT * FROM "{t}"').fetchall() for t, in
                        db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
                return objects, rows, db.execute('PRAGMA user_version').fetchone()[0]

        before_objects, before_rows, version = snapshot(copy)
        store = WorldEffectStore(copy)
        after_objects, after_rows, after_version = snapshot(copy)
        self.assertEqual(after_version, version)
        for name, sql in before_objects.items():
            self.assertEqual(after_objects[name], sql, name)
        for table, rows in before_rows.items():
            self.assertEqual(after_rows[table], rows, table)
        added = set(after_objects) - set(before_objects)
        # cplus_e_* come from the inherited ReadOnlyGraph base (CREATE IF NOT EXISTS, the same as every E3 store).
        self.assertTrue(all(n.startswith(('cplus_we_', 'cplus_e_', 'sqlite_autoindex_cplus_')) for n in added), added)
        self.assertTrue(any(n.startswith('cplus_we_') for n in added))
        c = WIRE['happy']
        self.assertEqual(store.reserve(c['reserve'])['state'], 'RESERVED')   # and it works on that file


if __name__ == '__main__':
    unittest.main()
