"""E3-D G2: operator-requested, one-target C2 handoff with durable attempt fencing.

No autonomous dequeue, world block actions, path planner or completion claims.
The Forge server alone starts C2. An acknowledged C2 goal is NOT verified arrival.
After process restart all unresolved attempts HOLD; never auto-replay motion.
"""
import hashlib
import json
import sqlite3
import time

from .cplus_e3_preview import MovePreviewStore, validate_move_preview, _canonical
from .cplus_e_graph import GraphConflict, _uuid
from .cplus_e_coordinator import ExtendedCoordinator

ACTIVE = ('RESERVED', 'C2_ACCEPTED', 'CANCEL_PENDING', 'HOLD')

class GuardedMotionStore(MovePreviewStore):
    def __init__(self, database):
        super().__init__(database)
        # E3 may be the first route invoked on a clean world. E1 schema
        # must exist before applying the cross-queue head guard.
        ExtendedCoordinator(database)
        with self.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS cplus_e3_g2_moves (
                    task_id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL,
                    attempt_id TEXT UNIQUE NOT NULL, requester_id TEXT NOT NULL,
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL,
                    session_id TEXT NOT NULL, actor_id TEXT NOT NULL,
                    observation_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
                    target_json TEXT NOT NULL, tolerance REAL NOT NULL,
                    goal_id TEXT NOT NULL DEFAULT '',
                    state TEXT NOT NULL CHECK(state IN ('RESERVED','C2_ACCEPTED','HOLD','CANCEL_PENDING','COMPLETED','CANCELLED')),
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                );
                CREATE UNIQUE INDEX IF NOT EXISTS cplus_e3_g2_active_actor
                  ON cplus_e3_g2_moves(world_id,actor_id)
                  WHERE state IN ('RESERVED','C2_ACCEPTED','HOLD','CANCEL_PENDING');
            ''')
            # G3 schema upgrade: preserve every G2 row and its original identity.
            # SQLite CHECK constraints cannot be extended with ALTER COLUMN.
            # Run one atomic, startup-only copy when upgrading an old G2 database.
            schema=db.execute("SELECT sql FROM sqlite_master WHERE type='table' "
                              "AND name='cplus_e3_g2_moves'").fetchone()[0]
            if 'CANCEL_PENDING' not in schema:
                db.execute('BEGIN IMMEDIATE')
                db.execute('''CREATE TABLE cplus_e3_g2_moves_upgrade (
                    task_id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL,
                    attempt_id TEXT UNIQUE NOT NULL, requester_id TEXT NOT NULL,
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL,
                    session_id TEXT NOT NULL, actor_id TEXT NOT NULL,
                    observation_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
                    target_json TEXT NOT NULL, tolerance REAL NOT NULL,
                    goal_id TEXT NOT NULL DEFAULT '',
                    state TEXT NOT NULL CHECK(state IN
                      ('RESERVED','C2_ACCEPTED','HOLD','CANCEL_PENDING','COMPLETED','CANCELLED')),
                    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
                )''')
                db.execute('INSERT INTO cplus_e3_g2_moves_upgrade SELECT * FROM cplus_e3_g2_moves')
                db.execute('DROP TABLE cplus_e3_g2_moves')
                db.execute('ALTER TABLE cplus_e3_g2_moves_upgrade RENAME TO cplus_e3_g2_moves')
                db.execute('''CREATE UNIQUE INDEX cplus_e3_g2_active_actor
                    ON cplus_e3_g2_moves(world_id, actor_id)
                    WHERE state IN ('RESERVED','C2_ACCEPTED','HOLD','CANCEL_PENDING')''')
                db.commit()
            db.execute('''CREATE TABLE IF NOT EXISTS cplus_e3_g3_terminals (
                task_id TEXT PRIMARY KEY, outcome TEXT NOT NULL,
                evidence_hash TEXT NOT NULL, evidence_json TEXT NOT NULL,
                finalized_at INTEGER NOT NULL)''')
            db.execute('''CREATE TABLE IF NOT EXISTS cplus_e3_g3_cancel_intents (
                task_id TEXT PRIMARY KEY, goal_id TEXT NOT NULL,
                requested_at INTEGER NOT NULL)''')
            # Crash/restart cannot resume C2. Preserve unresolved rows as HOLD;
            # do not infer completion/cancellation from a past journal text row.
            db.execute("UPDATE cplus_e3_g2_moves SET state='HOLD',updated_at=? "
                       "WHERE state IN ('RESERVED','C2_ACCEPTED','CANCEL_PENDING')", (int(time.time()),))

    @staticmethod
    def _result(row):
        return {'schema_version':1, 'task_id':row[0],'request_id':row[1],
                'attempt_id':row[2],'requester_id':row[3],
                'world_id':row[4],'dimension_id':row[5],
                'session_id':row[6],'actor_id':row[7],
                'observation_id':row[8], 'target':json.loads(row[9]),
                'tolerance':row[10], 'goal_id':row[11] or None,
                'state':row[12], 'action_kind':'move_to', 'node_id':'move',
                'execution_mode':'GUARDED_C2', 'world_effects':False,
                'can_execute':row[12]=='RESERVED',
                'c2_goal_accepted':row[12]=='C2_ACCEPTED',
                'motion_started':False,
                'completion_verified':row[12]=='COMPLETED'}

    @staticmethod
    def _row(db, task_id):
        return db.execute('''SELECT task_id,request_id,attempt_id,requester_id,world_id,
           dimension_id,session_id,actor_id,observation_id,target_json,tolerance,
           goal_id,state FROM cplus_e3_g2_moves WHERE task_id=?''', (task_id,)).fetchone()

    def reserve(self, payload):
        obs=validate_move_preview(payload)
        digest=hashlib.sha256(_canonical(payload).encode('utf-8')).hexdigest()
        world,actor=payload['world_id'],payload['actor_id']
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            old=db.execute('SELECT task_id,payload_hash FROM cplus_e3_g2_moves WHERE request_id=?',
                           (payload['request_id'],)).fetchone()
            if old:
                if old!=(payload['task_id'],digest):
                    raise GraphConflict('conflicting G2 request replay')
                return self._result(self._row(db,old[0]))
            # G1 preview cannot grant an execution permit. This new scoped
            # operator request is the ONLY way to reserve an actionable move.
            pending=db.execute('''SELECT 1 FROM cplus_e1_tasks WHERE world_id=? AND actor_id=?
                    AND state IN ('QUEUED','HOLD','DISPATCHING','CANCEL_PENDING') LIMIT 1''',
                    (world,actor)).fetchone()
            if pending:
                raise GraphConflict('E1 FIFO has unfinished work; reconcile before movement')
            e2_table=db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='cplus_e2_runs'").fetchone()
            busy=(db.execute('SELECT 1 FROM cplus_e2_runs WHERE actor_id=? AND state=? LIMIT 1',
                            (actor,'DISPATCHING')).fetchone() if e2_table else None)
            if busy:
                raise GraphConflict('read-only resource lease still in flight')
            now=int(time.time())
            try:
                db.execute('''INSERT INTO cplus_e3_g2_moves
                  (task_id,request_id,attempt_id,requester_id,world_id,dimension_id,
                   session_id,actor_id,observation_id,payload_hash,target_json,tolerance,
                   state,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'RESERVED',?,?)''',
                  (payload['task_id'],payload['request_id'],payload['attempt_id'],
                   payload['requester_id'],world,payload['dimension_id'],payload['session_id'],actor,
                   obs['observation_id'],digest,_canonical(payload['target']),
                   payload['tolerance'],now,now))
            except sqlite3.IntegrityError:
                raise GraphConflict('actor already has unresolved G2 motion; no replay') from None
            return self._result(self._row(db,payload['task_id']))

    def report(self, body):
        if not isinstance(body,dict) or set(body)!={'task_id','attempt_id','world_id','actor_id',
                                                     'session_id','state','goal_id'}:
            raise GraphConflict('invalid typed G2 result acknowledgement')
        for key in ('task_id','attempt_id','world_id','actor_id','session_id'):
            _uuid(body[key])
        if body['state'] not in ('C2_ACCEPTED','HOLD'):
            raise GraphConflict('G2 cannot claim completion')
        if body['state']=='C2_ACCEPTED':
            _uuid(body['goal_id'])
        elif body['goal_id'] is not None:
            raise GraphConflict('HOLD must not invent goal proof')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row=self._row(db,body['task_id'])
            if not row or (row[2],row[4],row[7],row[6]) != (
                    body['attempt_id'],body['world_id'],body['actor_id'],body['session_id']):
                raise GraphConflict('G2 result identity mismatch')
            if row[12]=='RESERVED':
                db.execute('UPDATE cplus_e3_g2_moves SET state=?,goal_id=?,updated_at=? WHERE task_id=?',
                           (body['state'],body['goal_id'] or '',int(time.time()),body['task_id']))
            elif row[12]!=body['state'] or row[11]!=(body['goal_id'] or ''):
                raise GraphConflict('stale/mismatched G2 result, no overwrite')
            return self._result(self._row(db,body['task_id']))

    def status(self, world_id,actor_id):
        _uuid(world_id);_uuid(actor_id)
        with self.connection() as db:
            row=db.execute('''SELECT task_id FROM cplus_e3_g2_moves WHERE world_id=? AND actor_id=?
                       ORDER BY created_at DESC, rowid DESC LIMIT 1''', (world_id,actor_id)).fetchone()
            return (self._result(self._row(db,row[0])) if row else
                    {'world_id':world_id,'actor_id':actor_id,'world_effects':False,
                     'state':'NONE','execution_mode':'GUARDED_C2',
                     # No attempt means no durable terminal evidence. Never
                     # infer COMPLETED or release a movement fence from NONE.
                     'completion_verified':False})

    # G3-2/3: only authenticated local Forge may submit live terminal evidence.
    # No Java/Minecraft operation, automatic replay, or world mutation in backend.
    def finalize(self, candidate):
        from .cplus_e3_g3_terminal import validate_terminal_candidate
        if not isinstance(candidate,dict):
            raise GraphConflict('G3 terminal candidate object required')
        task=candidate.get('task_id')
        _uuid(task)
        canonical=_canonical(candidate)
        digest=hashlib.sha256(canonical.encode('utf-8')).hexdigest()
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row=self._row(db,task)
            if not row:
                raise GraphConflict('G3 unknown movement attempt')
            done=db.execute('SELECT outcome,evidence_hash FROM cplus_e3_g3_terminals WHERE task_id=?',
                            (task,)).fetchone()
            if row[12]=='COMPLETED' and done==('COMPLETED',digest):
                return self._terminal_result(self._result(row),'COMPLETED')
            if done or row[12] not in ('C2_ACCEPTED','CANCEL_PENDING'):
                raise GraphConflict('G3 attempt not eligible; old HOLD must not be finalized')
            admitted=self._result(row)
            validate_terminal_candidate(candidate,admitted)
            now=int(time.time())
            db.execute('''INSERT INTO cplus_e3_g3_terminals
                (task_id,outcome,evidence_hash,evidence_json,finalized_at)
                VALUES (?,?,?,?,?)''',(task,'COMPLETED',digest,canonical,now))
            db.execute("UPDATE cplus_e3_g2_moves SET state='COMPLETED',updated_at=? WHERE task_id=? AND state IN ('C2_ACCEPTED','CANCEL_PENDING')",
                       (now,task))
            return self._terminal_result(self._result(self._row(db,task)),'COMPLETED')

    @staticmethod
    def _terminal_result(row,outcome):
        return {'schema_version':1, 'task_id':row['task_id'],
                'attempt_id':row['attempt_id'], 'goal_id':row['goal_id'],
                'world_id':row['world_id'], 'actor_id':row['actor_id'],
                'session_id':row['session_id'], 'state':outcome,
                'durable_ack':True, 'release_actor_fence':True,
                'completion_verified':outcome=='COMPLETED',
                'world_effects':False}

    def cancel_begin(self, body):
        required={'task_id','attempt_id','requester_id','world_id','actor_id','session_id','goal_id'}
        if type(body) is not dict or set(body)!=required:
            raise GraphConflict('G3 cancel identity shape')
        for key in required: _uuid(body[key])
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row=self._row(db,body['task_id'])
            if not row or any(self._result(row).get(key)!=body[key] for key in required):
                raise GraphConflict('G3 cancel identity mismatch')
            if row[12] not in ('C2_ACCEPTED','CANCEL_PENDING'):
                raise GraphConflict('G3 cancel requires live accepted attempt; no cancel of HOLD/completed')
            if row[12]=='C2_ACCEPTED':
                db.execute("UPDATE cplus_e3_g2_moves SET state='CANCEL_PENDING',updated_at=? WHERE task_id=?",
                           (int(time.time()),body['task_id']))
                db.execute('INSERT INTO cplus_e3_g3_cancel_intents VALUES (?,?,?)',
                           (body['task_id'],body['goal_id'],int(time.time())))
            return {**self._result(self._row(db,body['task_id'])),
                    'durable_ack':False,'release_actor_fence':False}

    def cancel_ack(self, body):
        required={'task_id','attempt_id','requester_id','world_id','actor_id','session_id',
                  'goal_id','action_id','c2_state','c1_stopped'}
        if type(body) is not dict or set(body)!=required:
            raise GraphConflict('G3 cancel ACK shape')
        for key in required-{'action_id','c2_state','c1_stopped'}: _uuid(body[key])
        if body['action_id'] is not None: _uuid(body['action_id'])
        if body['c2_state']!='CANCELLED' or body['c1_stopped'] is not True:
            raise GraphConflict('G3 cancellation not terminal and stopped')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row=self._row(db,body['task_id'])
            if not row or any(self._result(row).get(key)!=body[key] for key in
                              ('task_id','attempt_id','requester_id','world_id','actor_id','session_id','goal_id')):
                raise GraphConflict('G3 cancel ACK identity mismatch')
            digest=hashlib.sha256(_canonical(body).encode('utf-8')).hexdigest()
            old=db.execute('SELECT outcome,evidence_hash FROM cplus_e3_g3_terminals WHERE task_id=?',
                           (body['task_id'],)).fetchone()
            if row[12]=='CANCELLED' and old==('CANCELLED',digest):
                return self._terminal_result(self._result(row),'CANCELLED')
            if row[12]!='CANCEL_PENDING' or old:
                raise GraphConflict('G3 cancel ACK not in CANCEL_PENDING; no fence release')
            intent=db.execute('SELECT goal_id FROM cplus_e3_g3_cancel_intents WHERE task_id=?',
                              (body['task_id'],)).fetchone()
            if not intent or intent[0]!=body['goal_id']:
                raise GraphConflict('G3 missing durable cancellation intent')
            now=int(time.time())
            db.execute('INSERT INTO cplus_e3_g3_terminals VALUES (?,?,?,?,?)',
                       (body['task_id'],'CANCELLED',digest,_canonical(body),now))
            db.execute("UPDATE cplus_e3_g2_moves SET state='CANCELLED',updated_at=? WHERE task_id=? AND state='CANCEL_PENDING'",
                       (now,body['task_id']))
            return self._terminal_result(self._result(self._row(db,body['task_id'])),'CANCELLED')

    def abandon(self, body):
        """Authenticated Forge proof: reserve response never reached C2 handoff.

        The caller must have a durable no-handoff record. Unknown handoff stays
        fenced. This endpoint cannot cancel any admitted C2 goal.
        """
        required={'task_id','attempt_id','world_id','actor_id','session_id','requester_id','no_handoff'}
        if type(body) is not dict or set(body)!=required or body['no_handoff'] is not True:
            raise GraphConflict('durable no-handoff proof required')
        for key in required-{'no_handoff'}: _uuid(body[key])
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row=self._row(db,body['task_id'])
            if not row or any(self._result(row).get(k)!=body[k] for k in required-{'no_handoff'}):
                raise GraphConflict('abandon identity mismatch')
            if row[11] or row[12] not in ('RESERVED','HOLD','CANCELLED'):
                raise GraphConflict('handoff possible; cannot abandon')
            digest=hashlib.sha256(_canonical(body).encode()).hexdigest()
            old=db.execute('SELECT outcome,evidence_hash FROM cplus_e3_g3_terminals WHERE task_id=?',
                           (body['task_id'],)).fetchone()
            if old:
                if old!=('CANCELLED',digest):raise GraphConflict('different terminal evidence')
            else:
                now=int(time.time())
                db.execute('INSERT INTO cplus_e3_g3_terminals VALUES (?,?,?,?,?)',
                           (body['task_id'],'CANCELLED',digest,_canonical(body),now))
                db.execute("UPDATE cplus_e3_g2_moves SET state='CANCELLED',updated_at=? WHERE task_id=?",
                           (now,body['task_id']))
            return self._terminal_result(self._result(self._row(db,body['task_id'])),'CANCELLED')
