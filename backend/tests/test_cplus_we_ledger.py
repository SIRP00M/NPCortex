"""Stage 4 world-effect NO-OP ledger: state machine, idempotency, fences, restart, schema compatibility, fault windows.

Offline evidence only: SQLite on a temporary file. Says nothing about Minecraft chunk storage or crash safety of a real
world effect.
"""
import copy
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
from npcortex_backend.cplus_we_ledger import WorldEffectStore, _digest
from test_cplus_e3_g2_motion import body as g2_body


def uid():
    return str(uuid4())


def reserve_body(world=None, actor=None, session=None, **over):
    b = {'schema_version': 1, 'task_id': uid(), 'request_id': uid(), 'attempt_id': uid(),
         'world_id': world or uid(), 'actor_id': actor or uid(), 'profile_id': uid(),
         'session_id': session or uid(), 'requester_id': uid(), 'dimension_id': 'minecraft:overworld',
         'action_kind': 'noop', 'origin': 'MANAGER_PACKET',
         'authorization': {'decision': 'ALLOW', 'entry_id': 'owner-noop', 'policy_sha256': 'b' * 64}}
    b.update(over)
    return b


def ident(b, *extra):
    keys = ('task_id', 'attempt_id', 'world_id', 'actor_id', 'session_id') + tuple(extra)
    return {k: b[k] for k in keys}


def receipt(b, **over):
    r = ident(b)
    r.update(schema_version=1, kind='world_effect_receipt', result='NO_EFFECT', reason='', server_tick=7,
             executed=True, pre_hash='c' * 64, post_hash='c' * 64, world_effects=False)
    r.update(over)
    return r


class LedgerBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'world.db'
        self.s = WorldEffectStore(self.path)

    def tearDown(self):
        self.tmp.cleanup()

    def row_state(self, b):
        return self.s.status(b['world_id'], b['actor_id'])['state']

    def sql(self, query, *args):
        with closing(sqlite3.connect(self.path)) as db:
            with db:
                return db.execute(query, args).fetchall()


