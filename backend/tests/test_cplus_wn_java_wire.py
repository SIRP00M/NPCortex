"""Wire compatibility: the EXACT call sequences the Java NativeEffectFlow sends (fixtures/native_effect_wire_v1.json, produced and
shape-checked by NativeEffectFlowSelfTest) are accepted by the Python native ledger in order, the ledger reaches the same states
the Java side expected, and Python's canonical-JSON digest equals the one Java computed for every hashed body (byte parity)."""
import json
import tempfile
import unittest
from pathlib import Path

from npcortex_backend.cplus_e_graph import GraphConflict
from npcortex_backend.cplus_wn_store import NativeEffectStore, _canonical, _digest

WIRE = json.loads((Path(__file__).parent / 'fixtures' / 'native_effect_wire_v1.json').read_text('utf-8'))
STATE_ROUTES = ('reserve', 'start', 'finalize', 'cancel', 'hold', 'repair', 'reconcile', 'status')


class JavaNativeWire(unittest.TestCase):
    def replay(self, name):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        store = NativeEffectStore(Path(tmp.name) / 'w.db')
        replies = []
        for i, step in enumerate(WIRE[name]['steps']):
            route, body, expected = step['route'], step['body'], step['reply_state']
            if 'digest' in step:
                self.assertEqual(_digest(body), step['digest'], f'{name}[{i}] {route}: canonical digest differs between Java and Python')
            if 'intended_digest' in step:
                self.assertEqual(_digest(body['intended']), step['intended_digest'], f'{name}[{i}] intended digest')
            if expected == 'REFUSED':
                with self.assertRaises(GraphConflict, msg=f'{name}[{i}] {route}'):
                    getattr(store, route)(body)
                continue
            if route == 'saved':
                continue
            reply = getattr(store, route)(body)
            replies.append((route, reply))
            if route == 'bind':
                self.assertEqual(reply['state'], expected, f'{name}[{i}] bind')
            elif route in STATE_ROUTES:
                self.assertEqual(reply['state'], expected, f'{name}[{i}] {route}: the Java side expected {expected}, the ledger says {reply["state"]}')
        return store, replies

    def test_every_recorded_chain_is_accepted_in_order_with_the_same_states(self):
        names = [k for k in WIRE if k != 'contract']
        self.assertEqual(set(names), {'break_verified', 'break_hold_reconciled', 'pickup_partial', 'pickup_journal_failed_repaired',
                                      'break_cancelled', 'break_failed_before_effect', 'break_terminal_adopted_by_hash'})
        for name in names:
            with self.subTest(chain=name):
                self.replay(name)

    def test_the_end_states_are_the_expected_ones(self):
        ends = {'break_verified': 'EFFECT_VERIFIED', 'break_hold_reconciled': 'RECONCILED_EFFECT_OBSERVED', 'pickup_partial': 'EFFECT_PARTIAL',
                'pickup_journal_failed_repaired': 'EFFECT_VERIFIED', 'break_cancelled': 'CANCELLED_NO_EFFECT',
                'break_failed_before_effect': 'FAILED_NO_EFFECT', 'break_terminal_adopted_by_hash': 'EFFECT_VERIFIED'}
        for name, end in ends.items():
            with self.subTest(chain=name):
                store, _ = self.replay(name)
                steps = WIRE[name]['steps']
                reserve = next(s['body'] for s in steps if s['route'] == 'reserve')
                import sqlite3
                from contextlib import closing
                with closing(sqlite3.connect(store.db)) as db:
                    self.assertEqual(db.execute('SELECT state FROM cplus_wn_runs WHERE task_id=?', (reserve['task_id'],)).fetchone()[0], end)
                    self.assertEqual(db.execute("SELECT COUNT(*) FROM cplus_wn_claims WHERE state<>'RELEASED'").fetchone()[0], 0)

    def test_the_status_reply_carries_the_hash_java_matches_against(self):
        store, replies = self.replay('break_terminal_adopted_by_hash')
        status = [r for route, r in replies if route == 'status'][0]
        finalize = [s for s in WIRE['break_terminal_adopted_by_hash']['steps'] if s['route'] == 'finalize'][0]
        self.assertEqual(status['receipt_hash'], finalize['digest'])

    def test_the_canonical_form_is_byte_compatible_for_awkward_text(self):
        sample = {'b': 1, 'a': 'xé"\\\n\x01~\x7f', 'flag': True, 'list': [3, 'z'], 'big': 12345678901234}
        # Python's ensure_ascii escapes everything outside space..~, including DEL: the Java canonical form must do the same.
        self.assertEqual(_canonical(sample), '{"a":"x\\u00e9\\"\\\\\\n\\u0001~\\u007f","b":1,"big":12345678901234,"flag":true,"list":[3,"z"]}')

    def test_a_tampered_java_body_is_refused(self):
        steps = WIRE['break_verified']['steps']
        for field, value in (('origin', 'COMMAND'), ('action_kind', 'place_block')):
            tmp = tempfile.TemporaryDirectory()
            self.addCleanup(tmp.cleanup)
            store = NativeEffectStore(Path(tmp.name) / 'w.db')
            store.bind(steps[0]['body'])
            bad = json.loads(json.dumps(steps[1]['body']))
            bad[field] = value
            with self.assertRaises(GraphConflict):
                store.reserve(bad)


if __name__ == '__main__':
    unittest.main()
