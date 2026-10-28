"""Stage 4 NATIVE world-effect ledger (break_block / pickup_item): binding, state machine, claims, fences, idempotency,
restart, hold/repair/reconcile, attempt-specific status, schema compatibility.

Offline evidence only: SQLite on a temporary file. Says nothing about Minecraft chunk storage, the real native calls or the
crash safety of a real world effect.
"""
import copy
import shutil
import sqlite3
import tempfile
import threading
import unittest
from contextlib import closing
from pathlib import Path
from uuid import uuid4

from npcortex_backend.cplus_e3_g2_motion import GuardedMotionStore
from npcortex_backend.cplus_e_graph import GraphConflict
from npcortex_backend.cplus_m04_store import M04Blocked, M04Store
from npcortex_backend.cplus_we_ledger import WorldEffectStore
from npcortex_backend.cplus_wn_store import NativeEffectStore, _digest
from test_cplus_e3_g2_motion import body as g2_body

FIXTURES = Path(__file__).parent / 'fixtures'


def uid():
    return str(uuid4())


def block_target(x=1, y=64, z=1, block='minecraft:oak_log', sha='a' * 64):
    return {'kind': 'block', 'x': x, 'y': y, 'z': z, 'block_id': block, 'state_sha256': sha}


def item_target(entity=None, x=5.5, y=64.0, z=5.5, key='minecraft:oak_log', count=4, pre=0):
    return {'kind': 'item', 'entity_uuid': entity or uid(), 'item_key': key, 'nbt_sha256': 'e' * 64, 'count': count,
            'x': x, 'y': y, 'z': z, 'pre_count': pre}


def reserve_body(world, session, kind='break_block', actor=None, target=None, **over):
    b = {'schema_version': 1, 'task_id': uid(), 'request_id': uid(), 'attempt_id': uid(), 'world_id': world,
         'actor_id': actor or uid(), 'profile_id': uid(), 'session_id': session, 'requester_id': uid(),
         'dimension_id': 'minecraft:overworld', 'action_kind': kind, 'origin': 'MANAGER_PACKET',
         'authorization': {'decision': 'ALLOW', 'entry_id': 'owner-native', 'policy_sha256': 'b' * 64},
         'target': target or (block_target() if kind == 'break_block' else item_target())}
    b.update(over)
    return b


def ident(b, *extra):
    return {k: b[k] for k in ('task_id', 'attempt_id', 'world_id', 'actor_id', 'session_id') + tuple(extra)}


def break_evidence(b, **over):
    t = b['target']
    e = {'x': t['x'], 'y': t['y'], 'z': t['z'], 'before_block': t['block_id'], 'after_block': 'minecraft:air',
         'destroy_returned': True, 'tool_before': 'minecraft:air', 'tool_after': 'minecraft:air', 'tool_broke': False,
         'drops': [{'uuid': uid(), 'item': 'minecraft:oak_log', 'count': 1}], 'collateral': 'NONE', 'journal': 'NOT_REQUIRED'}
    e.update(over)
    return e


def pickup_evidence(b, **over):
    t = b['target']
    e = {'entity_uuid': t['entity_uuid'], 'item_key': t['item_key'], 'entity_count_before': 4, 'entity_count_after': 0,
         'delta': 4, 'event_count': 4, 'pre_count': t['pre_count'], 'post_count': t['pre_count'] + 4, 'journal': 'COMMITTED'}
    e.update(over)
    return e


def receipt(b, result='EFFECT_VERIFIED', reason='', executed=True, evidence=None, **over):
    r = ident(b)
    if evidence is None:
        evidence = break_evidence(b) if b['action_kind'] == 'break_block' else pickup_evidence(b)
    r.update(schema_version=1, kind='native_effect_receipt', action_kind=b['action_kind'], result=result, reason=reason,
             server_tick=11, executed=executed, evidence=evidence)
    r.update(over)
    return r


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'world.db'
        self.s = NativeEffectStore(self.path)
        self.world, self.session = uid(), uid()
        self.bind(self.world, self.session)

    def tearDown(self):
        self.tmp.cleanup()

    def bind(self, world, session, folder='World1', epoch=0):
        return self.s.bind({'schema_version': 1, 'world_id': world, 'folder_key': folder, 'epoch': epoch,
                            'session_id': session, 'contract': 'native-effects-v1'})

    def res(self, kind='break_block', **kw):
        return reserve_body(self.world, self.session, kind, **kw)

    def run_to_executing(self, kind='break_block', **kw):
        b = self.res(kind, **kw)
        self.s.reserve(b)
        self.s.start(ident(b, 'requester_id'))
        return b

    def sql(self, query, *args):
        with closing(sqlite3.connect(self.path)) as db:
            with db:
                return db.execute(query, args).fetchall()

    def state(self, b):
        return self.sql('SELECT state FROM cplus_wn_runs WHERE task_id=?', b['task_id'])[0][0]

    def claim_states(self, b):
        return [r[0] for r in self.sql('SELECT state FROM cplus_wn_claims WHERE task_id=?', b['task_id'])]