class StateMachine(LedgerBase):
    def test_happy_path_is_no_effect_verified_not_cancelled(self):
        b = reserve_body()
        r = self.s.reserve(b)
        self.assertEqual((r['state'], r['can_start'], r['world_effects'], r['native_mutation']),
                         ('RESERVED', True, False, False))
        s = self.s.start(ident(b, 'requester_id'))
        self.assertEqual((s['state'], s['execution_started']), ('EXECUTING', True))
        t = self.s.finalize(receipt(b))
        self.assertEqual(t['state'], 'NO_EFFECT_VERIFIED')
        self.assertTrue(t['durable_ack'] and t['release_actor_fence'])
        self.assertFalse(t['world_effects'])
        self.assertFalse(self.s.status(b['world_id'], b['actor_id'])['active'])

    def test_terminal_replay_is_identical_and_different_evidence_is_refused(self):
        b = reserve_body()
        self.s.reserve(b); self.s.start(ident(b, 'requester_id'))
        first = self.s.finalize(receipt(b))
        self.assertEqual(self.s.finalize(receipt(b)), first)               # lost ACK: stored result
        with self.assertRaises(GraphConflict):
            self.s.finalize(receipt(b, server_tick=8))                     # never re-judged
        with self.assertRaises(GraphConflict):
            self.s.finalize(receipt(b, result='PRECONDITION_FAILED', executed=False, reason='PRECONDITION_FAILED',
                                    pre_hash='', post_hash=''))

    def test_start_is_required_before_a_no_effect_receipt(self):
        b = reserve_body()
        self.s.reserve(b)
        with self.assertRaises(GraphConflict):
            self.s.finalize(receipt(b))
        self.assertEqual(self.row_state(b), 'RESERVED')

    def test_changed_fixture_hash_is_not_a_no_effect_receipt(self):
        b = reserve_body()
        self.s.reserve(b); self.s.start(ident(b, 'requester_id'))
        with self.assertRaises(GraphConflict):
            self.s.finalize(receipt(b, post_hash='d' * 64))
        self.assertEqual(self.row_state(b), 'EXECUTING')

    def test_receipt_cannot_claim_a_world_effect_or_malformed_fields(self):
        b = reserve_body()
        self.s.reserve(b); self.s.start(ident(b, 'requester_id'))
        for bad in (dict(world_effects=True), dict(executed=False), dict(server_tick=True), dict(server_tick=-1),
                    dict(pre_hash='C' * 64), dict(kind='x'), dict(result='VERIFIED'), dict(reason='x')):
            with self.assertRaises(GraphConflict, msg=str(bad)):
                self.s.finalize(receipt(b, **bad))
        extra = receipt(b); extra['surprise'] = 1
        with self.assertRaises(GraphConflict):
            self.s.finalize(extra)
        self.assertEqual(self.row_state(b), 'EXECUTING')

    def test_failed_no_effect_from_reserved_and_from_executing(self):
        fail = dict(result='PRECONDITION_FAILED', executed=False, reason='PRECONDITION_FAILED', pre_hash='', post_hash='')
        a = reserve_body()
        self.s.reserve(a)
        self.assertEqual(self.s.finalize(receipt(a, **fail))['state'], 'FAILED_NO_EFFECT')
        c = reserve_body()
        self.s.reserve(c); self.s.start(ident(c, 'requester_id'))
        self.assertEqual(self.s.finalize(receipt(c, **dict(fail, reason='START_ACK_LOST')))['state'], 'FAILED_NO_EFFECT')
        d = reserve_body()
        self.s.reserve(d)
        with self.assertRaises(GraphConflict):
            self.s.finalize(receipt(d, **dict(fail, reason='made up')))

    def test_cancel_only_before_start_and_distinct_from_success(self):
        b = reserve_body()
        self.s.reserve(b)
        c = self.s.cancel(dict(ident(b, 'requester_id'), reason='REQUESTER_GONE'))
        self.assertEqual(c['state'], 'CANCELLED_NO_EFFECT')
        self.assertEqual(self.s.cancel(dict(ident(b, 'requester_id'), reason='REQUESTER_GONE')), c)   # idempotent
        e = reserve_body()
        self.s.reserve(e); self.s.start(ident(e, 'requester_id'))
        with self.assertRaises(GraphConflict):
            self.s.cancel(dict(ident(e, 'requester_id'), reason='OPERATOR'))   # marker durable: receipt decides
        self.assertEqual(self.row_state(e), 'EXECUTING')
        with self.assertRaises(GraphConflict):
            self.s.start(ident(b, 'requester_id'))                              # cancelled can never start

    def test_foreign_identity_requester_and_unknown_task_refused(self):
        b = reserve_body()
        self.s.reserve(b)
        for key in ('session_id', 'actor_id', 'world_id', 'attempt_id'):
            bad = ident(b, 'requester_id'); bad[key] = uid()
            with self.assertRaises(GraphConflict, msg=key):
                self.s.start(bad)
        bad = ident(b, 'requester_id'); bad['requester_id'] = uid()
        with self.assertRaises(GraphConflict):
            self.s.start(bad)
        with self.assertRaises(GraphConflict):
            self.s.start(ident(reserve_body(), 'requester_id'))
        self.assertEqual(self.row_state(b), 'RESERVED')

    def test_start_replay_is_idempotent(self):
        b = reserve_body()
        self.s.reserve(b)
        first = self.s.start(ident(b, 'requester_id'))
        again = self.s.start(ident(b, 'requester_id'))
        self.assertTrue(again['replay'] and not first['replay'])


