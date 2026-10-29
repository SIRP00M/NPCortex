"""PAP-G2 player-action ledger for the NEW world-effect kinds place_block / use_block. The backend never touches a Minecraft world.

Additive tables only (cplus_pap_*). No cplus_wn_* table is altered (its action_kind CHECK is not touched, so no migration of an
accepted persistence boundary is needed). The accepted native store (cplus_wn_*) and this store arbitrate in the SAME
BEGIN IMMEDIATE write transaction of one SQLite file, in both directions:
  * actor fence : one unresolved attempt per actor across WN, PAP, no-op, E3 G2, E1 and M04;
  * block claims: one ACTIVE/HELD claim per block cell across WN (break) and PAP (place/use), checked by BOTH stores;
  so there is no check-then-claim gap between the two kinds. Claims only coordinate NPCortex actors, never players or mods.

Same lifecycle and durability rules as the native store: RESERVED -> EXECUTING (durable START marker, ACK required before any
native call) -> EFFECT_VERIFIED | FAILED_NO_EFFECT | HOLD_IN_DOUBT; exact replay by request_id/payload hash; a lost receipt or an
uncertain inventory persistence degrades to HOLD_IN_DOUBT, never to success, and HOLD is only released by an operator
reconcile from OBSERVED state. Exactly-once physical effects are NOT claimed. Authority is never decided here: an ALLOW decision
from Forge is recorded, not verified.
"""
from __future__ import annotations

import json
import sqlite3
import time

from .cplus_e_graph import GraphConflict, ReadOnlyGraph
from .cplus_wn_store import (ACTIVE, MAX_EVIDENCE, NativeEffectStore, ORIGIN, _AUTH_KEYS, _CANCEL_REASONS, _FAIL_REASONS,
                             _HOLD_REASONS, _IDENT, _IDS, _canonical, _digest, _exact, _hex64, _int, _res, _uuid)

PAP_ACTIONS = ('place_block', 'use_block')
_NO_EFFECT = {'place_block': 'PLACE_REFUSED', 'use_block': 'USE_REFUSED'}
_FACES = ('down', 'up', 'north', 'south', 'west', 'east')
PAP_ACTIVE = ACTIVE