class Binding(Base):
    def test_first_bind_advances_and_reports_the_capability(self):
        w = uid()
        r = self.bind(w, uid())
        self.assertEqual((r['state'], r['epoch'], r['how']), ('BOUND', 1, 'FIRST_BIND'))
        self.assertEqual(r['capabilities'], ['native-effects-v1'])
        r2 = self.bind(w, uid(), epoch=1)
        self.assertEqual((r2['epoch'], r2['how']), (2, 'ADVANCED'))

    def test_replay_in_the_same_session_is_idempotent(self):
        w, s = uid(), uid()
        first = self.bind(w, s)
        again = self.bind(w, s)
        self.assertEqual(first['epoch'], again['epoch'])
        self.assertEqual(again['how'], 'REPLAY')

    def test_refusal_cases_write_nothing_to_existing_rows(self):
        w = uid()
        self.bind(w, uid())                                                 # epoch 1
        self.bind(w, uid(), epoch=1)                                        # epoch 2
        before = self.sql('SELECT * FROM cplus_wn_bindings WHERE world_id=?', w)
        for name, kw in (('folder', dict(folder='Copy of World1', epoch=2)), ('ahead', dict(epoch=9)),
                         ('regression', dict(epoch=1))):
            with self.assertRaises(GraphConflict, msg=name):
                self.bind(w, uid(), **kw)
        self.assertEqual(self.sql('SELECT * FROM cplus_wn_bindings WHERE world_id=?', w), before)
        kinds = {r[0] for r in self.sql('SELECT kind FROM cplus_wn_binding_events WHERE world_id=?', w)}
        self.assertTrue({'REFUSED_FOLDER_MISMATCH', 'REFUSED_WORLD_AHEAD', 'REFUSED_WORLD_REGRESSION'} <= kinds)

    def test_world_with_an_epoch_against_a_database_that_never_saw_it_is_refused(self):
        with self.assertRaises(GraphConflict):
            self.bind(uid(), uid(), epoch=3)

    def test_crash_lag_is_tolerated_only_at_the_save_confirmed_epoch(self):
        w, s1 = uid(), uid()
        self.bind(w, s1)                                                    # backend epoch 1, world will carry 1
        self.s.saved({'schema_version': 1, 'world_id': w, 'session_id': s1, 'epoch': 1})
        s2 = uid()
        self.bind(w, s2, epoch=1)                                           # backend epoch 2 (world never saved it)
        s3 = uid()
        r = self.bind(w, s3, epoch=1)                                       # crash: the world still says 1 == saved epoch
        self.assertEqual(r['how'], 'LAG_TOLERATED')
        with self.assertRaises(GraphConflict):                              # an older backup (epoch 0) is a regression
            self.bind(w, uid(), epoch=0)

    def test_copy_cannot_bind_before_or_after_the_original(self):
        w = uid()
        self.bind(w, uid(), folder='World1')
        with self.assertRaises(GraphConflict):                              # a copy in another folder, same SavedData
            self.bind(w, uid(), folder='World1 - Copy', epoch=1)
        self.bind(w, uid(), folder='World1', epoch=1)                       # the original is unaffected

    def test_rebind_moves_the_folder_and_keeps_every_row(self):
        w, s = uid(), uid()
        self.bind(w, s)
        b = reserve_body(w, s)
        self.s.reserve(b)
        self.s.rebind({'schema_version': 1, 'world_id': w, 'folder_key': 'Renamed', 'confirm': 'REBIND', 'reason': 'operator renamed'})
        self.assertEqual(self.state(b), 'RESERVED')
        self.assertEqual(self.bind(w, uid(), folder='Renamed', epoch=1)['state'], 'BOUND')
        with self.assertRaises(GraphConflict):
            self.s.rebind({'schema_version': 1, 'world_id': w, 'folder_key': 'x', 'confirm': 'yes', 'reason': 'r'})

    def test_reserve_needs_a_binding_for_the_current_session(self):
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(uid(), uid()))                      # unbound world
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(self.world, uid()))                 # another session

    def test_unknown_bind_fields_and_contracts_are_refused(self):
        with self.assertRaises(GraphConflict):
            self.s.bind({'schema_version': 1, 'world_id': uid(), 'folder_key': 'w', 'epoch': 0, 'session_id': uid(),
                         'contract': 'something-else'})
        with self.assertRaises(GraphConflict):
            self.s.bind({'schema_version': 1, 'world_id': uid(), 'folder_key': '../x\n', 'epoch': 0, 'session_id': uid(),
                         'contract': 'native-effects-v1'})