class Hold(LedgerBase):
    def test_hold_in_doubt_keeps_the_fence_and_is_not_a_terminal(self):
        b = reserve_body()
        self.s.reserve(b)
        with self.assertRaises(GraphConflict):                                       # nothing to hold before the marker
            self.s.hold(dict(ident(b, 'requester_id'), reason='FIXTURE_CHANGED'))
        self.s.start(ident(b, 'requester_id'))
        h = self.s.hold(dict(ident(b, 'requester_id'), reason='FIXTURE_CHANGED'))
        self.assertEqual((h['state'], h['active']), ('HOLD_IN_DOUBT', True))
        self.assertFalse(h['release_actor_fence'])
        self.assertEqual(self.s.hold(dict(ident(b, 'requester_id'), reason='FIXTURE_CHANGED'))['state'], 'HOLD_IN_DOUBT')
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(world=b['world_id'], actor=b['actor_id'], session=b['session_id']))
        with self.assertRaises(GraphConflict):
            self.s.hold(dict(ident(b, 'requester_id'), reason='because'))
        self.assertEqual(self.sql('SELECT COUNT(*) FROM cplus_we_terminals')[0][0], 0)


class Admission(LedgerBase):
    def test_backend_never_grants_authority(self):
        base = reserve_body()
        for mutate in (lambda b: b.update(origin='CONSOLE'),
                       lambda b: b['authorization'].update(decision='DENY'),
                       lambda b: b['authorization'].update(decision='UNKNOWN'),
                       lambda b: b.pop('authorization'),
                       lambda b: b['authorization'].update(extra=1),
                       lambda b: b['authorization'].update(policy_sha256='z'),
                       lambda b: b.update(action_kind='break_block'),
                       lambda b: b.update(action_kind='place_block'),
                       lambda b: b.update(requester_id='not-a-uuid'),
                       lambda b: b.update(profile_id=str(uuid4()).upper()),
                       lambda b: b.update(dimension_id=''),
                       lambda b: b.update(schema_version=2),
                       lambda b: b.update(unexpected='x')):
            b = copy.deepcopy(base)
            mutate(b)
            with self.assertRaises(GraphConflict):
                self.s.reserve(b)
        self.assertEqual(self.sql('SELECT COUNT(*) FROM cplus_we_runs')[0][0], 0)

    def test_duplicate_request_is_idempotent_and_conflicting_replay_refused(self):
        b = reserve_body()
        first = self.s.reserve(b)
        self.assertEqual(self.s.reserve(copy.deepcopy(b)), first)
        changed = copy.deepcopy(b); changed['dimension_id'] = 'minecraft:the_nether'
        with self.assertRaises(GraphConflict):
            self.s.reserve(changed)
        self.assertEqual(self.sql('SELECT COUNT(*) FROM cplus_we_runs')[0][0], 1)

    def test_one_active_attempt_per_actor_only(self):
        a = reserve_body()
        self.s.reserve(a)
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(world=a['world_id'], actor=a['actor_id'], session=a['session_id']))
        other = reserve_body(world=a['world_id'], session=a['session_id'])        # another actor is independent
        self.assertEqual(self.s.reserve(other)['state'], 'RESERVED')
        self.s.start(ident(a, 'requester_id')); self.s.finalize(receipt(a))       # fence leaves with the terminal
        again = reserve_body(world=a['world_id'], actor=a['actor_id'], session=a['session_id'])
        self.assertEqual(self.s.reserve(again)['state'], 'RESERVED')

    def test_task_and_attempt_identity_reuse_refused(self):
        a = reserve_body()
        self.s.reserve(a)
        self.s.start(ident(a, 'requester_id')); self.s.finalize(receipt(a))
        reuse = reserve_body(world=a['world_id'], actor=a['actor_id'], session=a['session_id'], task_id=a['task_id'])
        with self.assertRaises(GraphConflict):
            self.s.reserve(reuse)


