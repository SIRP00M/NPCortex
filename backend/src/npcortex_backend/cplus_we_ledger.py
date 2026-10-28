"""Stage 4 World Effect Ledger, NO-OP foundation. The backend never touches a Minecraft world.

Additive tables only (cplus_we_*); no historical table is altered, no row of another store is written. A ledger row is a
durable CLAIM about one attempt, never proof of a physical effect and never a permission: authority is decided by the Forge
server (Manager packet sender + allow-list) and merely recorded here. This process cannot verify a player; the local token
is the only trust boundary (same as E3).

States:  RESERVED -> EXECUTING -> NO_EFFECT_VERIFIED | FAILED_NO_EFFECT | HOLD_IN_DOUBT -> RECONCILED_NO_EFFECT
         RESERVED -> CANCELLED_NO_EFFECT | FAILED_NO_EFFECT
EXECUTING is a durable marker written BEFORE the executor may run; after a crash RESERVED provably means "executor never
ran" and EXECUTING means "unknown" (HOLD_IN_DOUBT). Exactly-once physical effects are NOT claimed; nothing here is atomic
with Minecraft chunk storage.

Actor fence: partial unique index on (world, actor) for the three non-terminal states, plus a cross-store check (E3 G2/E1
and M04) executed inside the same BEGIN IMMEDIATE write transaction. All stores share one SQLite file, and BEGIN IMMEDIATE
takes SQLite's single write lock, so Check + Reserve cannot interleave with another store's Check + Reserve.
"""
from __future__ import annotations

import hashlib
import json
import sqlite3
import time
from uuid import UUID

from .cplus_e_graph import GraphConflict, ReadOnlyGraph

ACTIVE = ('RESERVED', 'EXECUTING', 'HOLD_IN_DOUBT')
TERMINAL = ('NO_EFFECT_VERIFIED', 'FAILED_NO_EFFECT', 'CANCELLED_NO_EFFECT', 'RECONCILED_NO_EFFECT')
ACTIONS = ('noop',)
ORIGIN = 'MANAGER_PACKET'
_IDS = ('task_id', 'request_id', 'attempt_id', 'world_id', 'actor_id', 'profile_id', 'session_id', 'requester_id')
_RESERVE_KEYS = set(_IDS) | {'schema_version', 'dimension_id', 'action_kind', 'origin', 'authorization'}
_AUTH_KEYS = {'decision', 'entry_id', 'policy_sha256'}
_IDENT = ('task_id', 'attempt_id', 'world_id', 'actor_id', 'session_id')
HEX64 = frozenset('0123456789abcdef')
_FAIL_REASONS = ('PRECONDITION_FAILED', 'START_ACK_LOST', 'REQUESTER_GONE')


def _canonical(data) -> str:
    return json.dumps(data, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False)


def _digest(data) -> str:
    return hashlib.sha256(_canonical(data).encode('utf-8')).hexdigest()


def _uuid(value, label='id'):
    if type(value) is not str:
        raise GraphConflict(f'{label} must be a UUID string')
    try:
        if str(UUID(value)) != value:
            raise ValueError
    except ValueError:
        raise GraphConflict(f'{label} must be a canonical UUID') from None
    return value


def _hex64(value, label):
    if type(value) is not str or len(value) != 64 or any(c not in HEX64 for c in value):
        raise GraphConflict(f'{label} must be a lowercase sha256')
    return value


def _exact(body, keys, label):
    if type(body) is not dict or set(body) != set(keys):
        raise GraphConflict(f'{label}: exact typed fields required')


