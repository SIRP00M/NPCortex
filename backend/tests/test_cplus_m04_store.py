"""M04 dormant metadata boundary: all tests use temporary database copies."""
import json
import os
import sqlite3
import tempfile
import unittest
from pathlib import Path
from contextlib import contextmanager, closing
from uuid import uuid4

from npcortex_backend.cplus_m04_store import M04Blocked, M04Store
from npcortex_backend.cplus_m04_contracts import ContractError, STEPS


def uid(): return str(uuid4())


class M04StoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'fixture.sqlite3'
        self.s = M04Store(self.path)
        self.s.initialize()
        # Existing G2 historical table is compulsory for safe admission.
        with self.db() as db:
            db.execute('''CREATE TABLE cplus_e3_g2_moves
                (task_id TEXT PRIMARY KEY, world_id TEXT, actor_id TEXT, state TEXT)''')
        self.ids = dict(zip(('world_id', 'actor_id', 'session_id', 'requester_id',
                             'task_id', 'attempt_id'), (uid() for _ in range(6))))
        self.kw = dict(request_id=uid(), dimension_id='minecraft:overworld',
                       block_key='20,70,30', proposed_contract={'action':'one_block', 'schema':1})

    @contextmanager
    def db(self):
        with closing(sqlite3.connect(self.path)) as con:
            with con:
                yield con

    def tearDown(self): self.tmp.cleanup()

    def reserve(self): return self.s.reserve(self.ids, **self.kw)

    def witness(self, step, effect='NONE', tick=1):
        return self.s.record_step(self.ids, step=step, observation_id=uid(),
                                  server_tick=tick, evidence_hash='a'*64, effect=effect)

    def test_additive_init_preserves_unrelated_table(self):
        with self.db() as db:
            db.execute('CREATE TABLE historical (id TEXT PRIMARY KEY)')
            db.execute("INSERT INTO historical VALUES ('keep')")
        self.s.initialize()
        with self.db() as db:
            self.assertEqual(db.execute('SELECT id FROM historical').fetchone()[0], 'keep')

    def test_reservation_is_durable_and_no_execution(self):
        one = self.reserve()
        self.assertEqual(one['state'], 'RESERVED')
        self.assertEqual(M04Store(self.path).status(self.ids['task_id']), one)
        with self.db() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM cplus_m04_steps').fetchone()[0], 0)

    def test_conflicting_reuse_request_uuid(self):
        self.reserve()
        changed = dict(self.kw, proposed_contract={'action':'different'})
        with self.assertRaises(M04Blocked): self.s.reserve(self.ids, **changed)

    def test_exact_reservation_replay(self):
        a = self.reserve()
        self.assertEqual(a, self.reserve())

    def test_concurrent_same_actor_blocks(self):
        self.reserve()
        newer = dict(self.ids, task_id=uid(), attempt_id=uid())
        with self.assertRaises(M04Blocked):
            self.s.reserve(newer, **dict(self.kw, request_id=uid(), block_key='different'))

    def test_concurrent_same_block_blocks(self):
        self.reserve()
        newer = dict(self.ids, actor_id=uid(), task_id=uid(), attempt_id=uid())
        with self.assertRaises(M04Blocked): self.s.reserve(newer, **dict(self.kw, request_id=uid()))

    def test_old_g2_accepted_blocks(self):
        with self.db() as db:
            db.execute('INSERT INTO cplus_e3_g2_moves VALUES (?,?,?,?)',
                       (uid(), self.ids['world_id'], self.ids['actor_id'], 'C2_ACCEPTED'))
        with self.assertRaisesRegex(M04Blocked, 'preserve fence'): self.reserve()

    def test_old_g2_hold_blocks(self):
        with self.db() as db:
            db.execute('INSERT INTO cplus_e3_g2_moves VALUES (?,?,?,?)',
                       (uid(), self.ids['world_id'], self.ids['actor_id'], 'HOLD'))
        with self.assertRaises(M04Blocked): self.reserve()

    def test_missing_g2_state_fails_closed(self):
        with self.db() as db:
            db.execute('DROP TABLE cplus_e3_g2_moves')
        with self.assertRaisesRegex(M04Blocked, 'unavailable'): self.reserve()

    def test_old_g2_completed_does_not_block(self):
        with self.db() as db:
            db.execute('INSERT INTO cplus_e3_g2_moves VALUES (?,?,?,?)',
                       (uid(), self.ids['world_id'], self.ids['actor_id'], 'COMPLETED'))
        self.assertEqual(self.reserve()['state'], 'RESERVED')

    def test_e1_fifo_blocks(self):
        with self.db() as db:
            db.execute('CREATE TABLE cplus_e1_tasks (world_id TEXT,actor_id TEXT,state TEXT)')
            db.execute('INSERT INTO cplus_e1_tasks VALUES (?,?,?)',
                       (self.ids['world_id'], self.ids['actor_id'], 'HOLD'))
        with self.assertRaises(M04Blocked): self.reserve()

    def test_out_of_order_witness_blocked(self):
        self.reserve()
        with self.assertRaises(M04Blocked): self.witness('BREAK', 'VERIFIED')

    def test_wrong_session_for_witness_blocked(self):
        self.reserve()
        changed = dict(self.ids, session_id=uid())
        with self.assertRaises(M04Blocked):
            self.s.record_step(changed, step='OBSERVE', observation_id=uid(), server_tick=1,
                               evidence_hash='a'*64, effect='NONE')

    def test_break_requires_persisted_intent(self):
        self.reserve()
        for step in STEPS[:4]: self.witness(step, 'NONE')
        with self.assertRaisesRegex(M04Blocked, 'intent'): self.witness('BREAK', 'VERIFIED')

    def test_effect_intent_idempotent_without_reexecution(self):
        self.reserve()
        for step in STEPS[:4]: self.witness(step)
        request = uid()
        first = self.s.prepare_effect(self.ids, step='BREAK', effect_request_id=request,
                                      payload={'block': [20,70,30], 'tool': 'pickaxe'})
        second = self.s.prepare_effect(self.ids, step='BREAK', effect_request_id=request,
                                       payload={'block': [20,70,30], 'tool': 'pickaxe'})
        self.assertFalse(first['replay'])
        self.assertTrue(second['replay'])
        with self.assertRaises(M04Blocked):
            self.s.prepare_effect(self.ids, step='BREAK', effect_request_id=uid(),
                                  payload={'block': [20,70,30]})

    def test_partial_break_holds_and_retains_fence(self):
        self.reserve()
        for step in STEPS[:4]: self.witness(step)
        self.s.prepare_effect(self.ids, step='BREAK', effect_request_id=uid(),payload={'block': [20,70,30]})
        held = self.witness('BREAK', 'PARTIAL')
        self.assertEqual((held['state'], held['effect']), ('HOLD','PARTIAL'))
        self.assertEqual(self.s.metadata_retry(self.ids), held)
        with self.assertRaises(M04Blocked): self.witness('VERIFY_BLOCK', 'VERIFIED')
        with self.assertRaises(M04Blocked):
            self.s.reserve(dict(self.ids, task_id=uid(), attempt_id=uid()),
                           **dict(self.kw, request_id=uid(), block_key='different'))

    def test_duplicate_witness_conflict(self):
        self.reserve()
        observation = uid()
        params=dict(step='OBSERVE', observation_id=observation, server_tick=1,
                    evidence_hash='b'*64, effect='NONE')
        first=self.s.record_step(self.ids, **params)
        self.assertEqual(first,self.s.record_step(self.ids, **params))
        with self.assertRaises(M04Blocked):
            self.s.record_step(self.ids, **dict(params, evidence_hash='c'*64))

    def test_no_terminal_without_all_real_witnesses(self):
        self.reserve()
        with self.assertRaises(M04Blocked): self.witness('TERMINAL','VERIFIED')

    def test_full_fixed_workflow_and_terminal_replay(self):
        self.reserve()
        for step in STEPS:
            if step in ('BREAK','PICKUP'):
                self.s.prepare_effect(self.ids, step=step,effect_request_id=uid(),payload={'step':step})
            state=self.witness(step,'VERIFIED' if step in ('BREAK','VERIFY_BLOCK','PICKUP','VERIFY_INVENTORY','TERMINAL') else 'NONE')
        self.assertEqual(state['state'],'COMPLETED')
        self.assertEqual(state['effect'],'VERIFIED')
        self.assertEqual(M04Store(self.path).metadata_retry(self.ids),state)
        with self.db() as db:
            self.assertEqual(db.execute('SELECT count(*) FROM cplus_m04_steps').fetchone()[0],len(STEPS))
        with self.assertRaises(M04Blocked): self.s.hold(self.ids)

    def test_hold_is_persistent_after_restart(self):
        self.reserve()
        original = self.s.hold(self.ids)
        self.assertEqual(M04Store(self.path).metadata_retry(self.ids), original)
        with self.assertRaises(M04Blocked): self.witness('OBSERVE')

    def test_boolean_tick_and_noncanonical_evidence_rejected(self):
        self.reserve()
        with self.assertRaises(ContractError):
            self.s.record_step(self.ids,step='OBSERVE',observation_id=uid(),server_tick=True,
                               evidence_hash='a'*64,effect='NONE')
        with self.assertRaises(ContractError):
            self.s.record_step(self.ids,step='OBSERVE',observation_id=uid(),server_tick=1,
                               evidence_hash='z'*64,effect='NONE')


if __name__ == '__main__': unittest.main()