class CrossStoreFences(LedgerBase):
    def g2(self):
        return GuardedMotionStore(self.path)

    def test_g2_blocks_world_effect_and_world_effect_blocks_g2(self):
        g = g2_body()
        self.g2().reserve(g)                                                      # an E3 movement attempt owns the actor
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(world=g['world_id'], actor=g['actor_id'], session=g['session_id']))
        w = reserve_body()
        self.s.reserve(w)                                                         # a world-effect attempt owns the actor
        blocked = g2_body(); blocked['world_id'] = w['world_id']; blocked['actor_id'] = w['actor_id']
        snap = dict(blocked['snapshot']); snap['world_id'] = w['world_id']; snap['actor_id'] = w['actor_id']
        blocked['snapshot'] = snap
        with self.assertRaises(GraphConflict):
            self.g2().reserve(blocked)
        self.s.start(ident(w, 'requester_id')); self.s.finalize(receipt(w))        # terminal releases it
        self.assertEqual(self.g2().reserve(blocked)['state'], 'RESERVED')

    def test_historical_g2_completed_rows_do_not_block(self):
        g = g2_body()
        store = self.g2()
        store.reserve(g)
        with closing(sqlite3.connect(self.path)) as db:
            with db:
                db.execute("UPDATE cplus_e3_g2_moves SET state='COMPLETED'")
        self.assertEqual(self.s.reserve(reserve_body(world=g['world_id'], actor=g['actor_id'],
                                                     session=g['session_id']))['state'], 'RESERVED')

    def test_m04_and_world_effect_fence_each_other(self):
        m04 = M04Store(self.path)
        m04.initialize()
        ids = dict(world_id=uid(), actor_id=uid(), session_id=uid(), requester_id=uid(), task_id=uid(), attempt_id=uid())
        kw = dict(request_id=uid(), dimension_id='minecraft:overworld', block_key='1,2,3', proposed_contract={'a': 1})
        GuardedMotionStore(self.path)                                             # M04 requires the G2 table
        m04.reserve(ids, **kw)
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(world=ids['world_id'], actor=ids['actor_id']))
        w = reserve_body()
        self.s.reserve(w)
        ids2 = dict(ids, world_id=w['world_id'], actor_id=w['actor_id'], task_id=uid(), attempt_id=uid())
        with self.assertRaises(M04Blocked):
            m04.reserve(ids2, **dict(kw, request_id=uid(), block_key='9,9,9'))

    def test_concurrent_cross_store_reservations_admit_exactly_one(self):
        """Many racing G2 + world-effect reservations for ONE actor on separate connections: exactly one winner."""
        GuardedMotionStore(self.path)
        world, actor, session = uid(), uid(), uid()
        wins, errors = [], []
        barrier = threading.Barrier(12)

        def g2_try():
            snap_body = g2_body()
            snap_body['world_id'], snap_body['actor_id'], snap_body['session_id'] = world, actor, session
            snap_body['snapshot'] = dict(snap_body['snapshot'], world_id=world, actor_id=actor, session_id=session)
            store = GuardedMotionStore(self.path)
            barrier.wait()
            try:
                store.reserve(snap_body); wins.append('g2')
            except GraphConflict:
                pass
            except Exception as exc:                                              # a lock timeout is a failure, not a pass
                errors.append(repr(exc))

        def we_try():
            store = WorldEffectStore(self.path)
            barrier.wait()
            try:
                store.reserve(reserve_body(world=world, actor=actor, session=session)); wins.append('we')
            except GraphConflict:
                pass
            except Exception as exc:
                errors.append(repr(exc))

        threads = [threading.Thread(target=g2_try if i % 2 else we_try) for i in range(12)]
        for t in threads: t.start()
        for t in threads: t.join(30)
        self.assertEqual(errors, [])
        self.assertEqual(len(wins), 1, wins)
        owners = (self.sql('SELECT COUNT(*) FROM cplus_we_runs WHERE world_id=?', world)[0][0]
                  + self.sql('SELECT COUNT(*) FROM cplus_e3_g2_moves WHERE world_id=?', world)[0][0])
        self.assertEqual(owners, 1)


