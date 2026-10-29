"""Stage 4 NATIVE world-effect ledger (break_block / pickup_item). The backend never touches a Minecraft world.

Additive tables only (cplus_wn_*); no historical table is altered and no row of another store is written. A row is a durable
CLAIM by the Forge server about one attempt, never proof of a physical effect and never a permission: authority is decided by
Forge (authenticated Manager packet sender + Owner allow-list + a server-held preview nonce) and merely recorded here. The local
token is the only trust boundary; this process cannot verify a player (same as E3 and the D+ no-op ledger).

States:  RESERVED -> EXECUTING -> EFFECT_VERIFIED | EFFECT_PARTIAL | FAILED_NO_EFFECT | HOLD_IN_DOUBT
         HOLD_IN_DOUBT -> EFFECT_VERIFIED | EFFECT_PARTIAL (repair, same intended receipt) | RECONCILED_*
         RESERVED -> CANCELLED_NO_EFFECT | FAILED_NO_EFFECT
EXECUTING is a durable marker written BEFORE Forge may dispatch the native call. After a crash RESERVED provably means "the
executor never ran"; EXECUTING means "unknown" (HOLD_IN_DOUBT). Exactly-once physical effects are NOT claimed and nothing here
is atomic with Minecraft chunk, entity or inventory storage. A lost post-effect receipt degrades to HOLD, never to success.

Actor fence : partial unique index on (world, actor) for the three non-terminal states + a cross-store check (D+ no-op, E3 G2,
              E1, M04) inside the same BEGIN IMMEDIATE write transaction (one SQLite file = one write lock).
Resource    : partial unique indexes on ACTIVE/HELD claims: one block cell, one item entity. A conservative same-item proximity
              rule is checked inside the same transaction. Claims only coordinate NPCortex actors (not players, hoppers, mods).
Binding     : (world_id, folder_key, epoch). It limits, but cannot prove, world identity; see bind().
"""
from __future__ import annotations

import hashlib
import json
import math
import re
import sqlite3
import time
from uuid import UUID

from .cplus_e_graph import GraphConflict, ReadOnlyGraph

ACTIVE = ('RESERVED', 'EXECUTING', 'HOLD_IN_DOUBT')
TERMINAL = ('EFFECT_VERIFIED', 'EFFECT_PARTIAL', 'FAILED_NO_EFFECT', 'CANCELLED_NO_EFFECT',
            'RECONCILED_PERSISTED_TRANSFER', 'RECONCILED_PERSISTED_STATE_UNCHANGED', 'RECONCILED_EFFECT_OBSERVED')
ACTIONS = ('break_block', 'pickup_item')
ORIGIN = 'MANAGER_PACKET'
CAPABILITY = 'native-effects-v1'
_IDS = ('task_id', 'request_id', 'attempt_id', 'world_id', 'actor_id', 'profile_id', 'session_id', 'requester_id')
_IDENT = ('task_id', 'attempt_id', 'world_id', 'actor_id', 'session_id')
_AUTH_KEYS = {'decision', 'entry_id', 'policy_sha256'}
HEX64 = frozenset('0123456789abcdef')
_RESOURCE = re.compile(r'^[a-z0-9_.-]+:[a-z0-9_./-]+$')
_FOLDER = re.compile(r'^[\w .()\[\]+,=@#!-]{1,128}$')
# Same-item stacks closer than this to an ACTIVE/HELD claim could merge into it (vanilla merge box is about 0.75 x 0.25).
PILE_H, PILE_V = 2.0, 1.0
_FAIL_REASONS = ('PRECONDITION_FAILED', 'START_ACK_LOST', 'REQUESTER_GONE', 'ENTITY_GONE', 'TARGET_CHANGED', 'TOOL_CHANGED',
                 'JOURNAL_UNAVAILABLE', 'BINDING_LOST', 'ABORTED', 'PERMISSION_WITHDRAWN', 'OUT_OF_REACH', 'STALLED',
                 'ACTOR_CHANGED', 'NOT_ELIGIBLE', 'CHUNK_UNLOADED', 'MINING_ABORTED')
_NO_EFFECT_REASONS = ('BREAK_REFUSED', 'NO_TRANSFER')
_HOLD_REASONS = ('EXECUTOR_EXCEPTION', 'AMBIGUOUS_EVIDENCE', 'INVENTORY_COMMIT_FAILED', 'UNEXPECTED_STATE')
_CANCEL_REASONS = ('REQUESTER_GONE', 'OPERATOR', 'SERVER_STOPPING')
MAX_EVIDENCE = 4096


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


def _int(value, label, lo, hi):
    if type(value) is not int or not lo <= value <= hi:
        raise GraphConflict(f'{label} must be an integer in [{lo},{hi}]')
    return value


def _num(value, label, lo, hi):
    if type(value) not in (int, float) or isinstance(value, bool) or not math.isfinite(value) or not lo <= value <= hi:
        raise GraphConflict(f'{label} must be a finite number in [{lo},{hi}]')
    return value


def _res(value, label):
    if type(value) is not str or len(value) > 128 or not _RESOURCE.match(value):
        raise GraphConflict(f'{label} must be a namespaced resource id')
    return value


