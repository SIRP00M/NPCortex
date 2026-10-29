"""PAP-G2 player-action ledger (place_block / use_block): lifecycle, typed evidence, exact replay, HOLD/reconcile, restart sweep and
CROSS-STORE arbitration with the accepted native break/pickup ledger under real concurrency (enforcement, not shadow mode).

Offline evidence only: SQLite on a temporary file. Says nothing about Minecraft chunk storage or the real native calls.
"""
import tempfile
import threading
import unittest
from contextlib import closing
from pathlib import Path
import sqlite3

from npcortex_backend.cplus_e_graph import GraphConflict
from npcortex_backend.cplus_pap_store import PlayerActionStore
from npcortex_backend.cplus_wn_store import NativeEffectStore
from test_cplus_wn_store import block_target, ident, reserve_body, uid


def place_target(x=1, y=64, z=1, support=(1, 63, 1), face='up', block='minecraft:cobblestone', sha='a' * 64, slot=0, count=8):
    return {'kind': 'block', 'x': x, 'y': y, 'z': z, 'block_id': block, 'state_sha256': sha, 'support_x': support[0],
            'support_y': support[1], 'support_z': support[2], 'face': face, 'item_key': block, 'item_count': count,
            'selected_slot': slot}


def use_target(x=2, y=64, z=2, block='minecraft:lever', sha='c' * 64, face='north'):
    return {'kind': 'block', 'x': x, 'y': y, 'z': z, 'block_id': block, 'state_sha256': sha, 'face': face}


def pap_body(world, session, kind='place_block', actor=None, target=None, **over):
    b = reserve_body(world, session, kind, actor=actor, target=target or (place_target() if kind == 'place_block' else use_target()))
    b['authorization']['entry_id'] = 'owner-pap'
    b.update(over)
    return b


def place_evidence(b, **over):
    t = b['target']
    e = {'x': t['x'], 'y': t['y'], 'z': t['z'], 'before_block': 'minecraft:air', 'after_block': t['block_id'],
         'interaction': 'CONSUME', 'item_key': t['item_key'], 'item_before': t['item_count'], 'item_after': t['item_count'] - 1,
         'selected_slot': t['selected_slot'], 'journal': 'COMMITTED'}
    e.update(over)
    return e


def use_evidence(b, **over):
    t = b['target']
    e = {'x': t['x'], 'y': t['y'], 'z': t['z'], 'block_id': t['block_id'], 'state_before_sha256': t['state_sha256'],
         'state_after_sha256': 'd' * 64, 'interaction': 'SUCCESS', 'journal': 'NOT_REQUIRED'}
    e.update(over)
    return e


def receipt(b, result='EFFECT_VERIFIED', reason='', executed=True, evidence=None, **over):
    r = ident(b)
    if evidence is None:
        evidence = place_evidence(b) if b['action_kind'] == 'place_block' else use_evidence(b)
    r.update(schema_version=1, kind='player_action_receipt', action_kind=b['action_kind'], result=result, reason=reason,
             server_tick=11, executed=executed, evidence=evidence)
    r.update(over)
    return r


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'world.db'
        self.wn = NativeEffectStore(self.path)
        self.pa = PlayerActionStore(self.path)
        self.world, self.session = uid(), uid()
        self.wn.bind({'schema_version': 1, 'world_id': self.world, 'folder_key': 'World1', 'epoch': 0, 'session_id': self.session,
                      'contract': 'native-effects-v1'})

    def tearDown(self):
        self.tmp.cleanup()

    def res(self, kind='place_block', **kw):
        return pap_body(self.world, self.session, kind, **kw)

    def executing(self, kind='place_block', **kw):
        b = self.res(kind, **kw)
        self.pa.reserve(b)
        self.pa.start(ident(b, 'requester_id'))
        return b

    def sql(self, query, *args):
        with closing(sqlite3.connect(self.path)) as db:
            with db:
                return db.execute(query, args).fetchall()

    def state(self, b):
        return self.sql('SELECT state FROM cplus_pap_runs WHERE task_id=?', b['task_id'])[0][0]

    def claims(self, b):
        return [r[0] for r in self.sql('SELECT state FROM cplus_pap_claims WHERE task_id=?', b['task_id'])]


