"""M04 D-Lite durable *metadata* boundary, disabled for gameplay dispatch.

No network route imports or calls this store, and no Forge-world effects are
executable here. A future authenticated Forge coordinator must prove each
witness using real server observations. Holding a SQLite claim is NOT an
authorization to mutate a block, equip an item or collect a drop.

Safety properties: additive tables, BEGIN IMMEDIATE claims, exact idempotency,
no old G2 HOLD bypass, irreversible-intent first, HOLD is persistent/fenced,
no effect retry, no automatic restart recovery, no deleting history.
"""
from __future__ import annotations

import hashlib
from contextlib import contextmanager
import json
import sqlite3
import time
from pathlib import Path

from .cplus_m04_contracts import STEPS, EFFECTS, ContractError, parse_identity

_EFFECT_STEPS = frozenset({"BREAK", "PICKUP"})
_BUSY_G2 = ("RESERVED", "C2_ACCEPTED", "HOLD", "CANCEL_PENDING")
_TERMINAL = ("COMPLETED", "CANCELLED")


def _canonical(data: object) -> str:
    return json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False)


def _digest(data: object) -> str:
    return hashlib.sha256(_canonical(data).encode()).hexdigest()


class M04Blocked(RuntimeError):
    """Admission is unsafe; never clear another actor's records to continue."""