class NativeEffectStore(ReadOnlyGraph):
    """Same SQLite file and connection discipline as the E3 and D+ stores (WAL, synchronous=FULL, BEGIN IMMEDIATE)."""

    def __init__(self, database):
        super().__init__(database)
        with self.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS cplus_wn_bindings (
                    world_id TEXT PRIMARY KEY, folder_key TEXT NOT NULL, epoch INTEGER NOT NULL,
                    saved_epoch INTEGER NOT NULL, bound_from INTEGER NOT NULL, last_session TEXT NOT NULL,
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS cplus_wn_binding_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, world_id TEXT NOT NULL, kind TEXT NOT NULL,
                    detail_json TEXT NOT NULL, created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS cplus_wn_runs (
                    task_id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL, attempt_id TEXT UNIQUE NOT NULL,
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL, session_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL, profile_id TEXT NOT NULL, requester_id TEXT NOT NULL,
                    action_kind TEXT NOT NULL CHECK (action_kind IN ('break_block','pickup_item')),
                    origin TEXT NOT NULL, entry_id TEXT NOT NULL, policy_sha256 TEXT NOT NULL,
                    target_json TEXT NOT NULL, payload_hash TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('RESERVED','EXECUTING','EFFECT_VERIFIED','EFFECT_PARTIAL',
                        'FAILED_NO_EFFECT','CANCELLED_NO_EFFECT','HOLD_IN_DOUBT','RECONCILED_PERSISTED_TRANSFER',
                        'RECONCILED_PERSISTED_STATE_UNCHANGED','RECONCILED_EFFECT_OBSERVED')),
                    detail TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL, started_at INTEGER, updated_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS cplus_wn_active_actor
                  ON cplus_wn_runs(world_id, actor_id)
                  WHERE state IN ('RESERVED','EXECUTING','HOLD_IN_DOUBT');
                CREATE TABLE IF NOT EXISTS cplus_wn_terminals (
                    task_id TEXT PRIMARY KEY REFERENCES cplus_wn_runs(task_id),
                    outcome TEXT NOT NULL, evidence_hash TEXT NOT NULL, evidence_json TEXT NOT NULL,
                    finalized_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS cplus_wn_holds (
                    task_id TEXT PRIMARY KEY REFERENCES cplus_wn_runs(task_id),
                    reason TEXT NOT NULL, hold_hash TEXT NOT NULL, intended_hash TEXT NOT NULL,
                    intended_json TEXT NOT NULL, created_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS cplus_wn_claims (
                    claim_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    task_id TEXT NOT NULL REFERENCES cplus_wn_runs(task_id),
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL, actor_id TEXT NOT NULL,
                    kind TEXT NOT NULL CHECK (kind IN ('block','item')),
                    bx INTEGER, by INTEGER, bz INTEGER,
                    entity_uuid TEXT, item_key TEXT, ox REAL, oy REAL, oz REAL,
                    state TEXT NOT NULL CHECK (state IN ('ACTIVE','HELD','RELEASED')),
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS cplus_wn_claim_block
                  ON cplus_wn_claims(world_id, dimension_id, bx, by, bz)
                  WHERE kind='block' AND state IN ('ACTIVE','HELD');
                CREATE UNIQUE INDEX IF NOT EXISTS cplus_wn_claim_item
                  ON cplus_wn_claims(world_id, dimension_id, entity_uuid)
                  WHERE kind='item' AND state IN ('ACTIVE','HELD');
                CREATE INDEX IF NOT EXISTS cplus_wn_claim_task ON cplus_wn_claims(task_id);
            ''')
            self._sweep(db, current_session=None)

    # ---- restart / session rules ------------------------------------------------------------------------------------
    @staticmethod
    def _sweep(db, current_session, world=None, actor=None):
        """Rows owned by a session that no longer exists can never continue (the executor lives in that session).
        RESERVED: the start marker was never durable, so the executor never ran -> FAILED_NO_EFFECT, claims released.
        EXECUTING: unknown -> HOLD_IN_DOUBT, claims HELD. Nothing is replayed and nothing is deleted."""
        now = int(time.time())
        scope, args = '', []
        if current_session is not None:
            scope += ' AND session_id<>?'
            args.append(current_session)
        if world is not None:
            scope += ' AND world_id=?'
            args.append(world)
        if actor is not None:
            scope += ' AND actor_id=?'
            args.append(actor)
        held = db.execute("SELECT task_id FROM cplus_wn_runs WHERE state='EXECUTING'" + scope, args).fetchall()
        db.execute("UPDATE cplus_wn_runs SET state='HOLD_IN_DOUBT',detail='session ended while EXECUTING',updated_at=? "
                   "WHERE state='EXECUTING'" + scope, (now, *args))
        for (task,) in held:
            db.execute("UPDATE cplus_wn_claims SET state='HELD',updated_at=? WHERE task_id=? AND state='ACTIVE'", (now, task))
        stale = db.execute("SELECT task_id FROM cplus_wn_runs WHERE state='RESERVED'" + scope, args).fetchall()
        for (task,) in stale:
            evidence = {'reason': 'SESSION_ENDED_BEFORE_START', 'task_id': task}
            db.execute("UPDATE cplus_wn_runs SET state='FAILED_NO_EFFECT',detail='session ended before the start marker',"
                       "updated_at=? WHERE task_id=?", (now, task))
            db.execute("UPDATE cplus_wn_claims SET state='RELEASED',updated_at=? WHERE task_id=?", (now, task))
            db.execute('INSERT OR IGNORE INTO cplus_wn_terminals VALUES (?,?,?,?,?)',
                       (task, 'FAILED_NO_EFFECT', _digest(evidence), _canonical(evidence), now))

    # ---- world binding ----------------------------------------------------------------------------------------------
    def bind(self, body):
        """Compare-and-advance handshake. A world proves lineage PROGRESS (its epoch), the folder proves WHICH COPY.

        Neither is proof of physical world identity. Guarantees: a world whose folder or epoch does not match the record cannot
        start a native attempt; a refusal writes nothing to any existing row. NOT guaranteed: telling apart two worlds with the
        same folder name and equal state; the very first bind of a database (trust on first use); a token holder."""
        _exact(body, ('schema_version', 'world_id', 'folder_key', 'epoch', 'session_id', 'contract'), 'bind')
        if body['schema_version'] != 1 or type(body['schema_version']) is not int or body['contract'] != CAPABILITY:
            raise GraphConflict('unsupported bind schema or contract')
        _uuid(body['world_id'], 'world_id')
        _uuid(body['session_id'], 'session_id')
        if type(body['folder_key']) is not str or not _FOLDER.fullmatch(body['folder_key']):
            raise GraphConflict('folder_key malformed')
        epoch = _int(body['epoch'], 'epoch', 0, 2 ** 53)
        now = int(time.time())
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT folder_key,epoch,saved_epoch,bound_from,last_session FROM cplus_wn_bindings WHERE world_id=?',
                             (body['world_id'],)).fetchone()
            if row is None:
                if epoch != 0:
                    self._event(db, body['world_id'], 'REFUSED_WORLD_AHEAD', body)
                    db.commit()
                    raise GraphConflict('WORLD_AHEAD: the world carries a binding epoch this database has never seen')
                db.execute('INSERT INTO cplus_wn_bindings VALUES (?,?,?,?,?,?,?,?)',
                           (body['world_id'], body['folder_key'], 1, 0, 0, body['session_id'], now, now))
                self._event(db, body['world_id'], 'BOUND_FIRST', body)
                return self._bound(body, 1, 'FIRST_BIND')
            folder, current, saved, bound_from, last = row
            if last == body['session_id'] and folder == body['folder_key'] and epoch == bound_from:
                return self._bound(body, current, 'REPLAY')           # lost reply, same session: idempotent
            reason = None
            if folder != body['folder_key']:
                reason = 'FOLDER_MISMATCH'
            elif epoch > current:
                reason = 'WORLD_AHEAD'
            elif epoch < current and epoch != saved:
                reason = 'WORLD_REGRESSION'
            if reason:
                self._event(db, body['world_id'], 'REFUSED_' + reason, body)
                db.commit()                  # the audit row is the only write; no existing row is touched
                raise GraphConflict(f'{reason}: native effects are refused for this world/database pairing')
            # A new session of this world: everything the previous session left unfinished can never continue.
            self._sweep(db, body['session_id'], body['world_id'])
            db.execute('UPDATE cplus_wn_bindings SET epoch=?,bound_from=?,last_session=?,updated_at=? WHERE world_id=?',
                       (current + 1, epoch, body['session_id'], now, body['world_id']))
            self._event(db, body['world_id'], 'BOUND', body)
            return self._bound(body, current + 1, 'LAG_TOLERATED' if epoch < current else 'ADVANCED')

    @staticmethod
    def _bound(body, epoch, how):
        return {'schema_version': 1, 'kind': 'native_binding', 'state': 'BOUND', 'world_id': body['world_id'],
                'session_id': body['session_id'], 'epoch': epoch, 'how': how, 'capabilities': [CAPABILITY],
                'world_effects': False}

    @staticmethod
    def _event(db, world, kind, detail):
        db.execute('INSERT INTO cplus_wn_binding_events(world_id,kind,detail_json,created_at) VALUES (?,?,?,?)',
                   (world, kind, _canonical(detail), int(time.time())))

    def saved(self, body):
        """Best-effort save confirmation (Forge saw LevelEvent.Save). Narrows crash lag; it proves no disk flush."""
        _exact(body, ('schema_version', 'world_id', 'session_id', 'epoch'), 'saved')
        _uuid(body['world_id'], 'world_id')
        _uuid(body['session_id'], 'session_id')
        epoch = _int(body['epoch'], 'epoch', 0, 2 ** 53)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT epoch,saved_epoch,last_session FROM cplus_wn_bindings WHERE world_id=?',
                             (body['world_id'],)).fetchone()
            if row is None or row[2] != body['session_id'] or epoch > row[0]:
                raise GraphConflict('save confirmation does not match the bound session')
            db.execute('UPDATE cplus_wn_bindings SET saved_epoch=?,updated_at=? WHERE world_id=?',
                       (max(row[1], epoch), int(time.time()), body['world_id']))
            return {'schema_version': 1, 'kind': 'native_saved', 'world_id': body['world_id'],
                    'saved_epoch': max(row[1], epoch), 'world_effects': False}

    def rebind(self, body):
        """Operator-directed fork/rename: moves the folder key of one world_id and keeps every row. Audited. Never automatic."""
        _exact(body, ('schema_version', 'world_id', 'folder_key', 'confirm', 'reason'), 'rebind')
        _uuid(body['world_id'], 'world_id')
        if body['confirm'] != 'REBIND' or type(body['reason']) is not str or not (1 <= len(body['reason']) <= 200):
            raise GraphConflict('rebind needs confirm=REBIND and a reason')
        if type(body['folder_key']) is not str or not _FOLDER.fullmatch(body['folder_key']):
            raise GraphConflict('folder_key malformed')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT folder_key,epoch FROM cplus_wn_bindings WHERE world_id=?', (body['world_id'],)).fetchone()
            if row is None:
                raise GraphConflict('no binding to move')
            # The new pairing starts from the record's epoch: the next bind must present it (or the saved epoch).
            db.execute('UPDATE cplus_wn_bindings SET folder_key=?,last_session=?,updated_at=? WHERE world_id=?',
                       (body['folder_key'], '00000000-0000-0000-0000-000000000000', int(time.time()), body['world_id']))
            self._event(db, body['world_id'], 'REBOUND', {'from': row[0], 'to': body['folder_key'], 'reason': body['reason']})
            return {'schema_version': 1, 'kind': 'native_rebind', 'world_id': body['world_id'], 'folder_key': body['folder_key'],
                    'epoch': row[1], 'world_effects': False}

    @staticmethod
    def _bound_session(db, world, session):
        row = db.execute('SELECT last_session FROM cplus_wn_bindings WHERE world_id=?', (world,)).fetchone()
        return row is not None and row[0] == session

    # ---- helpers ----------------------------------------------------------------------------------------------------
    _COLS = ('task_id,request_id,attempt_id,world_id,dimension_id,session_id,actor_id,profile_id,requester_id,action_kind,'
             'origin,entry_id,policy_sha256,target_json,state,detail')

    @classmethod
    def _row(cls, db, task):
        row = db.execute(f'SELECT {cls._COLS} FROM cplus_wn_runs WHERE task_id=?', (task,)).fetchone()
        return None if row is None else dict(zip(cls._COLS.split(','), row))

    @staticmethod
    def _view(row, **extra):
        out = {'schema_version': 1, 'kind': 'native_effect_run', 'native_mutation': True}
        out.update({k: row[k] for k in ('task_id', 'request_id', 'attempt_id', 'world_id', 'dimension_id', 'session_id',
                                        'actor_id', 'profile_id', 'requester_id', 'action_kind', 'entry_id', 'state', 'detail')})
        out['can_start'] = row['state'] == 'RESERVED'
        out['active'] = row['state'] in ACTIVE
        out.update(extra)
        return out

    @staticmethod
    def _ident(row, body):
        if row is None or any(row[k] != body[k] for k in _IDENT):
            raise GraphConflict('foreign or stale native-effect identity')

    @staticmethod
    def _cross_fence(db, world, actor):
        def has(name):
            return db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is not None
        for table, states, label in (
                ('cplus_we_runs', ('RESERVED', 'EXECUTING', 'HOLD_IN_DOUBT'), 'a no-op world-effect attempt'),
                ('cplus_e3_g2_moves', ('RESERVED', 'C2_ACCEPTED', 'HOLD', 'CANCEL_PENDING'), 'an E3 movement attempt'),
                ('cplus_e1_tasks', ('QUEUED', 'HOLD', 'DISPATCHING', 'CANCEL_PENDING'), 'an E1 FIFO task'),
                ('cplus_m04_runs', ('RESERVED', 'ACTIVE', 'HOLD'), 'an M04 task'),
                ('cplus_pap_runs', ('RESERVED', 'EXECUTING', 'HOLD_IN_DOUBT'), 'a PAP place/use attempt')):
            if has(table) and db.execute(
                    f"SELECT 1 FROM {table} WHERE world_id=? AND actor_id=? AND state IN ({','.join('?' for _ in states)}) LIMIT 1",
                    (world, actor, *states)).fetchone():
                raise GraphConflict(f'{label} still owns this actor')

    @staticmethod
    def _target(kind, target):
        if kind == 'break_block':
            _exact(target, ('kind', 'x', 'y', 'z', 'block_id', 'state_sha256'), 'target')
            if target['kind'] != 'block':
                raise GraphConflict('break_block needs a block target')
            _int(target['x'], 'x', -30000000, 30000000)
            _int(target['y'], 'y', -4096, 4096)
            _int(target['z'], 'z', -30000000, 30000000)
            _res(target['block_id'], 'block_id')
            _hex64(target['state_sha256'], 'state_sha256')
        else:
            _exact(target, ('kind', 'entity_uuid', 'item_key', 'nbt_sha256', 'count', 'x', 'y', 'z', 'pre_count'), 'target')
            if target['kind'] != 'item':
                raise GraphConflict('pickup_item needs an item target')
            _uuid(target['entity_uuid'], 'entity_uuid')
            _res(target['item_key'], 'item_key')
            _hex64(target['nbt_sha256'], 'nbt_sha256')
            _int(target['count'], 'count', 1, 1000)
            _num(target['x'], 'x', -30000000, 30000000)
            _num(target['y'], 'y', -4096, 4096)
            _num(target['z'], 'z', -30000000, 30000000)
            _int(target['pre_count'], 'pre_count', 0, 10000000)

    # ---- operations -------------------------------------------------------------------------------------------------
    def reserve(self, body):
        _exact(body, set(_IDS) | {'schema_version', 'dimension_id', 'action_kind', 'origin', 'authorization', 'target'}, 'reserve')
        if type(body['schema_version']) is not int or body['schema_version'] != 1 or body['action_kind'] not in ACTIONS \
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
            old = db.execute('SELECT task_id,payload_hash FROM cplus_wn_runs WHERE request_id=?', (body['request_id'],)).fetchone()
            if old:
                if old != (body['task_id'], digest):
                    raise GraphConflict('request_id replay with a different payload')
                return self._view(self._row(db, old[0]))
            # Only the bound session may settle anything: a stale or foreign session must not sweep the live session's rows.
            if not self._bound_session(db, body['world_id'], body['session_id']):
                raise GraphConflict('BINDING_UNVERIFIED: this world is not bound to the backend for the current session')
            # Claims are shared between actors, so a dead session is settled for the whole world, not only for this actor.
            self._sweep(db, body['session_id'], body['world_id'])
            db.commit()                               # keep the settlement even when this reserve is then refused
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT task_id,payload_hash FROM cplus_wn_runs WHERE request_id=?', (body['request_id'],)).fetchone()
            if old:                                   # a concurrent retry of this very request won the gap
                if old != (body['task_id'], digest):
                    raise GraphConflict('request_id replay with a different payload')
                return self._view(self._row(db, old[0]))
            if not self._bound_session(db, body['world_id'], body['session_id']):
                raise GraphConflict('BINDING_UNVERIFIED: this world is not bound to the backend for the current session')
            self._cross_fence(db, body['world_id'], body['actor_id'])
            now = int(time.time())
            target = body['target']
            try:
                db.execute('''INSERT INTO cplus_wn_runs (task_id,request_id,attempt_id,world_id,dimension_id,session_id,
                    actor_id,profile_id,requester_id,action_kind,origin,entry_id,policy_sha256,target_json,payload_hash,state,
                    created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED',?,?)''',
                           (body['task_id'], body['request_id'], body['attempt_id'], body['world_id'], body['dimension_id'],
                            body['session_id'], body['actor_id'], body['profile_id'], body['requester_id'],
                            body['action_kind'], body['origin'], auth['entry_id'], auth['policy_sha256'],
                            _canonical(target), digest, now, now))
            except sqlite3.IntegrityError:
                raise GraphConflict('actor already has an unresolved native action; no second attempt') from None
            self._claim(db, body, target, now)
            return self._view(self._row(db, body['task_id']))

    @staticmethod
    def _claim(db, body, target, now):
        world, dim = body['world_id'], body['dimension_id']
        if target['kind'] == 'block':
            # PAP-G2: place/use claims live in cplus_pap_claims; consult them in THIS transaction before ours is inserted.
            if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='cplus_pap_claims'").fetchone() and db.execute(
                    "SELECT 1 FROM cplus_pap_claims WHERE world_id=? AND dimension_id=? AND bx=? AND by=? AND bz=? "
                    "AND state IN ('ACTIVE','HELD')", (world, dim, target['x'], target['y'], target['z'])).fetchone():
                raise GraphConflict('CLAIM_CONFLICT: that block cell is claimed by a place/use action')
            try:
                db.execute('''INSERT INTO cplus_wn_claims (task_id,world_id,dimension_id,actor_id,kind,bx,by,bz,state,created_at,
                    updated_at) VALUES (?,?,?,?, 'block',?,?,?, 'ACTIVE',?,?)''',
                           (body['task_id'], world, dim, body['actor_id'], target['x'], target['y'], target['z'], now, now))
            except sqlite3.IntegrityError:
                raise GraphConflict('CLAIM_CONFLICT: that block is already claimed by another native action') from None
            return
        near = db.execute('''SELECT task_id FROM cplus_wn_claims WHERE world_id=? AND dimension_id=? AND kind='item'
            AND state IN ('ACTIVE','HELD') AND item_key=? AND ABS(ox-?)<=? AND ABS(oz-?)<=? AND ABS(oy-?)<=? LIMIT 1''',
                          (world, dim, target['item_key'], target['x'], PILE_H, target['z'], PILE_H, target['y'], PILE_V)).fetchone()
        if near:
            raise GraphConflict('CLAIM_CONFLICT: a nearby stack of the same item is already claimed (it could merge)')
        try:
            db.execute('''INSERT INTO cplus_wn_claims (task_id,world_id,dimension_id,actor_id,kind,entity_uuid,item_key,ox,oy,oz,
                state,created_at,updated_at) VALUES (?,?,?,?, 'item',?,?,?,?,?, 'ACTIVE',?,?)''',
                       (body['task_id'], world, dim, body['actor_id'], target['entity_uuid'], target['item_key'],
                        float(target['x']), float(target['y']), float(target['z']), now, now))
        except sqlite3.IntegrityError:
            raise GraphConflict('CLAIM_CONFLICT: that item entity is already claimed by another native action') from None

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
            if row['state'] == 'RESERVED' and not self._bound_session(db, body['world_id'], body['session_id']):
                raise GraphConflict('BINDING_UNVERIFIED: the binding changed since the reservation; no start')
            if row['state'] != 'RESERVED':
                raise GraphConflict(f"start refused in state {row['state']}")
            now = int(time.time())
            db.execute("UPDATE cplus_wn_runs SET state='EXECUTING',started_at=?,updated_at=? WHERE task_id=?",
                       (now, now, body['task_id']))
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
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_wn_terminals WHERE task_id=?', (body['task_id'],)).fetchone()
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
        """Typed evidence. The consistency rules here are the backend's defence in depth: the Forge side already refuses to
        report a result whose witnesses disagree (that case is a HOLD), and a body that claims otherwise is rejected."""
        if type(e) is not dict or len(_canonical(e)) > MAX_EVIDENCE:
            raise GraphConflict('evidence must be an object of bounded size')
        if result == 'PRECONDITION_FAILED':
            if e != {}:
                raise GraphConflict('a precondition failure carries empty evidence')
            return
        if kind == 'break_block':
            keys = {'x', 'y', 'z', 'before_block', 'after_block', 'destroy_returned'}
            if result == 'EFFECT_VERIFIED':
                keys |= {'tool_before', 'tool_after', 'tool_broke', 'drops', 'collateral', 'journal'}
            _exact(e, keys, 'break evidence')
            for axis in ('x', 'y', 'z'):
                if e[axis] != target[axis]:
                    raise GraphConflict('evidence is for a different block')
            _res(e['before_block'], 'before_block')
            _res(e['after_block'], 'after_block')
            if type(e['destroy_returned']) is not bool:
                raise GraphConflict('destroy_returned must be boolean')
            if result == 'EFFECT_VERIFIED':
                if e['destroy_returned'] is not True or e['before_block'] != target['block_id']:
                    raise GraphConflict('a verified break needs destroy=true on the previewed block')
                if e['collateral'] not in ('NONE', 'COLLATERAL') or e['journal'] not in ('COMMITTED', 'NOT_REQUIRED'):
                    raise GraphConflict('collateral/journal class unknown')
                if type(e['tool_broke']) is not bool or type(e['tool_before']) is not str or type(e['tool_after']) is not str \
                        or len(e['tool_before']) > 200 or len(e['tool_after']) > 200:
                    raise GraphConflict('tool state malformed')
                drops = e['drops']
                if type(drops) is not list or len(drops) > 16:
                    raise GraphConflict('drops must be a bounded list')
                for d in drops:
                    _exact(d, ('uuid', 'item', 'count'), 'drop')
                    _uuid(d['uuid'], 'drop uuid')
                    _res(d['item'], 'drop item')
                    _int(d['count'], 'drop count', 1, 1000)
            else:                                    # NO_EFFECT: the break was refused and nothing changed
                if e['destroy_returned'] is not False or e['after_block'] != e['before_block']:
                    raise GraphConflict('a refused break must leave the block unchanged')
            return
        keys = {'entity_uuid', 'item_key', 'entity_count_before', 'entity_count_after', 'delta', 'event_count',
                'pre_count', 'post_count', 'journal'}
        _exact(e, keys, 'pickup evidence')
        if e['entity_uuid'] != target['entity_uuid'] or e['item_key'] != target['item_key']:
            raise GraphConflict('evidence is for a different item')
        for key in ('entity_count_before', 'entity_count_after', 'delta', 'event_count', 'pre_count', 'post_count'):
            _int(e[key], key, 0, 10000000)
        if e['journal'] not in ('COMMITTED', 'NOT_REQUIRED') or e['entity_count_before'] < 1:
            raise GraphConflict('pickup evidence malformed')
        reduced = e['entity_count_before'] - e['entity_count_after']
        if result == 'EFFECT_VERIFIED':
            if not (e['delta'] >= 1 and e['delta'] == e['event_count'] == reduced and e['entity_count_after'] == 0
                    and e['post_count'] - e['pre_count'] == e['delta'] and e['journal'] == 'COMMITTED'):
                raise GraphConflict('a verified transfer needs inventory, event and entity to agree and the journal committed')
        elif result == 'EFFECT_PARTIAL':
            # Vanilla fires the pickup event only when Inventory.add reports progress in its LAST loop iteration, so a PARTIAL add
            # (stack shrunk, inventory grown) normally fires none: inventory and entity are the witnesses, the event may be 0.
            if not (0 < e['delta'] < e['entity_count_before'] and e['delta'] == reduced and e['event_count'] in (0, e['delta'])
                    and e['entity_count_after'] > 0 and e['post_count'] - e['pre_count'] == e['delta'] and e['journal'] == 'COMMITTED'):
                raise GraphConflict('a partial transfer needs inventory and entity to agree on 0 < delta < count (event 0 or delta)')
        else:                                        # NO_EFFECT / NO_TRANSFER
            if not (e['delta'] == 0 and e['event_count'] == 0 and e['entity_count_after'] == e['entity_count_before']
                    and e['post_count'] == e['pre_count']):
                raise GraphConflict('a no-transfer receipt needs every witness quiet')

    _RECEIPT_KEYS = set(_IDENT) | {'schema_version', 'kind', 'action_kind', 'result', 'reason', 'server_tick', 'executed',
                                   'evidence'}

    def _check_receipt(self, receipt, row):
        _exact(receipt, self._RECEIPT_KEYS, 'receipt')
        for key in _IDENT:
            _uuid(receipt[key], key)
        if type(receipt['schema_version']) is not int or receipt['schema_version'] != 1 \
                or receipt['kind'] != 'native_effect_receipt':
            raise GraphConflict('receipt kind/schema')
        if receipt['action_kind'] != row['action_kind']:
            raise GraphConflict('receipt action does not match the attempt')
        if type(receipt['server_tick']) is not int or isinstance(receipt['server_tick'], bool) or receipt['server_tick'] < 0:
            raise GraphConflict('server_tick must be a non-negative integer')
        if type(receipt['executed']) is not bool:
            raise GraphConflict('executed must be boolean')
        target = json.loads(row['target_json'])
        result = receipt['result']
        if result in ('EFFECT_VERIFIED', 'EFFECT_PARTIAL'):
            if result == 'EFFECT_PARTIAL' and row['action_kind'] != 'pickup_item':
                raise GraphConflict('only a pickup can be partial')
            if receipt['executed'] is not True or receipt['reason'] != '':
                raise GraphConflict('an effect receipt must be an executed run without a failure reason')
            outcome, wanted = result, ('EXECUTING',)
        elif result == 'NO_EFFECT':
            if receipt['executed'] is not True or receipt['reason'] not in _NO_EFFECT_REASONS:
                raise GraphConflict('NO_EFFECT needs executed=true and a known reason')
            outcome, wanted = 'FAILED_NO_EFFECT', ('EXECUTING',)
        elif result == 'PRECONDITION_FAILED':
            if receipt['executed'] is not False or receipt['reason'] not in _FAIL_REASONS:
                raise GraphConflict('failure receipt must state executed=false and a known reason')
            outcome, wanted = 'FAILED_NO_EFFECT', ('RESERVED', 'EXECUTING')
        else:
            raise GraphConflict('unknown receipt result')
        self._evidence(row['action_kind'], result, receipt['evidence'], target)
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
                raise GraphConflict('foreign or stale native-effect identity')
            outcome, wanted = self._check_receipt(receipt, row)
            self._ident(row, receipt)
            digest = _digest(receipt)
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_wn_terminals WHERE task_id=?', (receipt['task_id'],)).fetchone()
            if done:
                if done == (outcome, digest):
                    return self._terminal(db, row)          # lost ACK: identical terminal, stored result
                raise GraphConflict('terminal already recorded with different evidence; never re-judged')
            if row['state'] not in wanted:
                raise GraphConflict(f"finalize {receipt['result']} refused in state {row['state']}")
            self._finish(db, row, outcome, digest, receipt, receipt['reason'])
            return self._terminal(db, self._row(db, receipt['task_id']))

    def hold(self, body):
        """Forge cannot explain what happened after the marker: keep the fence and the claims, claim nothing.

        {intended} (optional, INVENTORY_COMMIT_FAILED only) is the complete receipt Forge would have sent had the journal
        commit succeeded; it is validated like a receipt and stored so a later persist-only repair adds no new evidence."""
        _exact(body, _IDENT + ('requester_id', 'reason', 'intended'), 'hold')
        for key in _IDENT + ('requester_id',):
            _uuid(body[key], key)
        if body['reason'] not in _HOLD_REASONS:
            raise GraphConflict('unknown hold reason')
        if len(_canonical(body['intended'])) > 2 * MAX_EVIDENCE:
            raise GraphConflict('intended receipt too large')
        digest = _digest(body)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            self._ident(row, body)
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester')
            intended_hash = ''
            if body['reason'] == 'INVENTORY_COMMIT_FAILED':
                outcome, _ = self._check_receipt(body['intended'], row)
                if outcome not in ('EFFECT_VERIFIED', 'EFFECT_PARTIAL') or body['intended']['evidence'].get('journal') != 'COMMITTED' \
                        or any(body['intended'][k] != body[k] for k in _IDENT):
                    raise GraphConflict('the intended receipt must be a committed effect for this attempt')
                intended_hash = _digest(body['intended'])
            elif body['intended'] != {}:
                raise GraphConflict('only INVENTORY_COMMIT_FAILED carries an intended receipt')
            old = db.execute('SELECT hold_hash FROM cplus_wn_holds WHERE task_id=?', (body['task_id'],)).fetchone()
            if old:
                if old[0] == digest:
                    return self._view(row, durable_ack=True, release_actor_fence=False, hold_hash=digest)
                raise GraphConflict('hold already recorded with different evidence')
            if row['state'] == 'HOLD_IN_DOUBT':          # a restart sweep got there first: keep, record the evidence only
                pass
            elif row['state'] != 'EXECUTING':
                raise GraphConflict(f"hold refused in state {row['state']}")
            now = int(time.time())
            db.execute("UPDATE cplus_wn_runs SET state='HOLD_IN_DOUBT',detail=?,updated_at=? WHERE task_id=?",
                       (body['reason'], now, body['task_id']))
            db.execute("UPDATE cplus_wn_claims SET state='HELD',updated_at=? WHERE task_id=? AND state='ACTIVE'", (now, body['task_id']))
            db.execute('INSERT INTO cplus_wn_holds VALUES (?,?,?,?,?,?)',
                       (body['task_id'], body['reason'], digest, intended_hash, _canonical(body['intended']), now))
            return self._view(self._row(db, body['task_id']), durable_ack=True, release_actor_fence=False, hold_hash=digest)

    def repair(self, body):
        """Operator-initiated persist-only repair. The Forge side has just committed the C1 journal receipt for this attempt
        under its strict rules (same avatar incarnation, identical inventory structure, REPLAY checked first). No new
        evidence is accepted: the terminal is the intended receipt that HOLD stored, identified by its sha256."""
        _exact(body, _IDENT + ('requester_id', 'intended_sha256'), 'repair')
        for key in _IDENT + ('requester_id',):
            _uuid(body[key], key)
        _hex64(body['intended_sha256'], 'intended_sha256')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            self._ident(row, body)
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester')
            hold = db.execute('SELECT reason,intended_hash,intended_json FROM cplus_wn_holds WHERE task_id=?',
                              (body['task_id'],)).fetchone()
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_wn_terminals WHERE task_id=?', (body['task_id'],)).fetchone()
            if done:
                if hold and done[1] == hold[1] == body['intended_sha256']:
                    return self._terminal(db, row)
                raise GraphConflict('terminal already recorded')
            if row['state'] != 'HOLD_IN_DOUBT' or not hold or hold[0] != 'INVENTORY_COMMIT_FAILED' or hold[1] != body['intended_sha256']:
                raise GraphConflict('repair is only possible for a recorded INVENTORY_COMMIT_FAILED hold with that receipt')
            intended = json.loads(hold[2])
            outcome, _ = self._check_receipt(intended, row)
            self._finish(db, row, outcome, hold[1], intended, 'PERSIST_ONLY_REPAIR')
            return self._terminal(db, self._row(db, body['task_id']))

    _RECONCILE = {
        'break_block': {'PERSISTED_STATE_UNCHANGED': 'RECONCILED_PERSISTED_STATE_UNCHANGED',
                        'EFFECT_OBSERVED': 'RECONCILED_EFFECT_OBSERVED'},
        'pickup_item': {'PERSISTED_TRANSFER': 'RECONCILED_PERSISTED_TRANSFER',
                        'PERSISTED_STATE_UNCHANGED': 'RECONCILED_PERSISTED_STATE_UNCHANGED'},
    }

    def reconcile(self, body):
        """Operator-decided release of an unresolved row from OBSERVED persisted state. It never claims the original attempt
        happened or did not happen, and it never creates an inventory receipt."""
        _exact(body, _IDENT + ('requester_id', 'evidence_class', 'observed'), 'reconcile')
        for key in _IDENT + ('requester_id',):
            _uuid(body[key], key)
        if type(body['evidence_class']) is not str or type(body['observed']) is not dict \
                or len(_canonical(body['observed'])) > MAX_EVIDENCE:
            raise GraphConflict('reconcile evidence malformed')
        digest = _digest(body)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, body['task_id'])
            if row is None or any(row[k] != body[k] for k in ('task_id', 'attempt_id', 'world_id', 'actor_id')):
                raise GraphConflict('foreign or stale native-effect identity')
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester: only the requester of the attempt may reconcile it')
            done = db.execute('SELECT outcome,evidence_hash FROM cplus_wn_terminals WHERE task_id=?', (body['task_id'],)).fetchone()
            if done:
                if done[1] == digest:
                    return self._terminal(db, row)
                raise GraphConflict('terminal already recorded; reconcile only releases unresolved rows')
            if not self._bound_session(db, row['world_id'], body['session_id']):
                raise GraphConflict('BINDING_UNVERIFIED: reconcile is refused while the world/database binding is unverified')
            if row['state'] == 'RESERVED':
                self._finish(db, row, 'CANCELLED_NO_EFFECT', digest, body, 'OPERATOR_RECONCILE')
            elif row['state'] in ('EXECUTING', 'HOLD_IN_DOUBT'):
                outcome = self._RECONCILE[row['action_kind']].get(body['evidence_class'])
                if outcome is None:
                    raise GraphConflict('evidence class not allowed for this action')
                self._reconcile_observation(row, body['evidence_class'], body['observed'])
                self._finish(db, row, outcome, digest, body, 'OPERATOR_RECONCILE')
            else:
                raise GraphConflict(f"nothing to reconcile in state {row['state']}")
            return self._terminal(db, self._row(db, body['task_id']))

    @staticmethod
    def _reconcile_observation(row, klass, observed):
        target = json.loads(row['target_json'])
        if row['action_kind'] == 'break_block':
            _exact(observed, ('x', 'y', 'z', 'state_sha256'), 'observed')
            if any(observed[a] != target[a] for a in ('x', 'y', 'z')):
                raise GraphConflict('observation is for a different block')
            _hex64(observed['state_sha256'], 'state_sha256')
            same = observed['state_sha256'] == target['state_sha256']
            if (klass == 'PERSISTED_STATE_UNCHANGED') != same:
                raise GraphConflict('the observed block state does not support that class')
            return
        _exact(observed, ('journal', 'entity'), 'observed')
        if klass == 'PERSISTED_TRANSFER' and (observed['journal'], observed['entity']) != ('PRESENT', 'ABSENT'):
            raise GraphConflict('persisted transfer needs a journal receipt and an absent entity')
        if klass == 'PERSISTED_STATE_UNCHANGED' and (observed['journal'], observed['entity']) != ('ABSENT', 'PRESENT_UNCHANGED'):
            raise GraphConflict('unchanged persisted state needs no receipt and an unchanged entity')

    # ---- status -----------------------------------------------------------------------------------------------------
    def status(self, body):
        """Attempt-specific status: exact identity or a conflict. The actor's latest run is never substituted."""
        _exact(body, _IDENT + ('requester_id',), 'status')
        for key in body:
            _uuid(body[key], key)
        with self.connection() as db:
            row = self._row(db, body['task_id'])
            if row is None:
                active = db.execute("SELECT task_id FROM cplus_wn_runs WHERE world_id=? AND actor_id=? AND state IN "
                                    "('RESERVED','EXECUTING','HOLD_IN_DOUBT') LIMIT 1", (body['world_id'], body['actor_id'])).fetchone()
                return {'schema_version': 1, 'kind': 'native_effect_run', 'state': 'NONE', 'task_id': body['task_id'],
                        'attempt_id': body['attempt_id'], 'world_id': body['world_id'], 'actor_id': body['actor_id'],
                        'session_id': body['session_id'], 'active': False, 'native_mutation': True,
                        'actor_active_task_id': active[0] if active else ''}
            self._ident(row, body)
            if row['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester')
            terminal = db.execute('SELECT outcome,evidence_hash FROM cplus_wn_terminals WHERE task_id=?', (row['task_id'],)).fetchone()
            hold = db.execute('SELECT reason,hold_hash,intended_hash FROM cplus_wn_holds WHERE task_id=?', (row['task_id'],)).fetchone()
            active = db.execute("SELECT task_id FROM cplus_wn_runs WHERE world_id=? AND actor_id=? AND state IN "
                                "('RESERVED','EXECUTING','HOLD_IN_DOUBT') LIMIT 1", (row['world_id'], row['actor_id'])).fetchone()
            return self._view(row, receipt_hash=terminal[1] if terminal else '', terminal_outcome=terminal[0] if terminal else '',
                              hold_reason=hold[0] if hold else '', hold_hash=hold[1] if hold else '',
                              intended_hash=hold[2] if hold else '', actor_active_task_id=active[0] if active else '')

    def active(self, body):
        """Operator discovery for Reconcile: the identity and target of the native attempt that owns this actor, if any.
        Only the requester of that attempt may see it. It is NOT a status (the actor's latest run is never a substitute for an
        exact attempt) and a reply from here never proves a terminal."""
        _exact(body, ('world_id', 'actor_id', 'requester_id'), 'active')
        for key in body:
            _uuid(body[key], key)
        with self.connection() as db:
            row = db.execute("SELECT task_id FROM cplus_wn_runs WHERE world_id=? AND actor_id=? AND state IN "
                             "('RESERVED','EXECUTING','HOLD_IN_DOUBT') LIMIT 1", (body['world_id'], body['actor_id'])).fetchone()
            if row is None:
                return {'schema_version': 1, 'kind': 'native_effect_run', 'state': 'NONE', 'active': False,
                        'world_id': body['world_id'], 'actor_id': body['actor_id'], 'native_mutation': True}
            run = self._row(db, row[0])
            if run['requester_id'] != body['requester_id']:
                raise GraphConflict('foreign requester: only the requester of the attempt may reconcile it')
            return self._view(run, target=json.loads(run['target_json']))

    # ---- terminal ---------------------------------------------------------------------------------------------------
    @staticmethod
    def _finish(db, row, outcome, digest, evidence, detail):
        now = int(time.time())
        db.execute('INSERT INTO cplus_wn_terminals VALUES (?,?,?,?,?)', (row['task_id'], outcome, digest, _canonical(evidence), now))
        db.execute('UPDATE cplus_wn_runs SET state=?,detail=?,updated_at=? WHERE task_id=?', (outcome, detail, now, row['task_id']))
        db.execute("UPDATE cplus_wn_claims SET state='RELEASED',updated_at=? WHERE task_id=?", (now, row['task_id']))

    def _terminal(self, db, row):
        t = db.execute('SELECT evidence_hash FROM cplus_wn_terminals WHERE task_id=?', (row['task_id'],)).fetchone()
        return self._view(row, backend_commit_ack=True, release_actor_fence=True, receipt_hash=t[0] if t else '')