class Lifecycle(Base):
    def test_place_reserve_start_finalize(self):
        b = self.res()
        r = self.pa.reserve(b)
        self.assertEqual((r['state'], r['can_start'], r['action_kind']), ('RESERVED', True, 'place_block'))
        self.assertEqual(self.claims(b), ['ACTIVE', 'ACTIVE'])            # the placed cell AND its support block
        s = self.pa.start(ident(b, 'requester_id'))
        self.assertEqual((s['state'], s['execution_started']), ('EXECUTING', True))
        f = self.pa.finalize(receipt(b))
        self.assertEqual((f['state'], f['backend_commit_ack'], f['release_actor_fence']), ('EFFECT_VERIFIED', True, True))
        self.assertEqual(self.claims(b), ['RELEASED', 'RELEASED'])

    def test_use_lifecycle_and_no_effect(self):
        b = self.executing('use_block')
        self.assertEqual(self.claims(b), ['ACTIVE'])
        refused = use_evidence(b, state_after_sha256=b['target']['state_sha256'], interaction='PASS')
        f = self.pa.finalize(receipt(b, 'NO_EFFECT', 'USE_REFUSED', evidence=refused))
        self.assertEqual(f['state'], 'FAILED_NO_EFFECT')

    def test_exact_replay_of_request_and_terminal(self):
        b = self.res()
        first = self.pa.reserve(b)
        self.assertEqual(self.pa.reserve(b)['task_id'], first['task_id'])
        other = dict(b, task_id=uid())
        other = dict(other, target=place_target(x=9))
        with self.assertRaises(GraphConflict):
            self.pa.reserve(other if other['request_id'] == b['request_id'] else dict(other, request_id=b['request_id']))
        self.pa.start(ident(b, 'requester_id'))
        r = receipt(b)
        a1 = self.pa.finalize(r)
        a2 = self.pa.finalize(r)                                           # lost ACK: identical bytes, same stored answer
        self.assertEqual((a1['receipt_hash'], a1['state']), (a2['receipt_hash'], a2['state']))
        with self.assertRaises(GraphConflict):                              # different evidence is never re-judged
            self.pa.finalize(receipt(b, evidence=place_evidence(b, selected_slot=0, item_after=b['target']['item_count'] - 1, interaction='SUCCESS')))

    def test_start_is_replayable_and_cancel_only_before_it(self):
        b = self.res()
        self.pa.reserve(b)
        c = ident(b, 'requester_id')
        self.assertTrue(self.pa.start(c)['execution_started'])
        self.assertTrue(self.pa.start(c)['replay'])
        with self.assertRaises(GraphConflict):
            self.pa.cancel(dict(c, reason='OPERATOR'))
        b2 = self.res(actor=uid(), target=place_target(x=40, support=(40, 63, 40)))
        self.pa.reserve(b2)
        cancelled = self.pa.cancel(dict(ident(b2, 'requester_id'), reason='OPERATOR'))
        self.assertEqual(cancelled['state'], 'CANCELLED_NO_EFFECT')
        self.assertEqual(self.claims(b2), ['RELEASED', 'RELEASED'])

    def test_unbound_world_session_or_foreign_identity_is_refused(self):
        with self.assertRaises(GraphConflict):
            self.pa.reserve(pap_body(uid(), uid()))
        b = self.executing()
        with self.assertRaises(GraphConflict):
            self.pa.start(dict(ident(b, 'requester_id'), session_id=uid()))
        with self.assertRaises(GraphConflict):
            self.pa.finalize(receipt(b, task_id=uid()))


class Binding(Base):
    def test_start_after_the_world_was_rebound_is_refused(self):
        b = self.res()
        self.pa.reserve(b)
        self.wn.bind({'schema_version': 1, 'world_id': self.world, 'folder_key': 'World1', 'epoch': 1, 'session_id': uid(),
                      'contract': 'native-effects-v1'})
        with self.assertRaises(GraphConflict) as e:
            self.pa.start(ident(b, 'requester_id'))
        self.assertIn('BINDING_UNVERIFIED', str(e.exception))
        self.assertEqual(self.state(b), 'RESERVED')