class M04Store:
    """Explicitly constructed store; never mounted by server.py automatically."""

    def __init__(self, path: str | Path):
        self.path = str(path)

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        try:
            db.execute("PRAGMA busy_timeout=5000")
            db.execute("PRAGMA synchronous=FULL")
            db.execute("PRAGMA foreign_keys=ON")
            with db:
                yield db
        finally:
            db.close()

    def initialize(self):
        """Additive schema only; test on DB copies before use on a real DB."""
        with self._db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS cplus_m04_runs (
                    task_id TEXT PRIMARY KEY,
                    request_id TEXT UNIQUE NOT NULL,
                    attempt_id TEXT UNIQUE NOT NULL,
                    world_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    requester_id TEXT NOT NULL,
                    dimension_id TEXT NOT NULL,
                    block_key TEXT NOT NULL,
                    payload_hash TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('RESERVED','ACTIVE','HOLD','COMPLETED','CANCELLED')),
                    effect TEXT NOT NULL CHECK (effect IN ('NONE','VERIFIED','PARTIAL','UNKNOWN','EXTERNALLY_CONSUMED')),
                    next_step INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS cplus_m04_actor_fence
                  ON cplus_m04_runs(world_id, actor_id)
                  WHERE state IN ('RESERVED','ACTIVE','HOLD');
                CREATE UNIQUE INDEX IF NOT EXISTS cplus_m04_target_fence
                  ON cplus_m04_runs(world_id, dimension_id, block_key)
                  WHERE state IN ('RESERVED','ACTIVE','HOLD');
                CREATE TABLE IF NOT EXISTS cplus_m04_steps (
                    task_id TEXT NOT NULL REFERENCES cplus_m04_runs(task_id),
                    ordinal INTEGER NOT NULL,
                    step TEXT NOT NULL,
                    observation_id TEXT NOT NULL,
                    server_tick INTEGER NOT NULL,
                    effect TEXT NOT NULL,
                    evidence_hash TEXT NOT NULL,
                    recorded_at INTEGER NOT NULL,
                    PRIMARY KEY (task_id, ordinal)
                );
                CREATE TABLE IF NOT EXISTS cplus_m04_intents (
                    task_id TEXT NOT NULL REFERENCES cplus_m04_runs(task_id),
                    step TEXT NOT NULL,
                    effect_request_id TEXT NOT NULL UNIQUE,
                    payload_hash TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    PRIMARY KEY (task_id, step)
                );
            """)

    @staticmethod
    def _table(db, name):
        return db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone() is not None

    @staticmethod
    def _row(db, task):
        row = db.execute("SELECT task_id,request_id,attempt_id,world_id,actor_id,session_id,requester_id,"
                         "dimension_id,block_key,payload_hash,state,effect,next_step FROM cplus_m04_runs "
                         "WHERE task_id=?", (task,)).fetchone()
        if row is None:
            return None
        fields = ('task_id','request_id','attempt_id','world_id','actor_id','session_id',
                  'requester_id','dimension_id','block_key','payload_hash','state','effect','next_step')
        return dict(zip(fields, row))

    @staticmethod
    def _guard_legacy(db, world, actor):
        # Existing old G2 HOLD rows are *fences*, not garbage to ignore.
        if not M04Store._table(db, 'cplus_e3_g2_moves'):
            raise M04Blocked('G2 historical state unavailable; fail closed')
        placeholders = ','.join('?' for _ in _BUSY_G2)
        old = db.execute(f"SELECT task_id,state FROM cplus_e3_g2_moves WHERE world_id=? "
                         f"AND actor_id=? AND state IN ({placeholders}) LIMIT 1",
                         (world, actor, *_BUSY_G2)).fetchone()
        if old:
            raise M04Blocked(f'old G2 attempt {old[0]} still {old[1]}; preserve fence')
        if M04Store._table(db, 'cplus_e1_tasks'):
            unresolved = db.execute("SELECT 1 FROM cplus_e1_tasks WHERE world_id=? AND actor_id=? "
                                    "AND state IN ('QUEUED','HOLD','DISPATCHING','CANCEL_PENDING') LIMIT 1",
                                    (world, actor)).fetchone()
            if unresolved:
                raise M04Blocked('unfinished E1 FIFO task')

    def reserve(self, identity: dict, *, request_id: str, dimension_id: str,
                block_key: str, proposed_contract: dict) -> dict:
        """Persist admission claim, but *NOT* a world-mutation permit."""
        from uuid import UUID
        world, actor, session, requester, task, attempt = parse_identity(identity)
        if not all(type(v) is str and v for v in (request_id, dimension_id, block_key)):
            raise ContractError('nonempty request, dimension and target required')
        try:
            if str(UUID(request_id)) != request_id:
                raise ValueError('noncanonical')
        except ValueError as exc:
            raise ContractError('request_id invalid UUID') from exc
        if type(proposed_contract) is not dict:
            raise ContractError('typed contract required')
        digest = _digest({'identity': identity, 'request_id': request_id,
                          'dimension_id': dimension_id, 'block_key': block_key,
                          'contract': proposed_contract})
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            replay = db.execute('SELECT task_id,payload_hash FROM cplus_m04_runs WHERE request_id=?',
                                (request_id,)).fetchone()
            if replay:
                if replay != (task, digest):
                    raise M04Blocked('conflicting request replay')
                return self._row(db, task)
            self._guard_legacy(db, world, actor)
            now = int(time.time())
            try:
                db.execute('''INSERT INTO cplus_m04_runs
                    (task_id,request_id,attempt_id,world_id,actor_id,session_id,requester_id,
                    dimension_id,block_key,payload_hash,state,effect,next_step,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,'RESERVED','NONE',0,?,?)''',
                    (task, request_id, attempt, world, actor, session, requester,
                     dimension_id, block_key, digest, now, now))
            except sqlite3.IntegrityError as exc:
                raise M04Blocked('actor/target/attempt already reserved; no competing world action') from exc
            return self._row(db, task)

    def status(self, task_id: str):
        with self._db() as db:
            return self._row(db, task_id)

    def prepare_effect(self, identity: dict, *, step: str,
                       effect_request_id: str, payload: dict):
        """Durably record intent *before* any external effect can be attempted.

        Returning an intent receipt does not allow gameplay on its own. A real
        Forge executor must independently validate auth, permission, native
        quiescence, loaded world and ownership on its server thread.
        """
        from uuid import UUID
        if step not in _EFFECT_STEPS or type(payload) is not dict:
            raise ContractError('only concrete irreversible effects have intents')
        try:
            if str(UUID(effect_request_id)) != effect_request_id:
                raise ValueError('noncanonical')
        except ValueError as exc:
            raise ContractError('invalid effect request UUID') from exc
        world, actor, session, requester, task, attempt = parse_identity(identity)
        digest = _digest(payload)
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, task)
            self._exact(row, identity)
            previous = db.execute('SELECT effect_request_id,payload_hash FROM cplus_m04_intents '
                                  'WHERE task_id=? AND step=?', (task, step)).fetchone()
            if previous:
                if previous != (effect_request_id, digest):
                    raise M04Blocked('ambiguous duplicate effect intent; no re-execution')
                return {'task_id': task, 'step': step, 'intent_hash': digest, 'replay': True}
            if row['state'] not in ('RESERVED', 'ACTIVE') or STEPS[row['next_step']] != step:
                raise M04Blocked('task not eligible for world-effect intent')
            db.execute('INSERT INTO cplus_m04_intents VALUES (?,?,?,?,?)',
                       (task, step, effect_request_id, digest, int(time.time())))
            return {'task_id': task, 'step': step, 'intent_hash': digest, 'replay': False}

    @staticmethod
    def _exact(row, identity):
        if row is None or tuple(row[key] for key in ('world_id','actor_id','session_id','requester_id',
                                                      'task_id','attempt_id')) != parse_identity(identity):
            raise M04Blocked('foreign/stale task identity')

    def record_step(self, identity: dict, *, step: str, observation_id: str,
                    server_tick: int, evidence_hash: str, effect: str) -> dict:
        """Record evidence metadata, never execute/retry the effect itself."""
        from uuid import UUID
        if step not in STEPS or effect not in EFFECTS or type(server_tick) is not int or server_tick < 0:
            raise ContractError('invalid witness step/tick/effect')
        try:
            if str(UUID(observation_id)) != observation_id:
                raise ValueError('noncanonical')
        except ValueError as exc:
            raise ContractError('invalid observation id') from exc
        if type(evidence_hash) is not str or len(evidence_hash) != 64 or any(c not in '0123456789abcdef' for c in evidence_hash):
            raise ContractError('invalid evidence sha256')
        witness = (step, observation_id, server_tick, evidence_hash, effect)
        world, actor, session, requester, task, attempt = parse_identity(identity)
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, task)
            self._exact(row, identity)
            existing = db.execute('SELECT step,observation_id,server_tick,evidence_hash,effect '
                                  'FROM cplus_m04_steps WHERE task_id=? AND ordinal=?',
                                  (task, STEPS.index(step))).fetchone()
            if existing:
                if existing != witness:
                    raise M04Blocked('conflicting witness replay; retained as-is')
                return self._row(db, task)
            if row['state'] not in ('RESERVED','ACTIVE') or row['next_step'] >= len(STEPS) or STEPS[row['next_step']] != step:
                raise M04Blocked('missing predecessor, stale or fenced task')
            if step in _EFFECT_STEPS and not db.execute('SELECT 1 FROM cplus_m04_intents WHERE task_id=? AND step=?',
                                                         (task, step)).fetchone():
                raise M04Blocked('effect intent was not durable before action')
            # Negative/unknown outcomes remain as evidence, but retain the fence.
            uncertain = effect in ('UNKNOWN', 'PARTIAL', 'EXTERNALLY_CONSUMED')
            if step in ('VERIFY_BLOCK', 'PICKUP','VERIFY_INVENTORY','TERMINAL') and effect != 'VERIFIED':
                uncertain = True
            now = int(time.time())
            db.execute('INSERT INTO cplus_m04_steps VALUES (?,?,?,?,?,?,?,?)',
                       (task, row['next_step'], step, observation_id, server_tick, effect, evidence_hash, now))
            state = 'HOLD' if uncertain else ('COMPLETED' if step == 'TERMINAL' else 'ACTIVE')
            db.execute('UPDATE cplus_m04_runs SET state=?,effect=?,next_step=?,updated_at=? WHERE task_id=?',
                       (state, effect if uncertain else ('VERIFIED' if state == 'COMPLETED' else row['effect']),
                        row['next_step'] + 1, now, task))
            return self._row(db, task)

    def hold(self, identity: dict, *, reason_effect: str = 'UNKNOWN') -> dict:
        if reason_effect not in EFFECTS:
            raise ContractError('invalid held effect')
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._row(db, identity['task_id'])
            self._exact(row, identity)
            if row['state'] == 'COMPLETED':
                raise M04Blocked('terminal immutable')
            db.execute("UPDATE cplus_m04_runs SET state='HOLD',effect=?,updated_at=? WHERE task_id=?",
                       (reason_effect, int(time.time()), identity['task_id']))
            return self._row(db, identity['task_id'])

    def metadata_retry(self, identity: dict) -> dict:
        """Read-only exact result. NEVER retries mining, pickup or equipment."""
        row = self.status(identity['task_id'])
        self._exact(row, identity)
        return row