class BreakStateMachine(Base):
    def test_happy_break(self):
        b = self.res()
        self.assertEqual(self.s.reserve(b)['state'], 'RESERVED')
        self.assertTrue(self.s.start(ident(b, 'requester_id'))['execution_started'])
        done = self.s.finalize(receipt(b))
        self.assertEqual((done['state'], done['backend_commit_ack'], done['release_actor_fence']), ('EFFECT_VERIFIED', True, True))
        self.assertEqual(self.claim_states(b), ['RELEASED'])

    def test_reserve_is_idempotent_and_conflicting_replays_are_refused(self):
        b = self.res()
        first = self.s.reserve(b)
        self.assertEqual(self.s.reserve(b)['task_id'], first['task_id'])
        other = copy.deepcopy(b)
        other['target']['x'] = 9
        with self.assertRaises(GraphConflict):
            self.s.reserve(other)

    def test_start_and_finalize_replays_return_the_stored_result(self):
        b = self.run_to_executing()
        self.assertTrue(self.s.start(ident(b, 'requester_id'))['replay'])
        r = receipt(b)
        a = self.s.finalize(r)
        again = self.s.finalize(r)
        self.assertEqual(a['receipt_hash'], again['receipt_hash'])
        self.assertEqual(a['receipt_hash'], _digest(r))

    def test_a_terminal_is_never_rejudged(self):
        b = self.run_to_executing()
        self.s.finalize(receipt(b))
        refused = receipt(b, result='NO_EFFECT', reason='BREAK_REFUSED', evidence=break_evidence(b, destroy_returned=False, after_block='minecraft:oak_log'))
        with self.assertRaises(GraphConflict):
            self.s.finalize(refused)

    def test_refused_break_is_failed_no_effect_not_effect(self):
        b = self.run_to_executing()
        ev = break_evidence(b, destroy_returned=False, after_block=b['target']['block_id'])
        for k in ('tool_before', 'tool_after', 'tool_broke', 'drops', 'collateral', 'journal'):
            ev.pop(k)
        self.assertEqual(self.s.finalize(receipt(b, 'NO_EFFECT', 'BREAK_REFUSED', evidence=ev))['state'], 'FAILED_NO_EFFECT')

    def test_inconsistent_evidence_is_rejected(self):
        b = self.run_to_executing()
        for bad in (break_evidence(b, destroy_returned=False),                       # claims a break the call denies
                    break_evidence(b, before_block='minecraft:stone'),               # a different block than previewed
                    break_evidence(b, x=99),                                         # another position
                    break_evidence(b, drops=[{'uuid': 'x', 'item': 'minecraft:oak_log', 'count': 1}]),
                    break_evidence(b, collateral='UNEXPECTED')):
            with self.assertRaises(GraphConflict):
                self.s.finalize(receipt(b, evidence=bad))
        self.assertEqual(self.state(b), 'EXECUTING')

    def test_failure_needs_executed_false_and_empty_evidence_and_a_known_reason(self):
        b = self.res()
        self.s.reserve(b)
        ok = receipt(b, 'PRECONDITION_FAILED', 'START_ACK_LOST', executed=False, evidence={})
        for bad in (dict(ok, executed=True), dict(ok, reason='NO_SUCH_REASON'), dict(ok, evidence={'x': 1})):
            with self.assertRaises(GraphConflict):
                self.s.finalize(bad)
        self.assertEqual(self.s.finalize(ok)['state'], 'FAILED_NO_EFFECT')

    def test_an_effect_receipt_cannot_be_recorded_before_the_marker(self):
        b = self.res()
        self.s.reserve(b)
        with self.assertRaises(GraphConflict):
            self.s.finalize(receipt(b))
        self.assertEqual(self.state(b), 'RESERVED')

    def test_cancel_only_before_the_marker(self):
        b = self.res()
        self.s.reserve(b)
        c = dict(ident(b, 'requester_id'), reason='OPERATOR')
        self.assertEqual(self.s.cancel(c)['state'], 'CANCELLED_NO_EFFECT')
        self.assertEqual(self.claim_states(b), ['RELEASED'])
        started = self.run_to_executing()
        with self.assertRaises(GraphConflict):
            self.s.cancel(dict(ident(started, 'requester_id'), reason='OPERATOR'))
        self.assertEqual(self.state(started), 'EXECUTING')

    def test_foreign_requester_and_stale_identity_are_refused(self):
        b = self.res()
        self.s.reserve(b)
        with self.assertRaises(GraphConflict):
            self.s.start(dict(ident(b, 'requester_id'), requester_id=uid()))
        with self.assertRaises(GraphConflict):
            self.s.start(dict(ident(b, 'requester_id'), session_id=uid()))

    def test_unknown_fields_and_wrong_types_are_refused(self):
        b = self.res()
        for bad in (dict(b, extra=1), dict(b, schema_version=True), dict(b, action_kind='place_block'), dict(b, origin='COMMAND')):
            with self.assertRaises(GraphConflict):
                self.s.reserve(bad)
        t = copy.deepcopy(b)
        t['target']['x'] = 1.5
        with self.assertRaises(GraphConflict):
            self.s.reserve(t)
        d = copy.deepcopy(b)
        d['authorization']['decision'] = 'DENY'
        with self.assertRaises(GraphConflict):
            self.s.reserve(d)


class PickupEvidence(Base):
    def test_verified_partial_and_no_transfer(self):
        b = self.run_to_executing('pickup_item', target=item_target(pre=3))
        self.assertEqual(self.s.finalize(receipt(b))['state'], 'EFFECT_VERIFIED')
        p = self.run_to_executing('pickup_item', target=item_target(pre=3))
        ev = pickup_evidence(p, entity_count_after=2, delta=2, event_count=2, post_count=5)
        self.assertEqual(self.s.finalize(receipt(p, 'EFFECT_PARTIAL', evidence=ev))['state'], 'EFFECT_PARTIAL')
        n = self.run_to_executing('pickup_item', target=item_target(pre=3))
        ev = pickup_evidence(n, entity_count_after=4, delta=0, event_count=0, post_count=3, journal='NOT_REQUIRED')
        self.assertEqual(self.s.finalize(receipt(n, 'NO_EFFECT', 'NO_TRANSFER', evidence=ev))['state'], 'FAILED_NO_EFFECT')

    def test_a_partial_transfer_is_witnessed_by_inventory_and_entity_the_event_may_be_zero(self):
        """Vanilla (found by the real GameTest) fires NO pickup event for a partial Inventory.add; the contract must accept that."""
        for event in (0, 2):
            p = self.run_to_executing('pickup_item', target=item_target(pre=3, x=5.5 + 10 * event, z=5.5 + 10 * event))
            ev = pickup_evidence(p, entity_count_after=2, delta=2, event_count=event, post_count=5)
            self.assertEqual(self.s.finalize(receipt(p, 'EFFECT_PARTIAL', evidence=ev))['state'], 'EFFECT_PARTIAL')
        q = self.run_to_executing('pickup_item', target=item_target(pre=3, x=90.5, z=90.5))
        for bad in (pickup_evidence(q, entity_count_after=2, delta=2, event_count=1, post_count=5),      # an event that matches nothing
                    pickup_evidence(q, entity_count_after=2, delta=3, event_count=0, post_count=6),      # inventory grew more than the entity shrank
                    pickup_evidence(q, entity_count_after=0, delta=4, event_count=0, post_count=7)):     # a full transfer is not partial
            with self.assertRaises(GraphConflict, msg=str(bad)):
                self.s.finalize(receipt(q, 'EFFECT_PARTIAL', evidence=bad))
        # but a FULL transfer still needs the event
        with self.assertRaises(GraphConflict):
            self.s.finalize(receipt(q, evidence=pickup_evidence(q, event_count=0)))

    def test_witnesses_that_disagree_are_never_a_success_or_a_no_effect(self):
        b = self.run_to_executing('pickup_item', target=item_target(pre=3))
        cases = [
            ('EFFECT_VERIFIED', '', pickup_evidence(b, event_count=3)),                       # event disagrees
            ('EFFECT_VERIFIED', '', pickup_evidence(b, post_count=6)),                        # inventory disagrees
            ('EFFECT_VERIFIED', '', pickup_evidence(b, entity_count_after=1)),                # entity disagrees
            ('EFFECT_VERIFIED', '', pickup_evidence(b, journal='NOT_REQUIRED')),              # the journal is not committed
            ('EFFECT_PARTIAL', '', pickup_evidence(b)),                                       # a full transfer is not partial
            ('NO_EFFECT', 'NO_TRANSFER', pickup_evidence(b, delta=0, event_count=1, entity_count_after=4, post_count=3)),
            ('NO_EFFECT', 'NO_TRANSFER', pickup_evidence(b, delta=0, event_count=0, entity_count_after=3, post_count=3)),
        ]
        for result, reason, ev in cases:
            with self.assertRaises(GraphConflict, msg=str(ev)):
                self.s.finalize(receipt(b, result, reason, evidence=ev))
        self.assertEqual(self.state(b), 'EXECUTING')

    def test_a_break_cannot_be_partial_and_a_pickup_receipt_needs_its_own_item(self):
        b = self.run_to_executing()
        with self.assertRaises(GraphConflict):
            self.s.finalize(receipt(b, 'EFFECT_PARTIAL'))
        p = self.run_to_executing('pickup_item')
        with self.assertRaises(GraphConflict):
            self.s.finalize(receipt(p, evidence=pickup_evidence(p, entity_uuid=uid())))