class Evidence(Base):
    def verify_fails(self, b, evidence, **kw):
        with self.assertRaises(GraphConflict):
            self.pa.finalize(receipt(b, evidence=evidence, **kw))

    def test_place_needs_block_present_one_item_consumed_and_journal_committed(self):
        b = self.executing()
        self.verify_fails(b, place_evidence(b, item_after=b['target']['item_count']))              # nothing consumed
        self.verify_fails(b, place_evidence(b, after_block='minecraft:dirt'))                      # a different block
        self.verify_fails(b, place_evidence(b, before_block='minecraft:cobblestone'))              # nothing changed
        self.verify_fails(b, place_evidence(b, journal='NOT_REQUIRED'))                            # inventory change without a receipt
        self.verify_fails(b, place_evidence(b, interaction='PASS'))
        self.verify_fails(b, place_evidence(b, x=99))                                              # a different cell
        self.verify_fails(b, place_evidence(b, selected_slot=3))                                   # a different hotbar slot
        self.assertEqual(self.state(b), 'EXECUTING')

    def test_refused_place_must_leave_everything_unchanged(self):
        b = self.executing()
        quiet = place_evidence(b, before_block='minecraft:air', after_block='minecraft:air', interaction='FAIL',
                               item_after=b['target']['item_count'], journal='NOT_REQUIRED')
        self.verify_fails(b, dict(quiet, item_after=b['target']['item_count'] - 1), result='NO_EFFECT', reason='PLACE_REFUSED')
        self.verify_fails(b, quiet, result='NO_EFFECT', reason='BREAK_REFUSED')                    # the native reason is not ours
        self.assertEqual(self.pa.finalize(receipt(b, 'NO_EFFECT', 'PLACE_REFUSED', evidence=quiet))['state'], 'FAILED_NO_EFFECT')

    def test_use_needs_success_and_an_observed_state_change(self):
        b = self.executing('use_block')
        self.verify_fails(b, use_evidence(b, state_after_sha256=b['target']['state_sha256']))      # success but nothing changed
        self.verify_fails(b, use_evidence(b, interaction='PASS'))                                  # changed but not a success
        self.verify_fails(b, use_evidence(b, journal='COMMITTED'))                                 # a use never writes an inventory receipt
        self.verify_fails(b, use_evidence(b, block_id='minecraft:oak_button'))
        self.verify_fails(b, use_evidence(b, state_before_sha256='e' * 64))

    def test_precondition_failure_carries_empty_evidence(self):
        b = self.res()
        self.pa.reserve(b)
        self.verify_fails(b, {'x': 1}, result='PRECONDITION_FAILED', reason='TARGET_CHANGED', executed=False)
        f = self.pa.finalize(receipt(b, 'PRECONDITION_FAILED', 'TARGET_CHANGED', False, {}))        # RESERVED may fail directly
        self.assertEqual(f['state'], 'FAILED_NO_EFFECT')

    def test_target_shape_is_validated(self):
        for bad in (dict(place_target(), face='sideways'), dict(place_target(), item_count=0), dict(place_target(), selected_slot=9),
                    dict(place_target(), support_x=1, support_y=64, support_z=1), {**place_target(), 'extra': 1},
                    {k: v for k, v in place_target().items() if k != 'item_key'}):
            with self.assertRaises(GraphConflict):
                self.pa.reserve(self.res(target=bad))
        with self.assertRaises(GraphConflict):
            self.pa.reserve(self.res('use_block', target=dict(use_target(), kind='item')))
        with self.assertRaises(GraphConflict):
            self.pa.reserve(self.res(kind='break_block'))                                          # the native kinds are not ours