class PlayerActionStore(ReadOnlyGraph):
    def __init__(self, database):
        super().__init__(database)
        with self.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS cplus_pap_runs (
                    task_id TEXT PRIMARY KEY, request_id TEXT NOT NULL UNIQUE, attempt_id TEXT NOT NULL,
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL, session_id TEXT NOT NULL, actor_id TEXT NOT NULL,
                    profile_id TEXT NOT NULL, requester_id TEXT NOT NULL,
                    action_kind TEXT NOT NULL CHECK (action_kind IN ('place_block','use_block')),
                    origin TEXT NOT NULL, entry_id TEXT NOT NULL, policy_sha256 TEXT NOT NULL, target_json TEXT NOT NULL,
                    payload_hash TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('RESERVED','EXECUTING','EFFECT_VERIFIED','FAILED_NO_EFFECT',
                        'CANCELLED_NO_EFFECT','HOLD_IN_DOUBT','RECONCILED_EFFECT_OBSERVED','RECONCILED_PERSISTED_STATE_UNCHANGED')),
                    detail TEXT NOT NULL DEFAULT '', started_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS cplus_pap_active_actor ON cplus_pap_runs(world_id, actor_id)
                  WHERE state IN ('RESERVED','EXECUTING','HOLD_IN_DOUBT');
                CREATE TABLE IF NOT EXISTS cplus_pap_terminals (
                    task_id TEXT PRIMARY KEY REFERENCES cplus_pap_runs(task_id), outcome TEXT NOT NULL,
                    evidence_hash TEXT NOT NULL, evidence_json TEXT NOT NULL, created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS cplus_pap_holds (
                    task_id TEXT PRIMARY KEY REFERENCES cplus_pap_runs(task_id), reason TEXT NOT NULL, hold_hash TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS cplus_pap_claims (
                    claim_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL REFERENCES cplus_pap_runs(task_id),
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL, actor_id TEXT NOT NULL,
                    bx INTEGER NOT NULL, by INTEGER NOT NULL, bz INTEGER NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('ACTIVE','HELD','RELEASED')),
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS cplus_pap_claim_cell ON cplus_pap_claims(world_id, dimension_id, bx, by, bz)
                  WHERE state IN ('ACTIVE','HELD');
            ''')
            self._sweep(db, None)       # constructing a store is a backend restart: nothing from a dead process can continue

    # ---- restart / session rules ------------------------------------------------------------------------------------
    @staticmethod
    def _sweep(db, current_session, world=None):
        """RESERVED (start never durable) -> FAILED_NO_EFFECT; EXECUTING (unknown) -> HOLD_IN_DOUBT. Nothing replayed or deleted."""
        now = int(time.time())
        scope, args = '', []
        if current_session is not None:
            scope += ' AND session_id<>?'
            args.append(current_session)
        if world is not None:
            scope += ' AND world_id=?'
            args.append(world)
        for (task,) in db.execute("SELECT task_id FROM cplus_pap_runs WHERE state='EXECUTING'" + scope, args).fetchall():
            db.execute("UPDATE cplus_pap_runs SET state='HOLD_IN_DOUBT',detail='session ended while EXECUTING',updated_at=? "
                       "WHERE task_id=?", (now, task))
            db.execute("UPDATE cplus_pap_claims SET state='HELD',updated_at=? WHERE task_id=? AND state='ACTIVE'", (now, task))
        for (task,) in db.execute("SELECT task_id FROM cplus_pap_runs WHERE state='RESERVED'" + scope, args).fetchall():
            evidence = {'reason': 'SESSION_ENDED_BEFORE_START', 'task_id': task}
            db.execute("UPDATE cplus_pap_runs SET state='FAILED_NO_EFFECT',detail='session ended before the start marker',"
                       "updated_at=? WHERE task_id=?", (now, task))
            db.execute("UPDATE cplus_pap_claims SET state='RELEASED',updated_at=? WHERE task_id=?", (now, task))
            db.execute('INSERT OR IGNORE INTO cplus_pap_terminals VALUES (?,?,?,?,?)',
                       (task, 'FAILED_NO_EFFECT', _digest(evidence), _canonical(evidence), now))

    # ---- helpers ----------------------------------------------------------------------------------------------------
    _COLS = ('task_id,request_id,attempt_id,world_id,dimension_id,session_id,actor_id,profile_id,requester_id,action_kind,'
             'origin,entry_id,policy_sha256,target_json,state,detail')

    @classmethod
    def _row(cls, db, task):
        row = db.execute(f'SELECT {cls._COLS} FROM cplus_pap_runs WHERE task_id=?', (task,)).fetchone()
        return None if row is None else dict(zip(cls._COLS.split(','), row))

    @staticmethod
    def _view(row, **extra):
        out = {'schema_version': 1, 'kind': 'player_action_run', 'native_mutation': True}
        out.update({k: row[k] for k in ('task_id', 'request_id', 'attempt_id', 'world_id', 'dimension_id', 'session_id',
                                        'actor_id', 'profile_id', 'requester_id', 'action_kind', 'entry_id', 'state', 'detail')})
        out['can_start'] = row['state'] == 'RESERVED'
        out['active'] = row['state'] in PAP_ACTIVE
        out.update(extra)
        return out

    @staticmethod
    def _ident(row, body):
        if row is None or any(row[k] != body[k] for k in _IDENT):
            raise GraphConflict('foreign or stale player-action identity')

    @classmethod
    def _bound(cls, db, world, session):
        # the binding handshake lives in the accepted native store's table; without it nothing is verified
        return cls._has(db, 'cplus_wn_bindings') and NativeEffectStore._bound_session(db, world, session)

    @staticmethod
    def _has(db, name):
        return db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is not None

    @classmethod
    def _actor_fence(cls, db, world, actor):
        """The accepted native ledger and every other store: one unresolved attempt per actor, all in this transaction."""
        NativeEffectStore._cross_fence(db, world, actor)
        if cls._has(db, 'cplus_wn_runs') and db.execute(
                "SELECT 1 FROM cplus_wn_runs WHERE world_id=? AND actor_id=? AND state IN ('RESERVED','EXECUTING','HOLD_IN_DOUBT') LIMIT 1",
                (world, actor)).fetchone():
            raise GraphConflict('a native break/pickup attempt still owns this actor')

    @staticmethod
    def _cells(target):
        cells = [(target['x'], target['y'], target['z'])]
        if 'support_x' in target:
            cells.append((target['support_x'], target['support_y'], target['support_z']))
        return cells

    @staticmethod
    def _target(kind, t):
        base = ('kind', 'x', 'y', 'z', 'block_id', 'state_sha256')
        if kind == 'place_block':
            _exact(t, base + ('support_x', 'support_y', 'support_z', 'face', 'item_key', 'item_count', 'selected_slot'), 'target')
            _int(t['support_x'], 'support_x', -30000000, 30000000)
            _int(t['support_y'], 'support_y', -4096, 4096)
            _int(t['support_z'], 'support_z', -30000000, 30000000)
            if t['face'] not in _FACES:
                raise GraphConflict('face unknown')
            _res(t['item_key'], 'item_key')
            _int(t['item_count'], 'item_count', 1, 64)
            _int(t['selected_slot'], 'selected_slot', 0, 8)
            if (t['support_x'], t['support_y'], t['support_z']) == (t['x'], t['y'], t['z']):
                raise GraphConflict('the support block and the placed cell must differ')
        else:
            _exact(t, base + ('face',), 'target')
            if t['face'] not in _FACES:
                raise GraphConflict('face unknown')
        if t['kind'] != 'block':
            raise GraphConflict(f'{kind} needs a block target')
        _int(t['x'], 'x', -30000000, 30000000)
        _int(t['y'], 'y', -4096, 4096)
        _int(t['z'], 'z', -30000000, 30000000)
        _res(t['block_id'], 'block_id')
        _hex64(t['state_sha256'], 'state_sha256')

    # ---- operations -------------------------------------------------------------------------------------------------
    def reserve(self, body):
        _exact(body, set(_IDS) | {'schema_version', 'dimension_id', 'action_kind', 'origin', 'authorization', 'target'}, 'reserve')
        if type(body['schema_version']) is not int or body['schema_version'] != 1 or body['action_kind'] not in PAP_ACTIONS \
                or body['origin'] != ORIGIN:
            raise GraphConflict('unsupported schema, action or origin')
        for key in _IDS:
            _uuid(body[key], key)
        if type(body['dimension_id']) is not str or not body['dimension_id'] or len(body['dimension_id']) > 128:
            raise GraphConflict('dimension_id required')
        auth = body['authorization']
        _exact(auth, _AUTH_KEYS, 'authorization')
        if auth['decision'] != 'ALLOW' or type(auth['entry_id']) is not str or not (1 <= len(auth['entry_id']) <= 64):
            raise GraphConflict('an ALLOW decision with a policy entry is required; the backend never grants authority')
        _hex64(auth['policy_sha256'], 'policy_sha256')
        self._target(body['action_kind'], body['target'])
        digest = _digest(body)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT task_id,payload_hash FROM cplus_pap_runs WHERE request_id=?', (body['request_id'],)).fetchone()
            if old:
                if old != (body['task_id'], digest):
                    raise GraphConflict('request_id replay with a different payload')
                return self._view(self._row(db, old[0]))
            if not self._bound(db, body['world_id'], body['session_id']):
                raise GraphConflict('BINDING_UNVERIFIED: this world is not bound to the backend for the current session')
            self._sweep(db, body['session_id'], body['world_id'])
            self._actor_fence(db, body['world_id'], body['actor_id'])
            now = int(time.time())
            target = body['target']
            try:
                db.execute('''INSERT INTO cplus_pap_runs (task_id,request_id,attempt_id,world_id,dimension_id,session_id,
                    actor_id,profile_id,requester_id,action_kind,origin,entry_id,policy_sha256,target_json,payload_hash,state,
                    created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED',?,?)''',
                           (body['task_id'], body['request_id'], body['attempt_id'], body['world_id'], body['dimension_id'],
                            body['session_id'], body['actor_id'], body['profile_id'], body['requester_id'], body['action_kind'],
                            body['origin'], auth['entry_id'], auth['policy_sha256'], _canonical(target), digest, now, now))
            except sqlite3.IntegrityError:
                raise GraphConflict('actor already has an unresolved player action; no second attempt') from None
            for x, y, z in self._cells(target):
                # the accepted native claims are consulted in THIS transaction, then ours is inserted: no gap between the two
                if self._has(db, 'cplus_wn_claims') and db.execute(
                        "SELECT 1 FROM cplus_wn_claims WHERE world_id=? AND dimension_id=? AND kind='block' AND bx=? AND by=? AND bz=? "
                        "AND state IN ('ACTIVE','HELD')", (body['world_id'], body['dimension_id'], x, y, z)).fetchone():
                    raise GraphConflict('CLAIM_CONFLICT: that block cell is claimed by a native break')
                try:
                    db.execute('''INSERT INTO cplus_pap_claims (task_id,world_id,dimension_id,actor_id,bx,by,bz,state,created_at,
                        updated_at) VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?)''',
                               (body['task_id'], body['world_id'], body['dimension_id'], body['actor_id'], x, y, z, now, now))
                except sqlite3.IntegrityError:
                    raise GraphConflict('CLAIM_CONFLICT: that block cell is already claimed by another player action') from None
            return self._view(self._row(db, body['task_id']))

    def start(self, body):
        """Durable EXECUTION_STARTED marker. Forge may dispatch its native call only after this reply."""
        _exact(body, _IDENT + ('requester_id',), 'start')
        for key in body:
            _uuid(body[key], key)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            self._ident(row, body)
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester')
            if row['state'] == 'EXECUTING':
                return self._view(row, execution_started=True, replay=True)
            if row['state'] == 'RESERVED' and not self._bound(db, body['world_id'], body['session_id']):
                raise GraphConflict('BINDING_UNVERIFIED: the binding changed since the reservation; no start')
            if row['state'] != 'RESERVED':
                raise GraphConflict(f"start refused in state {row['state']}")
            now = int(time.time())
            db.execute("UPDATE cplus_pap_runs SET state='EXECUTING',started_at=?,updated_at=? WHERE task_id=?", (now, now, body['task_id']))
            return self._view(self._row(db, body['task_id']), execution_started=True, replay=False)

    def cancel(self, body):
        """Only before the start marker. After it, the receipt (or HOLD) decides; a cancel never rewrites it."""
        _exact(body, _IDENT + ('requester_id', 'reason'), 'cancel')
        for key in _IDENT + ('requester_id',):
            _uuid(body[key], key)
        if body['reason'] not in _CANCEL_REASONS:
            raise GraphConflict('unknown cancel reason')
        digest = _digest(body)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            self._ident(row, body)
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester')
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_pap_terminals WHERE task_id=?', (body['task_id'],)).fetchone()
            if done:
                if done == ('CANCELLED_NO_EFFECT', digest):
                    return self._terminal(db, row)
                raise GraphConflict('terminal already recorded with different evidence')
            if row['state'] != 'RESERVED':
                raise GraphConflict(f"cancel refused in state {row['state']}; the execution marker is durable")
            self._finish(db, row, 'CANCELLED_NO_EFFECT', digest, body, body['reason'])
            return self._terminal(db, self._row(db, body['task_id']))

    # ---- receipts ---------------------------------------------------------------------------------------------------
    @staticmethod
    def _evidence(kind, result, e, target):
        """Typed evidence. The Forge side already turns disagreeing witnesses into a HOLD; this is defence in depth."""
        if type(e) is not dict or len(_canonical(e)) > MAX_EVIDENCE:
            raise GraphConflict('evidence must be an object of bounded size')
        if result == 'PRECONDITION_FAILED':
            if e != {}:
                raise GraphConflict('a precondition failure carries empty evidence')
            return
        if kind == 'place_block':
            _exact(e, ('x', 'y', 'z', 'before_block', 'after_block', 'interaction', 'item_key', 'item_before', 'item_after',
                       'selected_slot', 'journal'), 'place evidence')
        else:
            _exact(e, ('x', 'y', 'z', 'block_id', 'state_before_sha256', 'state_after_sha256', 'interaction', 'journal'), 'use evidence')
        if any(e[a] != target[a] for a in ('x', 'y', 'z')):
            raise GraphConflict('evidence is for a different block cell')
        if e['interaction'] not in ('SUCCESS', 'CONSUME', 'PASS', 'FAIL') or e['journal'] not in ('COMMITTED', 'NOT_REQUIRED'):
            raise GraphConflict('interaction/journal class unknown')
        ok = e['interaction'] in ('SUCCESS', 'CONSUME')
        if kind == 'place_block':
            _res(e['before_block'], 'before_block')
            _res(e['after_block'], 'after_block')
            _res(e['item_key'], 'item_key')
            _int(e['item_before'], 'item_before', 0, 64)
            _int(e['item_after'], 'item_after', 0, 64)
            _int(e['selected_slot'], 'selected_slot', 0, 8)
            if e['item_key'] != target['item_key'] or e['selected_slot'] != target['selected_slot']:
                raise GraphConflict('evidence is for a different item or slot')
            if result == 'EFFECT_VERIFIED':
                if not (ok and e['after_block'] == target['block_id'] and e['before_block'] != e['after_block']
                        and e['item_before'] - e['item_after'] == 1 and e['journal'] == 'COMMITTED'):
                    raise GraphConflict('a verified place needs the block present, one item consumed and the journal committed')
            elif not (not ok and e['after_block'] == e['before_block'] and e['item_before'] == e['item_after']
                      and e['journal'] == 'NOT_REQUIRED'):
                raise GraphConflict('a refused place must leave the block and the item count unchanged')
            return
        _res(e['block_id'], 'block_id')
        _hex64(e['state_before_sha256'], 'state_before_sha256')
        _hex64(e['state_after_sha256'], 'state_after_sha256')
        if e['block_id'] != target['block_id'] or e['state_before_sha256'] != target['state_sha256'] or e['journal'] != 'NOT_REQUIRED':
            raise GraphConflict('use evidence is for a different block or state, or claims an inventory journal')
        changed = e['state_before_sha256'] != e['state_after_sha256']
        if result == 'EFFECT_VERIFIED':
            if not (ok and changed):
                raise GraphConflict('a verified use needs an interaction success and an observed state change')
        elif ok or changed:
            raise GraphConflict('a refused use must leave the block state unchanged')

    _RECEIPT_KEYS = set(_IDENT) | {'schema_version', 'kind', 'action_kind', 'result', 'reason', 'server_tick', 'executed', 'evidence'}

    def _check_receipt(self, receipt, row):
        _exact(receipt, self._RECEIPT_KEYS, 'receipt')
        for key in _IDENT:
            _uuid(receipt[key], key)
        if type(receipt['schema_version']) is not int or receipt['schema_version'] != 1 or receipt['kind'] != 'player_action_receipt':
            raise GraphConflict('receipt kind/schema')
        if receipt['action_kind'] != row['action_kind']:
            raise GraphConflict('receipt action does not match the attempt')
        if type(receipt['server_tick']) is not int or isinstance(receipt['server_tick'], bool) or receipt['server_tick'] < 0:
            raise GraphConflict('server_tick must be a non-negative integer')
        if type(receipt['executed']) is not bool:
            raise GraphConflict('executed must be boolean')
        result = receipt['result']
        if result == 'EFFECT_VERIFIED':
            if receipt['executed'] is not True or receipt['reason'] != '':
                raise GraphConflict('an effect receipt must be an executed run without a failure reason')
            outcome, wanted = result, ('EXECUTING',)
        elif result == 'NO_EFFECT':
            if receipt['executed'] is not True or receipt['reason'] != _NO_EFFECT[row['action_kind']]:
                raise GraphConflict('NO_EFFECT needs executed=true and the refusal reason of this action')
            outcome, wanted = 'FAILED_NO_EFFECT', ('EXECUTING',)
        elif result == 'PRECONDITION_FAILED':
            if receipt['executed'] is not False or receipt['reason'] not in _FAIL_REASONS:
                raise GraphConflict('failure receipt must state executed=false and a known reason')
            outcome, wanted = 'FAILED_NO_EFFECT', ('RESERVED', 'EXECUTING')
        else:
            raise GraphConflict('unknown receipt result')
        self._evidence(row['action_kind'], result, receipt['evidence'], json.loads(row['target_json']))
        return outcome, wanted

    def finalize(self, receipt):
        """Typed receipt -> durable terminal. Claims are released in the same transaction as the terminal row."""
        if type(receipt) is not dict or 'task_id' not in receipt:
            raise GraphConflict('receipt: exact typed fields required')
        _uuid(receipt['task_id'], 'task_id')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, receipt['task_id'])
            if row is None:
                raise GraphConflict('foreign or stale player-action identity')
            outcome, wanted = self._check_receipt(receipt, row)
            self._ident(row, receipt)
            digest = _digest(receipt)
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_pap_terminals WHERE task_id=?', (receipt['task_id'],)).fetchone()
            if done:
                if done == (outcome, digest):
                    return self._terminal(db, row)          # lost ACK: identical terminal, stored result
                raise GraphConflict('terminal already recorded with different evidence; never re-judged')
            if row['state'] not in wanted:
                raise GraphConflict(f"finalize {receipt['result']} refused in state {row['state']}")
            self._finish(db, row, outcome, digest, receipt, receipt['reason'])
            return self._terminal(db, self._row(db, receipt['task_id']))

    def hold(self, body):
        """Forge cannot explain what happened after the marker (or the inventory persistence is uncertain after a real world
        effect): keep the fence and the claims, claim nothing, never roll back, never repeat."""
        _exact(body, _IDENT + ('requester_id', 'reason', 'intended'), 'hold')
        for key in _IDENT + ('requester_id',):
            _uuid(body[key], key)
        if body['reason'] not in _HOLD_REASONS or body['intended'] != {}:
            raise GraphConflict('unknown hold reason, or an intended receipt (repair is not offered for player actions)')
        digest = _digest(body)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            self._ident(row, body)
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester')
            old = db.execute('SELECT hold_hash FROM cplus_pap_holds WHERE task_id=?', (body['task_id'],)).fetchone()
            if old:
                if old[0] == digest:
                    return self._view(row, durable_ack=True, release_actor_fence=False, hold_hash=digest)
                raise GraphConflict('hold already recorded with different evidence')
            if row['state'] not in ('EXECUTING', 'HOLD_IN_DOUBT'):
                raise GraphConflict(f"hold refused in state {row['state']}")
            now = int(time.time())
            db.execute("UPDATE cplus_pap_runs SET state='HOLD_IN_DOUBT',detail=?,updated_at=? WHERE task_id=?", (body['reason'], now, body['task_id']))
            db.execute("UPDATE cplus_pap_claims SET state='HELD',updated_at=? WHERE task_id=? AND state='ACTIVE'", (now, body['task_id']))
            db.execute('INSERT INTO cplus_pap_holds VALUES (?,?,?,?)', (body['task_id'], body['reason'], digest, now))
            return self._view(self._row(db, body['task_id']), durable_ack=True, release_actor_fence=False, hold_hash=digest)

    def reconcile(self, body):
        """Operator-decided release of an unresolved row from OBSERVED state. It never claims the original attempt happened or
        did not happen and never creates an inventory receipt. observed = {x,y,z,state_sha256|block_id}."""
        _exact(body, _IDENT + ('requester_id', 'evidence_class', 'observed'), 'reconcile')
        for key in _IDENT + ('requester_id',):
            _uuid(body[key], key)
        if body['evidence_class'] not in ('EFFECT_OBSERVED', 'PERSISTED_STATE_UNCHANGED') or type(body['observed']) is not dict \
                or len(_canonical(body['observed'])) > MAX_EVIDENCE:
            raise GraphConflict('reconcile evidence malformed')
        digest = _digest(body)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            if row is None or any(row[k] != body[k] for k in ('task_id', 'attempt_id', 'world_id', 'actor_id')):
                raise GraphConflict('foreign or stale player-action identity')
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester: only the requester of the attempt may reconcile it')
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_pap_terminals WHERE task_id=?', (body['task_id'],)).fetchone()
            if done:
                if done[1] == digest:
                    return self._terminal(db, row)
                raise GraphConflict('terminal already recorded; reconcile only releases unresolved rows')
            if not self._bound(db, row['world_id'], body['session_id']):
                raise GraphConflict('BINDING_UNVERIFIED: reconcile is refused while the world/database binding is unverified')
            if row['state'] == 'RESERVED':
                self._finish(db, row, 'CANCELLED_NO_EFFECT', digest, body, 'OPERATOR_RECONCILE')
            elif row['state'] in ('EXECUTING', 'HOLD_IN_DOUBT'):
                obs, target = body['observed'], json.loads(row['target_json'])
                _exact(obs, ('x', 'y', 'z', 'block_id', 'state_sha256'), 'observed')
                if any(obs[a] != target[a] for a in ('x', 'y', 'z')):
                    raise GraphConflict('observation is for a different block cell')
                _res(obs['block_id'], 'block_id')
                _hex64(obs['state_sha256'], 'state_sha256')
                unchanged = obs['state_sha256'] == target['state_sha256']
                if (body['evidence_class'] == 'PERSISTED_STATE_UNCHANGED') != unchanged:
                    raise GraphConflict('the observed block state does not support that class')
                self._finish(db, row, 'RECONCILED_PERSISTED_STATE_UNCHANGED' if unchanged else 'RECONCILED_EFFECT_OBSERVED',
                             digest, body, 'OPERATOR_RECONCILE')
            else:
                raise GraphConflict(f"nothing to reconcile in state {row['state']}")
            return self._terminal(db, self._row(db, body['task_id']))

    # ---- status -----------------------------------------------------------------------------------------------------
    def status(self, body):
        """Attempt-specific status: exact identity or a conflict. The actor's latest run is never substituted."""
        _exact(body, _IDENT + ('requester_id',), 'status')
        for key in body:
            _uuid(body[key], key)
        with self.connection() as db:
            row = self._row(db, body['task_id'])
            if row is None:
                return {'schema_version': 1, 'kind': 'player_action_run', 'state': 'NONE', 'task_id': body['task_id'],
                        'attempt_id': body['attempt_id'], 'world_id': body['world_id'], 'actor_id': body['actor_id'],
                        'session_id': body['session_id'], 'active': False, 'native_mutation': True}
            self._ident(row, body)
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester')
            t = db.execute('SELECT outcome,evidence_hash FROM cplus_pap_terminals WHERE task_id=?', (row['task_id'],)).fetchone()
            h = db.execute('SELECT reason,hold_hash FROM cplus_pap_holds WHERE task_id=?', (row['task_id'],)).fetchone()
            return self._view(row, receipt_hash=t[1] if t else '', terminal_outcome=t[0] if t else '',
                              hold_reason=h[0] if h else '', hold_hash=h[1] if h else '')

    def active(self, body):
        """Operator discovery for Reconcile: the unresolved player action that owns this actor, if any (requester only)."""
        _exact(body, ('world_id', 'actor_id', 'requester_id'), 'active')
        for key in body:
            _uuid(body[key], key)
        with self.connection() as db:
            r = db.execute("SELECT task_id FROM cplus_pap_runs WHERE world_id=? AND actor_id=? AND state IN "
                           "('RESERVED','EXECUTING','HOLD_IN_DOUBT') LIMIT 1", (body['world_id'], body['actor_id'])).fetchone()
            if r is None:
                return {'schema_version': 1, 'kind': 'player_action_run', 'state': 'NONE', 'active': False,
                        'world_id': body['world_id'], 'actor_id': body['actor_id'], 'native_mutation': True}
            run = self._row(db, r[0])
            if run['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester: only the requester of the attempt may reconcile it')
            return self._view(run, target=json.loads(run['target_json']))

    # ---- terminal ---------------------------------------------------------------------------------------------------
    @staticmethod
    def _finish(db, row, outcome, digest, evidence, detail):
        now = int(time.time())
        db.execute('INSERT INTO cplus_pap_terminals VALUES (?,?,?,?,?)', (row['task_id'], outcome, digest, _canonical(evidence), now))
        db.execute('UPDATE cplus_pap_runs SET state=?,detail=?,updated_at=? WHERE task_id=?', (outcome, detail, now, row['task_id']))
        db.execute("UPDATE cplus_pap_claims SET state='RELEASED',updated_at=? WHERE task_id=?", (now, row['task_id']))

    def _terminal(self, db, row):
        t = db.execute('SELECT evidence_hash FROM cplus_pap_terminals WHERE task_id=?', (row['task_id'],)).fetchone()
        return self._view(row, backend_commit_ack=True, release_actor_fence=True, receipt_hash=t[0] if t else '')