class Claims(Base):
    def test_same_block_is_exclusive_adjacent_blocks_are_independent(self):
        self.s.reserve(self.res(target=block_target(1, 64, 1)))
        with self.assertRaises(GraphConflict):
            self.s.reserve(self.res(target=block_target(1, 64, 1)))
        self.assertEqual(self.s.reserve(self.res(target=block_target(2, 64, 1)))['state'], 'RESERVED')
        self.assertEqual(self.s.reserve(self.res(target=block_target(1, 65, 1)))['state'], 'RESERVED')

    def test_other_dimensions_and_worlds_do_not_share_cells(self):
        self.s.reserve(self.res())
        nether = self.res(dimension_id='minecraft:the_nether')
        self.assertEqual(self.s.reserve(nether)['state'], 'RESERVED')
        w2, s2 = uid(), uid()
        self.bind(w2, s2)
        self.assertEqual(self.s.reserve(reserve_body(w2, s2))['state'], 'RESERVED')

    def test_a_released_claim_does_not_block_and_a_refused_reserve_leaves_no_claim(self):
        b = self.run_to_executing()
        with self.assertRaises(GraphConflict):
            self.s.reserve(self.res())
        self.assertEqual(self.sql('SELECT COUNT(*) FROM cplus_wn_claims')[0][0], 1)
        self.s.finalize(receipt(b))
        self.assertEqual(self.s.reserve(self.res())['state'], 'RESERVED')

    def test_hold_keeps_the_claim_blocking_other_actors(self):
        b = self.run_to_executing()
        self.s.hold(dict(ident(b, 'requester_id'), reason='EXECUTOR_EXCEPTION', intended={}))
        self.assertEqual(self.claim_states(b), ['HELD'])
        with self.assertRaises(GraphConflict):
            self.s.reserve(self.res())

    def test_one_item_entity_one_owner_and_the_pile_rule_is_conservative(self):
        e = uid()
        self.s.reserve(self.res('pickup_item', target=item_target(e, 5.5, 64.0, 5.5)))
        with self.assertRaises(GraphConflict):                                   # same entity
            self.s.reserve(self.res('pickup_item', target=item_target(e, 30.5, 64.0, 30.5)))
        with self.assertRaises(GraphConflict):                                   # a different stack of the same item that could merge
            self.s.reserve(self.res('pickup_item', target=item_target(uid(), 6.5, 64.4, 5.9)))
        self.assertEqual(self.s.reserve(self.res('pickup_item', target=item_target(uid(), 9.5, 64.0, 5.5)))['state'], 'RESERVED')
        self.assertEqual(self.s.reserve(self.res('pickup_item', target=item_target(uid(), 5.5, 64.0, 5.5, key='minecraft:stick')))['state'], 'RESERVED')

    def test_a_held_pickup_blocks_a_second_actor_after_a_restart_sweep(self):
        e = uid()
        b = self.run_to_executing('pickup_item', target=item_target(e))
        s2 = uid()
        self.bind(self.world, s2, epoch=1)                                       # the game restarted: the new session sweeps
        self.assertEqual(self.state(b), 'HOLD_IN_DOUBT')
        self.assertEqual(self.claim_states(b), ['HELD'])
        other = reserve_body(self.world, s2, 'pickup_item', target=item_target(e))
        with self.assertRaises(GraphConflict):
            self.s.reserve(other)
        near = reserve_body(self.world, s2, 'pickup_item', target=item_target(uid(), 5.9, 64.0, 5.9))
        with self.assertRaises(GraphConflict):                                   # a merged/moved entity is still fenced by the pile rule
            self.s.reserve(near)

    def test_claims_are_independent_of_the_actor_fence_for_distinct_actors(self):
        a = self.res(actor=uid(), target=block_target(1, 64, 1))
        b = self.res(actor=uid(), target=block_target(40, 64, 40))
        self.s.reserve(a)
        self.s.reserve(b)
        with self.assertRaises(GraphConflict):                                   # the SAME actor cannot hold two attempts
            self.s.reserve(self.res(actor=a['actor_id'], target=block_target(3, 64, 3)))

    def test_concurrent_reservations_of_one_block_admit_exactly_one(self):
        wins, errors = [], []
        barrier = threading.Barrier(12)

        def attempt():
            store = NativeEffectStore(self.path)
            body = self.res(target=block_target(7, 70, 7))
            barrier.wait()
            try:
                store.reserve(body)
                wins.append(body['task_id'])
            except GraphConflict:
                pass
            except Exception as exc:                                              # pragma: no cover - would be a defect
                errors.append(repr(exc))
        threads = [threading.Thread(target=attempt) for _ in range(12)]
        [t.start() for t in threads]
        [t.join() for t in threads]
        self.assertEqual(errors, [])
        self.assertEqual(len(wins), 1)
        self.assertEqual(self.sql("SELECT COUNT(*) FROM cplus_wn_claims WHERE state='ACTIVE'")[0][0], 1)

    def test_concurrent_pickups_of_one_entity_admit_exactly_one(self):
        entity = uid()
        wins, errors = [], []
        barrier = threading.Barrier(10)

        def attempt():
            store = NativeEffectStore(self.path)
            body = self.res('pickup_item', target=item_target(entity))
            barrier.wait()
            try:
                store.reserve(body)
                wins.append(1)
            except GraphConflict:
                pass
            except Exception as exc:                                              # pragma: no cover
                errors.append(repr(exc))
        threads = [threading.Thread(target=attempt) for _ in range(10)]
        [t.start() for t in threads]
        [t.join() for t in threads]
        self.assertEqual((errors, len(wins)), ([], 1))