class HoldAndReconcile(Base):
    def hold(self, b, reason='INVENTORY_COMMIT_FAILED'):
        return self.pa.hold(dict(ident(b, 'requester_id'), reason=reason, intended={}))

    def test_hold_keeps_claims_and_fence_and_blocks_a_native_break(self):
        b = self.executing()
        h = self.hold(b)
        self.assertEqual((h['state'], h['durable_ack'], h['release_actor_fence']), ('HOLD_IN_DOUBT', True, False))
        self.assertEqual(self.claims(b), ['HELD', 'HELD'])
        self.assertEqual(self.hold(b)['hold_hash'], h['hold_hash'])                                # identical retry
        with self.assertRaises(GraphConflict):
            self.wn.reserve(reserve_body(self.world, self.session, 'break_block', target=block_target(1, 64, 1)))
        with self.assertRaises(GraphConflict):                                                     # the support cell is held as well
            self.wn.reserve(reserve_body(self.world, self.session, 'break_block', target=block_target(1, 63, 1)))
        with self.assertRaises(GraphConflict):                                                     # the same actor is still fenced
            self.pa.reserve(self.res(actor=b['actor_id'], target=place_target(x=50, support=(50, 63, 50))))

    def test_hold_never_turns_into_success_by_itself(self):
        b = self.executing()
        self.hold(b)
        with self.assertRaises(GraphConflict):
            self.pa.finalize(receipt(b))
        with self.assertRaises(GraphConflict):
            self.pa.cancel(dict(ident(b, 'requester_id'), reason='OPERATOR'))
        self.assertEqual(self.state(b), 'HOLD_IN_DOUBT')

    def test_reconcile_from_observed_state_releases_claims(self):
        b = self.executing()
        self.hold(b)
        t = b['target']
        observed = {'x': t['x'], 'y': t['y'], 'z': t['z'], 'block_id': t['block_id'], 'state_sha256': 'f' * 64}
        body = dict(ident(b, 'requester_id'), evidence_class='EFFECT_OBSERVED', observed=observed)
        with self.assertRaises(GraphConflict):                                                     # wrong class for that observation
            self.pa.reconcile(dict(body, evidence_class='PERSISTED_STATE_UNCHANGED'))
        with self.assertRaises(GraphConflict):                                                     # another requester
            self.pa.reconcile(dict(body, requester_id=uid()))
        done = self.pa.reconcile(body)
        self.assertEqual(done['state'], 'RECONCILED_EFFECT_OBSERVED')
        self.assertEqual(self.claims(b), ['RELEASED', 'RELEASED'])
        self.assertEqual(self.pa.reconcile(body)['receipt_hash'], done['receipt_hash'])
        self.pa.reserve(self.res(actor=b['actor_id'], target=place_target(x=51, support=(51, 63, 51))))   # the actor is free again

    def test_status_and_active_are_attempt_specific(self):
        b = self.executing()
        st = self.pa.status(ident(b, 'requester_id'))
        self.assertEqual((st['state'], st['active']), ('EXECUTING', True))
        self.assertEqual(self.pa.active({'world_id': self.world, 'actor_id': b['actor_id'], 'requester_id': b['requester_id']})['task_id'], b['task_id'])
        with self.assertRaises(GraphConflict):
            self.pa.active({'world_id': self.world, 'actor_id': b['actor_id'], 'requester_id': uid()})
        none = self.pa.status(ident(self.res(), 'requester_id'))
        self.assertEqual(none['state'], 'NONE')


class Restart(Base):
    def test_restart_sweep(self):
        reserved = self.res()
        self.pa.reserve(reserved)
        executing = self.executing(actor=uid(), target=place_target(x=7, support=(7, 63, 7)))
        again = PlayerActionStore(self.path)                                                       # a new process = a restart
        self.assertEqual(self.state(reserved), 'FAILED_NO_EFFECT')
        self.assertEqual(self.claims(reserved), ['RELEASED', 'RELEASED'])
        self.assertEqual(self.state(executing), 'HOLD_IN_DOUBT')
        self.assertEqual(self.claims(executing), ['HELD', 'HELD'])
        self.assertEqual(again.status(ident(executing, 'requester_id'))['state'], 'HOLD_IN_DOUBT')

    def test_a_new_session_sweeps_only_stale_rows_when_reserving(self):
        stale = self.executing()
        new_session = uid()
        self.wn.bind({'schema_version': 1, 'world_id': self.world, 'folder_key': 'World1', 'epoch': 1, 'session_id': new_session,
                      'contract': 'native-effects-v1'})
        self.pa.reserve(pap_body(self.world, new_session, actor=uid(), target=place_target(x=60, support=(60, 63, 60))))
        self.assertEqual(self.state(stale), 'HOLD_IN_DOUBT')