class WorldEffectStore(ReadOnlyGraph):
    """Same SQLite file and connection discipline as the E3 stores (WAL, synchronous=FULL, BEGIN IMMEDIATE)."""

    def __init__(self, database):
        super().__init__(database)
        with self.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS cplus_we_runs (
                    task_id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL, attempt_id TEXT UNIQUE NOT NULL,
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL, session_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL, profile_id TEXT NOT NULL, requester_id TEXT NOT NULL,
                    action_kind TEXT NOT NULL CHECK (action_kind IN ('noop')),
                    origin TEXT NOT NULL, entry_id TEXT NOT NULL, policy_sha256 TEXT NOT NULL,
                    payload_hash TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('RESERVED','EXECUTING','NO_EFFECT_VERIFIED',
                        'FAILED_NO_EFFECT','CANCELLED_NO_EFFECT','HOLD_IN_DOUBT','RECONCILED_NO_EFFECT')),
                    detail TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL, started_at INTEGER, updated_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS cplus_we_active_actor
                  ON cplus_we_runs(world_id, actor_id)
                  WHERE state IN ('RESERVED','EXECUTING','HOLD_IN_DOUBT');
                CREATE TABLE IF NOT EXISTS cplus_we_terminals (
                    task_id TEXT PRIMARY KEY REFERENCES cplus_we_runs(task_id),
                    outcome TEXT NOT NULL, evidence_hash TEXT NOT NULL, evidence_json TEXT NOT NULL,
                    finalized_at INTEGER NOT NULL
                );
            ''')
            self._sweep(db, current_session=None)

    # ---- restart / session rules ------------------------------------------------------------------------------------
    @staticmethod
    def _sweep(db, current_session, world=None, actor=None):
        """Rows owned by a session that no longer exists can never continue (the executor lives in that session).
        RESERVED: the start marker was never durable, so the executor never ran -> FAILED_NO_EFFECT.
        EXECUTING: unknown -> HOLD_IN_DOUBT (fence kept). Nothing is replayed, nothing is deleted."""
        now = int(time.time())
        scope, args = '', []
        if current_session is not None:
            scope += ' AND session_id<>?'
            args.append(current_session)
        if world is not None:
            scope += ' AND world_id=? AND actor_id=?'
            args += [world, actor]
        db.execute("UPDATE cplus_we_runs SET state='HOLD_IN_DOUBT',detail='session ended while EXECUTING',updated_at=? "
                   "WHERE state='EXECUTING'" + scope, (now, *args))
        stale = db.execute("SELECT task_id FROM cplus_we_runs WHERE state='RESERVED'" + scope, args).fetchall()
        for (task,) in stale:
            evidence = {'reason': 'SESSION_ENDED_BEFORE_START', 'task_id': task}
            db.execute("UPDATE cplus_we_runs SET state='FAILED_NO_EFFECT',detail='session ended before the start marker',"
                       "updated_at=? WHERE task_id=?", (now, task))
            db.execute('INSERT OR IGNORE INTO cplus_we_terminals VALUES (?,?,?,?,?)',
                       (task, 'FAILED_NO_EFFECT', _digest(evidence), _canonical(evidence), now))

    # ---- helpers ----------------------------------------------------------------------------------------------------
    @staticmethod
    def _row(db, task):
        row = db.execute('SELECT task_id,request_id,attempt_id,world_id,dimension_id,session_id,actor_id,profile_id,'
                         'requester_id,action_kind,origin,entry_id,policy_sha256,state,detail FROM cplus_we_runs '
                         'WHERE task_id=?', (task,)).fetchone()
        if row is None:
            return None
        names = ('task_id', 'request_id', 'attempt_id', 'world_id', 'dimension_id', 'session_id', 'actor_id', 'profile_id',
                 'requester_id', 'action_kind', 'origin', 'entry_id', 'policy_sha256', 'state', 'detail')
        return dict(zip(names, row))

    @staticmethod
    def _view(row, **extra):
        out = {'schema_version': 1, 'kind': 'world_effect_run', 'world_effects': False,
               'execution_mode': 'NOOP', 'native_mutation': False}
        out.update({k: row[k] for k in ('task_id', 'request_id', 'attempt_id', 'world_id', 'dimension_id', 'session_id',
                                        'actor_id', 'profile_id', 'requester_id', 'action_kind', 'entry_id', 'state', 'detail')})
        out['can_start'] = row['state'] == 'RESERVED'
        out['active'] = row['state'] in ACTIVE
        out.update(extra)
        return out

    @staticmethod
    def _ident(row, body):
        if row is None or any(row[k] != body[k] for k in _IDENT):
            raise GraphConflict('foreign or stale world-effect identity')

    @staticmethod
    def _cross_fence(db, world, actor):
        """Inspect every other store that can own this actor. A missing table means that store was never used here."""
        def has(name):
            return db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is not None
        if has('cplus_e3_g2_moves') and db.execute(
                "SELECT 1 FROM cplus_e3_g2_moves WHERE world_id=? AND actor_id=? AND state IN "
                "('RESERVED','C2_ACCEPTED','HOLD','CANCEL_PENDING') LIMIT 1", (world, actor)).fetchone():
            raise GraphConflict('E3 movement attempt still owns this actor')
        if has('cplus_e1_tasks') and db.execute(
                "SELECT 1 FROM cplus_e1_tasks WHERE world_id=? AND actor_id=? AND state IN "
                "('QUEUED','HOLD','DISPATCHING','CANCEL_PENDING') LIMIT 1", (world, actor)).fetchone():
            raise GraphConflict('unfinished E1 FIFO task owns this actor')
        if has('cplus_m04_runs') and db.execute(
                "SELECT 1 FROM cplus_m04_runs WHERE world_id=? AND actor_id=? AND state IN "
                "('RESERVED','ACTIVE','HOLD') LIMIT 1", (world, actor)).fetchone():
            raise GraphConflict('M04 task still owns this actor')
        if has('cplus_wn_runs') and db.execute(
                "SELECT 1 FROM cplus_wn_runs WHERE world_id=? AND actor_id=? AND state IN "
                "('RESERVED','EXECUTING','HOLD_IN_DOUBT') LIMIT 1", (world, actor)).fetchone():
            raise GraphConflict('a native world-effect attempt still owns this actor')

    # ---- operations -------------------------------------------------------------------------------------------------
    def reserve(self, body):
        _exact(body, _RESERVE_KEYS, 'reserve')
        if type(body['schema_version']) is not int or body['schema_version'] != 1 or body['action_kind'] not in ACTIONS or body['origin'] != ORIGIN:
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
        digest = _digest(body)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT task_id,payload_hash FROM cplus_we_runs WHERE request_id=?',
                             (body['request_id'],)).fetchone()
            if old:
                if old != (body['task_id'], digest):
                    raise GraphConflict('request_id replay with a different payload')
                return self._view(self._row(db, old[0]))
            # A row owned by an earlier session can never continue; settle it before judging the fence.
            self._sweep(db, body['session_id'], body['world_id'], body['actor_id'])
            db.commit()                               # keep the settlement even when this reserve is then refused
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT task_id,payload_hash FROM cplus_we_runs WHERE request_id=?',
                             (body['request_id'],)).fetchone()
            if old:                                   # a concurrent retry of this very request won the gap
                if old != (body['task_id'], digest):
                    raise GraphConflict('request_id replay with a different payload')
                return self._view(self._row(db, old[0]))
            self._cross_fence(db, body['world_id'], body['actor_id'])
            now = int(time.time())
            try:
                db.execute('''INSERT INTO cplus_we_runs (task_id,request_id,attempt_id,world_id,dimension_id,session_id,
                    actor_id,profile_id,requester_id,action_kind,origin,entry_id,policy_sha256,payload_hash,state,
                    created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED',?,?)''',
                           (body['task_id'], body['request_id'], body['attempt_id'], body['world_id'], body['dimension_id'],
                            body['session_id'], body['actor_id'], body['profile_id'], body['requester_id'],
                            body['action_kind'], body['origin'], auth['entry_id'], auth['policy_sha256'], digest, now, now))
            except sqlite3.IntegrityError:
                raise GraphConflict('actor already has an unresolved world action; no second attempt') from None
            return self._view(self._row(db, body['task_id']))

    def start(self, body):
        """Durable EXECUTION_STARTED marker. Forge may dispatch its executor only after this reply."""
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
            if row['state'] != 'RESERVED':
                raise GraphConflict(f"start refused in state {row['state']}")
            now = int(time.time())
            db.execute("UPDATE cplus_we_runs SET state='EXECUTING',started_at=?,updated_at=? WHERE task_id=?",
                       (now, now, body['task_id']))
            return self._view(self._row(db, body['task_id']), execution_started=True, replay=False)

    def cancel(self, body):
        """Only before the start marker. After it, the receipt (or HOLD) decides; a cancel never rewrites it."""
        _exact(body, _IDENT + ('requester_id', 'reason'), 'cancel')
        for key in _IDENT + ('requester_id',):
            _uuid(body[key], key)
        if body['reason'] not in ('REQUESTER_GONE', 'OPERATOR', 'SERVER_STOPPING'):
            raise GraphConflict('unknown cancel reason')
        evidence = {k: body[k] for k in body}
        digest = _digest(evidence)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            self._ident(row, body)
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester')
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_we_terminals WHERE task_id=?',
                              (body['task_id'],)).fetchone()
            if done:
                if done == ('CANCELLED_NO_EFFECT', digest):
                    return self._terminal(db, row)
                raise GraphConflict('terminal already recorded with different evidence')
            if row['state'] != 'RESERVED':
                raise GraphConflict(f"cancel refused in state {row['state']}; the execution marker is durable")
            self._finish(db, row, 'CANCELLED_NO_EFFECT', digest, evidence, body['reason'])
            return self._terminal(db, self._row(db, body['task_id']))

    def finalize(self, receipt):
        """Typed receipt -> durable terminal. NO_EFFECT needs executed=true after the marker and pre_hash == post_hash."""
        keys = set(_IDENT) | {'schema_version', 'kind', 'result', 'reason', 'server_tick', 'executed',
                              'pre_hash', 'post_hash', 'world_effects'}
        _exact(receipt, keys, 'receipt')
        for key in _IDENT:
            _uuid(receipt[key], key)
        if receipt['schema_version'] != 1 or receipt['kind'] != 'world_effect_receipt' or receipt['world_effects'] is not False:
            raise GraphConflict('receipt kind/schema; a no-op receipt cannot claim a world effect')
        if type(receipt['schema_version']) is not int:
            raise GraphConflict('schema_version must be an integer')
        if type(receipt['server_tick']) is not int or isinstance(receipt['server_tick'], bool) or receipt['server_tick'] < 0:
            raise GraphConflict('server_tick must be a non-negative integer')
        if type(receipt['executed']) is not bool:
            raise GraphConflict('executed must be boolean')
        if receipt['result'] == 'NO_EFFECT':
            if receipt['executed'] is not True or receipt['reason'] != '':
                raise GraphConflict('NO_EFFECT receipt must be an executed run without a failure reason')
            _hex64(receipt['pre_hash'], 'pre_hash')
            _hex64(receipt['post_hash'], 'post_hash')
            if receipt['pre_hash'] != receipt['post_hash']:
                raise GraphConflict('fixture hash changed during a no-op: this is NOT a no-effect receipt')
            outcome, wanted = 'NO_EFFECT_VERIFIED', ('EXECUTING',)
        elif receipt['result'] == 'PRECONDITION_FAILED':
            if receipt['executed'] is not False or receipt['reason'] not in _FAIL_REASONS \
                    or receipt['pre_hash'] != '' or receipt['post_hash'] != '':
                raise GraphConflict('failure receipt must state executed=false, a known reason and empty hashes')
            outcome, wanted = 'FAILED_NO_EFFECT', ('RESERVED', 'EXECUTING')
        else:
            raise GraphConflict('unknown receipt result')
        digest = _digest(receipt)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, receipt['task_id'])
            self._ident(row, receipt)
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_we_terminals WHERE task_id=?',
                              (receipt['task_id'],)).fetchone()
            if done:
                if done == (outcome, digest):
                    return self._terminal(db, row)          # lost ACK: identical terminal, stored result
                raise GraphConflict('terminal already recorded with different evidence; never re-judged')
            if row['state'] not in wanted:
                raise GraphConflict(f"finalize {receipt['result']} refused in state {row['state']}")
            self._finish(db, row, outcome, digest, receipt, receipt['reason'])
            return self._terminal(db, self._row(db, receipt['task_id']))

    def hold(self, body):
        """The Forge side saw something it cannot explain after the marker: keep the fence, claim nothing."""
        _exact(body, _IDENT + ('requester_id', 'reason'), 'hold')
        for key in _IDENT + ('requester_id',):
            _uuid(body[key], key)
        if body['reason'] not in ('FIXTURE_CHANGED', 'EXECUTOR_FAULT'):
            raise GraphConflict('unknown hold reason')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            self._ident(row, body)
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester')
            if row['state'] == 'HOLD_IN_DOUBT':
                return self._view(row, durable_ack=True, release_actor_fence=False)
            if row['state'] != 'EXECUTING':
                raise GraphConflict(f"hold refused in state {row['state']}")
            db.execute("UPDATE cplus_we_runs SET state='HOLD_IN_DOUBT',detail=?,updated_at=? WHERE task_id=?",
                       (body['reason'], int(time.time()), body['task_id']))
            return self._view(self._row(db, body['task_id']), durable_ack=True, release_actor_fence=False)

    def reconcile(self, body):
        """Operator-decided release of an unresolved row. It never claims the original attempt was verified."""
        _exact(body, _IDENT + ('requester_id', 'fresh_hash'), 'reconcile')
        for key in _IDENT + ('requester_id',):
            _uuid(body[key], key)
        _hex64(body['fresh_hash'], 'fresh_hash')
        digest = _digest(body)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            if row is None or any(row[k] != body[k] for k in ('task_id', 'attempt_id', 'world_id', 'actor_id')):
                raise GraphConflict('foreign or stale world-effect identity')
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester: only the requester of the attempt may reconcile it')
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_we_terminals WHERE task_id=?',
                              (body['task_id'],)).fetchone()
            if done:
                if done[1] == digest:
                    return self._terminal(db, row)
                raise GraphConflict('terminal already recorded; reconcile only releases unresolved rows')
            if row['state'] == 'RESERVED':
                self._finish(db, row, 'CANCELLED_NO_EFFECT', digest, body, 'OPERATOR_RECONCILE')
            elif row['state'] in ('EXECUTING', 'HOLD_IN_DOUBT'):
                self._finish(db, row, 'RECONCILED_NO_EFFECT', digest, body, 'OPERATOR_RECONCILE')
            else:
                raise GraphConflict(f"nothing to reconcile in state {row['state']}")
            return self._terminal(db, self._row(db, body['task_id']))

    def status(self, world_id, actor_id):
        _uuid(world_id, 'world_id')
        _uuid(actor_id, 'actor_id')
        with self.connection() as db:
            row = db.execute('SELECT task_id FROM cplus_we_runs WHERE world_id=? AND actor_id=? '
                             'ORDER BY created_at DESC, rowid DESC LIMIT 1', (world_id, actor_id)).fetchone()
            if not row:
                return {'schema_version': 1, 'kind': 'world_effect_run', 'world_id': world_id, 'actor_id': actor_id,
                        'state': 'NONE', 'active': False, 'world_effects': False, 'native_mutation': False}
            return self._view(self._row(db, row[0]))

    # ---- terminal ---------------------------------------------------------------------------------------------------
    @staticmethod
    def _finish(db, row, outcome, digest, evidence, detail):
        now = int(time.time())
        db.execute('INSERT INTO cplus_we_terminals VALUES (?,?,?,?,?)',
                   (row['task_id'], outcome, digest, _canonical(evidence), now))
        db.execute('UPDATE cplus_we_runs SET state=?,detail=?,updated_at=? WHERE task_id=?',
                   (outcome, detail, now, row['task_id']))

    def _terminal(self, db, row):
        # The fence leaves the partial unique index with the state change, in the same transaction as the terminal row.
        t = db.execute('SELECT evidence_hash FROM cplus_we_terminals WHERE task_id=?', (row['task_id'],)).fetchone()
        return self._view(row, durable_ack=True, release_actor_fence=True, receipt_hash=t[0] if t else '')