class RestartAndSweep(Base):
    def test_a_dead_session_releases_reserved_claims_and_holds_executing_ones(self):
        r = self.res(target=block_target(1, 64, 1))
        self.s.reserve(r)
        e = self.run_to_executing(target=block_target(2, 64, 2))
        s2 = uid()
        self.bind(self.world, s2, epoch=1)
        same_actor_new_session = reserve_body(self.world, s2, actor=r['actor_id'], target=block_target(5, 64, 5))
        self.s.reserve(same_actor_new_session)                                    # the new session swept the old one
        self.assertEqual(self.state(r), 'FAILED_NO_EFFECT')
        self.assertEqual(self.claim_states(r), ['RELEASED'])
        self.assertEqual(self.sql('SELECT outcome FROM cplus_wn_terminals WHERE task_id=?', r['task_id'])[0][0], 'FAILED_NO_EFFECT')
        again = reserve_body(self.world, s2, actor=e['actor_id'], target=block_target(8, 64, 8))
        with self.assertRaises(GraphConflict):                                    # still fenced: HOLD_IN_DOUBT
            self.s.reserve(again)
        self.assertEqual(self.state(e), 'HOLD_IN_DOUBT')
        self.assertEqual(self.claim_states(e), ['HELD'])
        self.assertEqual(self.sql('SELECT COUNT(*) FROM cplus_wn_terminals WHERE task_id=?', e['task_id'])[0][0], 0)
        with self.assertRaises(GraphConflict):                                    # and the cell stays claimed
            self.s.reserve(reserve_body(self.world, s2, target=block_target(2, 64, 2)))

    def test_reopening_the_store_settles_dead_sessions_without_deleting_anything(self):
        e = self.run_to_executing()
        before = self.sql('SELECT COUNT(*) FROM cplus_wn_runs')[0][0]
        NativeEffectStore(self.path)
        self.assertEqual(self.sql('SELECT COUNT(*) FROM cplus_wn_runs')[0][0], before)
        # A backend (re)start settles EVERY unfinished native attempt conservatively (the same rule as the no-op ledger):
        # the executor cannot be proven to be running, so EXECUTING becomes HOLD_IN_DOUBT, never success and never a replay.
        self.assertEqual(self.state(e), 'HOLD_IN_DOUBT')
        self.assertEqual(self.claim_states(e), ['HELD'])