class CrossStore(Base):
    """Enforced arbitration between the accepted native ledger (break) and the PAP ledger (place/use)."""

    def wn_break(self, x, y, z, actor=None):
        b = reserve_body(self.world, self.session, 'break_block', actor=actor, target=block_target(x, y, z))
        self.wn.reserve(b)
        return b

    def test_break_blocks_place_on_the_same_cell_and_on_the_support_cell(self):
        self.wn_break(10, 63, 10)                                                                   # break the block we would stand on
        with self.assertRaises(GraphConflict) as e:
            self.pa.reserve(self.res(target=place_target(x=10, y=64, z=10, support=(10, 63, 10))))
        self.assertIn('CLAIM_CONFLICT', str(e.exception))
        self.wn_break(20, 64, 20)
        with self.assertRaises(GraphConflict):
            self.pa.reserve(self.res(target=place_target(x=20, y=64, z=20, support=(20, 63, 20))))

    def test_place_and_use_block_a_native_break(self):
        self.pa.reserve(self.res(target=place_target(x=30, y=64, z=30, support=(30, 63, 30))))
        for cell in ((30, 64, 30), (30, 63, 30)):
            with self.assertRaises(GraphConflict) as e:
                self.wn_break(*cell)
            self.assertIn('CLAIM_CONFLICT', str(e.exception))
        self.pa.reserve(self.res('use_block', target=use_target(x=31, y=64, z=30)))
        with self.assertRaises(GraphConflict):
            self.wn_break(31, 64, 30)

    def test_the_actor_fence_spans_both_stores_in_both_directions(self):
        actor = uid()
        self.wn_break(1, 64, 1, actor=actor)
        with self.assertRaises(GraphConflict) as e:                                                 # a DIFFERENT cell: only the fence can refuse
            self.pa.reserve(self.res(actor=actor, target=place_target(x=300, support=(300, 63, 300))))
        self.assertIn('owns this actor', str(e.exception))
        actor2 = uid()
        self.pa.reserve(self.res(actor=actor2, target=place_target(x=5, support=(5, 63, 5))))
        with self.assertRaises(GraphConflict):
            self.wn_break(8, 64, 8, actor=actor2)

    def test_released_claims_free_the_cell_for_the_other_kind(self):
        b = self.res(target=place_target(x=70, y=64, z=70, support=(70, 63, 70)))
        self.pa.reserve(b)
        self.pa.start(ident(b, 'requester_id'))
        self.pa.finalize(receipt(b))
        self.wn_break(70, 64, 70)                                                                   # now the native kind may have it

    def test_concurrent_race_has_exactly_one_winner_per_cell(self):
        """Both stores, two threads, a barrier, 60 independent cells: no round may produce zero or two winners."""
        wins = {'pap': 0, 'wn': 0}
        problems = []
        for round_no in range(60):
            x = 1000 + round_no
            barrier = threading.Barrier(2)
            outcome = {}

            def do_pap():
                barrier.wait()
                try:
                    self.pa.reserve(self.res(target=place_target(x=x, y=64, z=5, support=(x, 63, 5))))
                    outcome['pap'] = 'won'
                except GraphConflict as exc:
                    outcome['pap'] = str(exc)

            def do_wn():
                barrier.wait()
                try:
                    self.wn.reserve(reserve_body(self.world, self.session, 'break_block', target=block_target(x, 63, 5)))
                    outcome['wn'] = 'won'
                except GraphConflict as exc:
                    outcome['wn'] = str(exc)

            threads = [threading.Thread(target=do_pap), threading.Thread(target=do_wn)]
            [t.start() for t in threads]
            [t.join() for t in threads]
            winners = [k for k, v in outcome.items() if v == 'won']
            if len(winners) != 1:
                problems.append((round_no, outcome))
            else:
                wins[winners[0]] += 1
            active = self.sql("SELECT (SELECT count(*) FROM cplus_pap_claims WHERE bx=? AND by=63 AND bz=5 AND state IN ('ACTIVE','HELD')) "
                              "+ (SELECT count(*) FROM cplus_wn_claims WHERE bx=? AND by=63 AND bz=5 AND state IN ('ACTIVE','HELD'))", x, x)[0][0]
            if active != 1:
                problems.append((round_no, 'claims', active))
        self.assertEqual(problems, [])
        self.assertEqual(wins['pap'] + wins['wn'], 60)

    def test_concurrent_same_cell_among_pap_actions(self):
        results = []
        barrier = threading.Barrier(6)

        def worker():
            barrier.wait()
            try:
                self.pa.reserve(self.res(actor=uid(), target=place_target(x=2000, y=64, z=2000, support=(2000, 63, 2000))))
                results.append('won')
            except GraphConflict:
                results.append('lost')
        ts = [threading.Thread(target=worker) for _ in range(6)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(results.count('won'), 1)


class Schema(Base):
    def test_native_tables_are_not_altered_and_constraints_are_additive(self):
        cols = {r[1] for r in self.sql('PRAGMA table_info(cplus_wn_runs)')}
        self.assertIn('action_kind', cols)
        ddl = self.sql("SELECT sql FROM sqlite_master WHERE name='cplus_wn_runs'")[0][0]
        self.assertIn("('break_block','pickup_item')", ddl)                                          # untouched
        self.assertNotIn('place_block', ddl)
        self.assertEqual({r[0] for r in self.sql("SELECT name FROM sqlite_master WHERE name LIKE 'cplus_pap_%' AND type='table'")},
                         {'cplus_pap_runs', 'cplus_pap_terminals', 'cplus_pap_holds', 'cplus_pap_claims'})


if __name__ == '__main__':
    unittest.main()