class RestartAndRecovery(LedgerBase):
    def test_reserved_before_start_settles_failed_no_effect_after_restart(self):
        b = reserve_body()
        self.s.reserve(b)
        restarted = WorldEffectStore(self.path)
        st = restarted.status(b['world_id'], b['actor_id'])
        self.assertEqual(st['state'], 'FAILED_NO_EFFECT')                          # the executor could never have run
        self.assertFalse(st['active'])
        with self.assertRaises(GraphConflict):
            restarted.start(ident(b, 'requester_id'))

    def test_executing_becomes_hold_in_doubt_and_stays_fenced(self):
        b = reserve_body()
        self.s.reserve(b); self.s.start(ident(b, 'requester_id'))
        restarted = WorldEffectStore(self.path)
        self.assertEqual(restarted.status(b['world_id'], b['actor_id'])['state'], 'HOLD_IN_DOUBT')
        for session in (b['session_id'], uid()):                                     # same or new session: still fenced
            with self.assertRaises(GraphConflict):
                restarted.reserve(reserve_body(world=b['world_id'], actor=b['actor_id'], session=session))
        with self.assertRaises(GraphConflict):
            restarted.finalize(receipt(b))                                           # a late receipt cannot resurrect it
        self.assertEqual(restarted.status(b['world_id'], b['actor_id'])['state'], 'HOLD_IN_DOUBT')

    def test_reconcile_releases_hold_without_claiming_verification(self):
        b = reserve_body()
        self.s.reserve(b); self.s.start(ident(b, 'requester_id'))
        restarted = WorldEffectStore(self.path)
        new_session = uid()
        rec = dict(ident(b, 'requester_id'), session_id=new_session, fresh_hash='e' * 64)
        r = restarted.reconcile(rec)
        self.assertEqual(r['state'], 'RECONCILED_NO_EFFECT')
        self.assertNotEqual(r['state'], 'NO_EFFECT_VERIFIED')
        self.assertEqual(restarted.reconcile(rec), r)
        with self.assertRaises(GraphConflict):
            restarted.reconcile(dict(rec, fresh_hash='f' * 64))                      # terminal is not re-judged
        again = reserve_body(world=b['world_id'], actor=b['actor_id'], session=new_session)
        self.assertEqual(restarted.reserve(again)['state'], 'RESERVED')

    def test_new_session_settles_a_dead_session_row_inside_reserve(self):
        b = reserve_body()
        self.s.reserve(b)                                       # game quit, backend kept running
        fresh = reserve_body(world=b['world_id'], actor=b['actor_id'])           # different session id
        self.assertEqual(self.s.reserve(fresh)['state'], 'RESERVED')
        self.assertEqual(self.sql('SELECT state FROM cplus_we_runs WHERE task_id=?', b['task_id'])[0][0],
                         'FAILED_NO_EFFECT')

    def test_executing_row_of_a_dead_session_becomes_hold_and_blocks_new_session(self):
        b = reserve_body()
        self.s.reserve(b); self.s.start(ident(b, 'requester_id'))
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(world=b['world_id'], actor=b['actor_id']))
        self.assertEqual(self.row_state(b), 'HOLD_IN_DOUBT')

    def test_reconcile_of_reserved_is_a_cancel_not_a_success(self):
        b = reserve_body()
        self.s.reserve(b)
        r = self.s.reconcile(dict(ident(b, 'requester_id'), fresh_hash='e' * 64))
        self.assertEqual(r['state'], 'CANCELLED_NO_EFFECT')

    def test_reconcile_refuses_terminal_and_foreign_rows(self):
        b = reserve_body()
        self.s.reserve(b); self.s.start(ident(b, 'requester_id')); self.s.finalize(receipt(b))
        with self.assertRaises(GraphConflict):
            self.s.reconcile(dict(ident(b, 'requester_id'), fresh_hash='e' * 64))
        with self.assertRaises(GraphConflict):
            self.s.reconcile(dict(ident(reserve_body(), 'requester_id'), fresh_hash='e' * 64))


    def test_reconcile_by_a_foreign_requester_is_refused(self):
        b = reserve_body()
        self.s.reserve(b); self.s.start(ident(b, 'requester_id'))
        with self.assertRaises(GraphConflict):
            self.s.reconcile(dict(ident(b, 'requester_id'), requester_id=uid(), fresh_hash='e' * 64))
        self.assertEqual(self.row_state(b), 'EXECUTING')

    def test_boolean_schema_version_is_refused(self):
        with self.assertRaises(GraphConflict):
            self.s.reserve(reserve_body(schema_version=True))

    def test_concurrent_retry_of_one_request_never_reports_a_false_refusal(self):
        """The request_id retried while the first copy sits between the sweep commit and the insert."""
        b = reserve_body()
        results, errors = [], []
        barrier = threading.Barrier(8)

        def go():
            store = WorldEffectStore(self.path)
            barrier.wait()
            try:
                results.append(store.reserve(copy.deepcopy(b))['task_id'])
            except Exception as exc:
                errors.append(repr(exc))
        threads = [threading.Thread(target=go) for _ in range(8)]
        for t in threads: t.start()
        for t in threads: t.join(30)
        self.assertEqual(errors, [])
        self.assertEqual(results, [b['task_id']] * 8)
        self.assertEqual(self.sql('SELECT COUNT(*) FROM cplus_we_runs')[0][0], 1)