class HoldRepairReconcile(Base):
    def intended(self, b, **over):
        return receipt(b, evidence=pickup_evidence(b, **over))

    def test_hold_is_idempotent_and_different_evidence_is_refused(self):
        b = self.run_to_executing()
        h = dict(ident(b, 'requester_id'), reason='EXECUTOR_EXCEPTION', intended={})
        first = self.s.hold(h)
        self.assertEqual((first['state'], first['release_actor_fence']), ('HOLD_IN_DOUBT', False))
        self.assertEqual(self.s.hold(h)['hold_hash'], first['hold_hash'])
        with self.assertRaises(GraphConflict):
            self.s.hold(dict(h, reason='AMBIGUOUS_EVIDENCE'))

    def test_hold_cannot_start_from_reserved_and_foreign_requesters_are_refused(self):
        b = self.res()
        self.s.reserve(b)
        with self.assertRaises(GraphConflict):
            self.s.hold(dict(ident(b, 'requester_id'), reason='EXECUTOR_EXCEPTION', intended={}))
        e = self.run_to_executing(target=block_target(30, 64, 30))
        with self.assertRaises(GraphConflict):
            self.s.hold(dict(ident(e, 'requester_id'), requester_id=uid(), reason='EXECUTOR_EXCEPTION', intended={}))

    def test_persist_only_repair_uses_the_stored_intended_receipt_and_nothing_else(self):
        b = self.run_to_executing('pickup_item', target=item_target(pre=2))
        intended = receipt(b, evidence=pickup_evidence(b, post_count=6))
        self.s.hold(dict(ident(b, 'requester_id'), reason='INVENTORY_COMMIT_FAILED', intended=intended))
        self.assertEqual(self.state(b), 'HOLD_IN_DOUBT')
        with self.assertRaises(GraphConflict):                                    # a hash nobody recorded
            self.s.repair(dict(ident(b, 'requester_id'), intended_sha256='f' * 64))
        done = self.s.repair(dict(ident(b, 'requester_id'), intended_sha256=_digest(intended)))
        self.assertEqual((done['state'], done['receipt_hash']), ('EFFECT_VERIFIED', _digest(intended)))
        self.assertEqual(self.claim_states(b), ['RELEASED'])
        again = self.s.repair(dict(ident(b, 'requester_id'), intended_sha256=_digest(intended)))
        self.assertEqual(again['receipt_hash'], done['receipt_hash'])

    def test_repair_is_impossible_for_other_hold_reasons_and_an_intended_receipt_must_be_a_committed_effect(self):
        b = self.run_to_executing('pickup_item', target=item_target(pre=2))
        self.s.hold(dict(ident(b, 'requester_id'), reason='AMBIGUOUS_EVIDENCE', intended={}))
        with self.assertRaises(GraphConflict):
            self.s.repair(dict(ident(b, 'requester_id'), intended_sha256='a' * 64))
        c = self.run_to_executing('pickup_item', target=item_target(pre=2, x=50.5, z=50.5))
        with self.assertRaises(GraphConflict):
            self.s.hold(dict(ident(c, 'requester_id'), reason='INVENTORY_COMMIT_FAILED',
                             intended=receipt(c, evidence=pickup_evidence(c, journal='NOT_REQUIRED', delta=4))))
        with self.assertRaises(GraphConflict):
            self.s.hold(dict(ident(c, 'requester_id'), reason='EXECUTOR_EXCEPTION', intended=self.intended(c)))

    def reconcile(self, b, klass, observed, requester=None, session=None):
        return self.s.reconcile(dict(ident(b, 'requester_id'), session_id=session or b['session_id'],
                                     requester_id=requester or b['requester_id'], evidence_class=klass, observed=observed))

    def test_reconcile_classes_follow_the_observation_and_never_say_no_effect(self):
        b = self.run_to_executing('pickup_item')
        self.s.hold(dict(ident(b, 'requester_id'), reason='AMBIGUOUS_EVIDENCE', intended={}))
        with self.assertRaises(GraphConflict):                                    # receipt absent + entity gone = loss possible
            self.reconcile(b, 'PERSISTED_STATE_UNCHANGED', {'journal': 'ABSENT', 'entity': 'ABSENT'})
        with self.assertRaises(GraphConflict):                                    # receipt present + entity present = duplication
            self.reconcile(b, 'PERSISTED_TRANSFER', {'journal': 'PRESENT', 'entity': 'PRESENT_UNCHANGED'})
        with self.assertRaises(GraphConflict):
            self.reconcile(b, 'NO_EFFECT', {'journal': 'ABSENT', 'entity': 'PRESENT_UNCHANGED'})
        done = self.reconcile(b, 'PERSISTED_STATE_UNCHANGED', {'journal': 'ABSENT', 'entity': 'PRESENT_UNCHANGED'})
        self.assertEqual(done['state'], 'RECONCILED_PERSISTED_STATE_UNCHANGED')
        self.assertEqual(self.claim_states(b), ['RELEASED'])
        c = self.run_to_executing('pickup_item')
        self.assertEqual(self.reconcile(c, 'PERSISTED_TRANSFER', {'journal': 'PRESENT', 'entity': 'ABSENT'})['state'],
                         'RECONCILED_PERSISTED_TRANSFER')

    def test_break_reconcile_compares_the_observed_block_state(self):
        b = self.run_to_executing()
        t = b['target']
        same = {'x': t['x'], 'y': t['y'], 'z': t['z'], 'state_sha256': t['state_sha256']}
        other = dict(same, state_sha256='9' * 64)
        with self.assertRaises(GraphConflict):
            self.reconcile(b, 'PERSISTED_STATE_UNCHANGED', other)
        with self.assertRaises(GraphConflict):
            self.reconcile(b, 'EFFECT_OBSERVED', same)
        self.assertEqual(self.reconcile(b, 'EFFECT_OBSERVED', other)['state'], 'RECONCILED_EFFECT_OBSERVED')

    def test_reconcile_needs_the_requester_and_a_verified_binding(self):
        b = self.run_to_executing()
        t = b['target']
        obs = {'x': t['x'], 'y': t['y'], 'z': t['z'], 'state_sha256': '9' * 64}
        with self.assertRaises(GraphConflict):
            self.reconcile(b, 'EFFECT_OBSERVED', obs, requester=uid())
        with self.assertRaises(GraphConflict):                                    # another session has not bound
            self.reconcile(b, 'EFFECT_OBSERVED', obs, session=uid())
        self.assertEqual(self.state(b), 'EXECUTING')

    def test_a_reserved_row_reconciles_as_cancelled(self):
        b = self.res()
        self.s.reserve(b)
        done = self.reconcile(b, 'ANY', {})
        self.assertEqual(done['state'], 'CANCELLED_NO_EFFECT')


class AttemptStatus(Base):
    def status(self, b, **over):
        return self.s.status(dict(ident(b, 'requester_id'), **over))

    def test_status_is_attempt_specific_and_carries_the_receipt_hash(self):
        b = self.run_to_executing()
        self.assertEqual(self.status(b)['state'], 'EXECUTING')
        r = receipt(b)
        self.s.finalize(r)
        st = self.status(b)
        self.assertEqual((st['state'], st['terminal_outcome'], st['receipt_hash']), ('EFFECT_VERIFIED', 'EFFECT_VERIFIED', _digest(r)))

    def test_a_later_attempt_never_changes_the_answer_for_an_earlier_one(self):
        first = self.run_to_executing()
        r = receipt(first)
        self.s.finalize(r)
        later = self.res(actor=first['actor_id'], target=block_target(9, 64, 9))
        self.s.reserve(later)
        st = self.status(first)
        self.assertEqual((st['state'], st['receipt_hash'], st['actor_active_task_id']), ('EFFECT_VERIFIED', _digest(r), later['task_id']))
        self.assertEqual(self.status(later)['state'], 'RESERVED')

    def test_identity_must_match_exactly(self):
        b = self.run_to_executing()
        for over in (dict(attempt_id=uid()), dict(session_id=uid()), dict(actor_id=uid()), dict(requester_id=uid())):
            with self.assertRaises(GraphConflict):
                self.status(b, **over)

    def test_an_unknown_task_is_reported_as_none_with_the_actor_fence_holder(self):
        held = self.run_to_executing()
        ghost = dict(ident(held, 'requester_id'), task_id=uid(), attempt_id=uid())
        st = self.s.status(ghost)
        self.assertEqual((st['state'], st['actor_active_task_id']), ('NONE', held['task_id']))

    def test_hold_status_exposes_the_hold_and_intended_hashes(self):
        b = self.run_to_executing('pickup_item', target=item_target(pre=1))
        intended = receipt(b, evidence=pickup_evidence(b, post_count=5))
        h = self.s.hold(dict(ident(b, 'requester_id'), reason='INVENTORY_COMMIT_FAILED', intended=intended))
        st = self.status(b)
        self.assertEqual((st['state'], st['hold_reason'], st['hold_hash'], st['intended_hash']),
                         ('HOLD_IN_DOUBT', 'INVENTORY_COMMIT_FAILED', h['hold_hash'], _digest(intended)))


class ActiveDiscovery(Base):
    def test_active_returns_the_identity_and_target_only_to_the_requester(self):
        b = self.run_to_executing()
        self.s.hold(dict(ident(b, 'requester_id'), reason='EXECUTOR_EXCEPTION', intended={}))
        found = self.s.active({'world_id': b['world_id'], 'actor_id': b['actor_id'], 'requester_id': b['requester_id']})
        self.assertEqual((found['active'], found['state'], found['task_id'], found['action_kind']), (True, 'HOLD_IN_DOUBT', b['task_id'], 'break_block'))
        self.assertEqual(found['target']['x'], b['target']['x'])
        with self.assertRaises(GraphConflict):
            self.s.active({'world_id': b['world_id'], 'actor_id': b['actor_id'], 'requester_id': uid()})
        none = self.s.active({'world_id': self.world, 'actor_id': uid(), 'requester_id': uid()})
        self.assertEqual((none['active'], none['state']), (False, 'NONE'))

    def test_active_is_not_a_status_and_never_shows_a_terminal(self):
        b = self.run_to_executing()
        self.s.finalize(receipt(b))
        found = self.s.active({'world_id': b['world_id'], 'actor_id': b['actor_id'], 'requester_id': b['requester_id']})
        self.assertEqual(found['active'], False)
        self.assertNotIn('receipt_hash', found)


class CrossStoreFences(Base):
    def test_a_no_op_attempt_blocks_a_native_attempt_and_vice_versa(self):
        we = WorldEffectStore(self.path)
        actor = uid()
        wb = {'schema_version': 1, 'task_id': uid(), 'request_id': uid(), 'attempt_id': uid(), 'world_id': self.world,
              'actor_id': actor, 'profile_id': uid(), 'session_id': self.session, 'requester_id': uid(),
              'dimension_id': 'minecraft:overworld', 'action_kind': 'noop', 'origin': 'MANAGER_PACKET',
              'authorization': {'decision': 'ALLOW', 'entry_id': 'owner-noop', 'policy_sha256': 'b' * 64}}
        we.reserve(wb)
        with self.assertRaises(GraphConflict):
            self.s.reserve(self.res(actor=actor))
        we.cancel(dict(ident(wb, 'requester_id'), reason='OPERATOR'))
        n = self.res(actor=actor)
        self.s.reserve(n)
        wb2 = dict(wb, task_id=uid(), request_id=uid(), attempt_id=uid())
        with self.assertRaises(GraphConflict):
            we.reserve(wb2)

    def test_native_and_e3_g2_fence_each_other(self):
        g = g2_body()
        GuardedMotionStore(self.path).reserve(g)
        s = g['session_id']
        self.bind(g['world_id'], s)
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(g['world_id'], s, actor=g['actor_id']))
        n = self.res()
        self.s.reserve(n)
        blocked = g2_body()
        blocked['world_id'], blocked['actor_id'] = self.world, n['actor_id']
        blocked['snapshot'] = dict(blocked['snapshot'], world_id=self.world, actor_id=n['actor_id'])
        with self.assertRaises(GraphConflict):
            GuardedMotionStore(self.path).reserve(blocked)

    def test_native_and_m04_fence_each_other(self):
        m04 = M04Store(self.path)
        m04.initialize()
        GuardedMotionStore(self.path)
        ids = dict(world_id=self.world, actor_id=uid(), session_id=self.session, requester_id=uid(), task_id=uid(), attempt_id=uid())
        kw = dict(request_id=uid(), dimension_id='minecraft:overworld', block_key='1,2,3', proposed_contract={'a': 1})
        m04.reserve(ids, **kw)
        with self.assertRaises(GraphConflict):
            self.s.reserve(self.res(actor=ids['actor_id']))
        n = self.res()
        self.s.reserve(n)
        ids2 = dict(ids, actor_id=n['actor_id'], task_id=uid(), attempt_id=uid())
        with self.assertRaises(M04Blocked):
            m04.reserve(ids2, **dict(kw, request_id=uid(), block_key='9,9,9'))

    def test_a_native_hold_keeps_fencing_other_stores_until_reconciled(self):
        n = self.run_to_executing()
        self.s.hold(dict(ident(n, 'requester_id'), reason='EXECUTOR_EXCEPTION', intended={}))
        blocked = g2_body()
        blocked['world_id'], blocked['actor_id'] = self.world, n['actor_id']
        blocked['snapshot'] = dict(blocked['snapshot'], world_id=self.world, actor_id=n['actor_id'])
        with self.assertRaises(GraphConflict):
            GuardedMotionStore(self.path).reserve(blocked)

    def test_concurrent_native_and_g2_reservations_for_one_actor_admit_exactly_one(self):
        GuardedMotionStore(self.path)
        actor = uid()
        wins, errors = [], []
        barrier = threading.Barrier(12)

        def native():
            store = NativeEffectStore(self.path)
            body = reserve_body(self.world, self.session, actor=actor, target=block_target(uid().__hash__() % 1000, 64, 3))
            barrier.wait()
            try:
                store.reserve(body); wins.append('native')
            except GraphConflict:
                pass
            except Exception as exc:                                              # pragma: no cover
                errors.append(repr(exc))

        def g2():
            b = g2_body()
            b['world_id'], b['actor_id'], b['session_id'] = self.world, actor, self.session
            b['snapshot'] = dict(b['snapshot'], world_id=self.world, actor_id=actor, session_id=self.session)
            store = GuardedMotionStore(self.path)
            barrier.wait()
            try:
                store.reserve(b); wins.append('g2')
            except GraphConflict:
                pass
            except Exception as exc:                                              # pragma: no cover
                errors.append(repr(exc))
        threads = [threading.Thread(target=native if i % 2 else g2) for i in range(12)]
        [t.start() for t in threads]
        [t.join() for t in threads]
        self.assertEqual((errors, len(wins)), ([], 1), wins)