class SchemaCompatibility(LedgerBase):
    def test_additive_only_and_historical_rows_untouched(self):
        # A pre-existing database with historical E3 rows, an M04 table and an unrelated table.
        path = Path(self.tmp.name) / 'old.db'
        g = g2_body()
        store = GuardedMotionStore(path)
        store.reserve(g)
        store.report({'task_id': g['task_id'], 'attempt_id': g['attempt_id'], 'world_id': g['world_id'],
                      'actor_id': g['actor_id'], 'session_id': g['session_id'], 'state': 'HOLD', 'goal_id': None})
        m04 = M04Store(path)
        m04.initialize()
        with closing(sqlite3.connect(path)) as db:
            with db:
                db.execute('CREATE TABLE historical (k TEXT PRIMARY KEY)')
                db.execute("INSERT INTO historical VALUES ('keep')")
                version = db.execute('PRAGMA user_version').fetchone()[0]
                before = {t: db.execute(f'SELECT * FROM {t}').fetchall() for t in
                          ('cplus_e3_g2_moves', 'cplus_m04_runs', 'historical')}
                schema = {r[0]: r[1] for r in db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'cplus_we%' "
                                                         "AND name NOT LIKE 'sqlite_%'")}
        WorldEffectStore(path)
        WorldEffectStore(path)                                                      # idempotent init
        with closing(sqlite3.connect(path)) as db:
            with db:
                self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], version)
                for t, rows in before.items():
                    self.assertEqual(db.execute(f'SELECT * FROM {t}').fetchall(), rows, t)
                after = {r[0]: r[1] for r in db.execute("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'cplus_we%' "
                                                        "AND name NOT LIKE 'sqlite_%'")}
                self.assertEqual(after, schema)
                self.assertEqual(db.execute("SELECT state FROM cplus_e3_g2_moves").fetchone()[0], 'HOLD')   # HOLD preserved

    def test_effective_durability_settings(self):
        with self.s.connection() as db:
            self.assertEqual(db.execute('PRAGMA synchronous').fetchone()[0], 2)     # FULL
            self.assertEqual(db.execute('PRAGMA journal_mode').fetchone()[0].lower(), 'wal')
            self.assertEqual(db.execute('PRAGMA foreign_keys').fetchone()[0], 1)

    def test_only_noop_action_is_representable_in_storage(self):
        b = reserve_body()
        self.s.reserve(b)
        with closing(sqlite3.connect(self.path)) as db:
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("UPDATE cplus_we_runs SET action_kind='break_block'")
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute("UPDATE cplus_we_runs SET state='COMPLETED'")


if __name__ == '__main__':
    unittest.main()