class SchemaCompatibility(Base):
    def test_opens_on_the_v4_baseline_without_altering_any_existing_object(self):
        copy_path = Path(self.tmp.name) / 'v4.sqlite3'
        shutil.copyfile(FIXTURES / 'v4_baseline.sqlite3', copy_path)

        def snapshot(path):
            with closing(sqlite3.connect(path)) as db:
                objects = {r[0]: r[1] for r in db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'")}
                rows = {t: db.execute(f'SELECT * FROM "{t}"').fetchall() for t, in
                        db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
                return objects, rows, db.execute('PRAGMA user_version').fetchone()[0]
        before_objects, before_rows, version = snapshot(copy_path)
        store = NativeEffectStore(copy_path)
        after_objects, after_rows, after_version = snapshot(copy_path)
        self.assertEqual(after_version, version)
        for name, sql in before_objects.items():
            self.assertEqual(after_objects[name], sql, name)
        for table, rows in before_rows.items():
            self.assertEqual(after_rows[table], rows, table)
        added = set(after_objects) - set(before_objects)
        self.assertTrue(all(n.startswith(('cplus_wn_', 'cplus_e_', 'sqlite_autoindex_cplus_')) for n in added), added)
        w, s = uid(), uid()
        store.bind({'schema_version': 1, 'world_id': w, 'folder_key': 'W', 'epoch': 0, 'session_id': s, 'contract': 'native-effects-v1'})
        self.assertEqual(store.reserve(reserve_body(w, s))['state'], 'RESERVED')

    def test_the_no_op_ledger_tables_are_untouched_by_the_native_store(self):
        we = WorldEffectStore(self.path)
        sql_before = self.sql("SELECT name,sql FROM sqlite_master WHERE name LIKE 'cplus_we_%' ORDER BY name")
        NativeEffectStore(self.path)
        self.assertEqual(self.sql("SELECT name,sql FROM sqlite_master WHERE name LIKE 'cplus_we_%' ORDER BY name"), sql_before)
        self.assertIsNotNone(we)

    def test_a_historical_noop_row_in_hold_is_preserved_and_still_fences(self):
        we = WorldEffectStore(self.path)
        actor = uid()
        wb = {'schema_version': 1, 'task_id': uid(), 'request_id': uid(), 'attempt_id': uid(), 'world_id': self.world,
              'actor_id': actor, 'profile_id': uid(), 'session_id': self.session, 'requester_id': uid(),
              'dimension_id': 'minecraft:overworld', 'action_kind': 'noop', 'origin': 'MANAGER_PACKET',
              'authorization': {'decision': 'ALLOW', 'entry_id': 'owner-noop', 'policy_sha256': 'b' * 64}}
        we.reserve(wb)
        we.start(ident(wb, 'requester_id'))
        we.hold(dict(ident(wb, 'requester_id'), reason='FIXTURE_CHANGED'))
        rows = self.sql('SELECT * FROM cplus_we_runs')
        NativeEffectStore(self.path)
        self.assertEqual(self.sql('SELECT * FROM cplus_we_runs'), rows)
        with self.assertRaises(GraphConflict):
            self.s.reserve(self.res(actor=actor))

    def test_the_action_check_constraint_admits_only_the_two_native_kinds(self):
        with closing(sqlite3.connect(self.path)) as db:
            with self.assertRaises(sqlite3.IntegrityError):
                with db:
                    db.execute("INSERT INTO cplus_wn_runs (task_id,request_id,attempt_id,world_id,dimension_id,session_id,"
                               "actor_id,profile_id,requester_id,action_kind,origin,entry_id,policy_sha256,target_json,payload_hash,"
                               "state,created_at,updated_at) VALUES ('t','r','a','w','d','s','a','p','q','place_block','o','e','x','{}','h','RESERVED',1,1)")


class ReviewFixes(Base):
    """CRITICAL reviewer findings 6, 7 and 9 (2026-10-28)."""

    def test_a_foreign_session_cannot_sweep_the_live_sessions_rows(self):
        live = self.run_to_executing()
        stale = uid()                                     # a session the world is not bound to
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(self.world, stale, actor=uid(), target=block_target(x=9)))
        self.assertEqual(self.state(live), 'EXECUTING', 'the live attempt was not touched by the refused reserve')

    def test_start_is_refused_after_the_binding_moved_on(self):
        b = self.res()
        self.s.reserve(b)
        self.bind(self.world, uid(), epoch=1)             # a newer session binds: the reservation belongs to a dead session
        with self.assertRaises(GraphConflict):
            self.s.start(ident(b, 'requester_id'))
        self.assertNotEqual(self.state(b), 'EXECUTING')

    def test_a_trailing_newline_is_not_a_valid_folder_key(self):
        for key in ('World1\n', '', 'x' * 129):
            with self.assertRaises(GraphConflict, msg=repr(key)):
                self.bind(uid(), uid(), folder=key)
        self.assertEqual(self.bind(uid(), uid(), folder='New World-0123456789abcdef')['state'], 'BOUND')


if __name__ == '__main__':
    unittest.main()
