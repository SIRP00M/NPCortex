"""C-F3 E1/E2: opt-in authenticated read-only task coordinator.

Separate append-only schema from E-P0 preview and C1/C-F1 ledgers.
The backend has no authority to drive C1/C2 or grant a world-effect permit.
Only an operator-originated Forge snapshot may be submitted to these endpoints.
"""
import hashlib
import json
import re
import sqlite3
import time
from uuid import uuid4

from .cplus_e_graph import ReadOnlyGraph, GraphConflict, _uuid
from .cplus_f2_observation import validate_v2, evidence_fresh
from .identity import IdentityError

_NODE = re.compile(r'^[a-z][a-z0-9_]{0,31}$')
_SUPPORTED = {'observe_cells', 'report_coverage', 'report_permission'}
_STATES = {'QUEUED', 'HOLD', 'DISPATCHING', 'CANCEL_PENDING', 'CANCELLED', 'READ_ONLY_VERIFIED'}


def _canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def _validate_plan(plan):
    if type(plan) is not list or not 1 <= len(plan) <= 8:
        raise GraphConflict('invalid bounded task graph')
    seen = set()
    normalized = []
    for raw in plan:
        if not isinstance(raw, dict) or set(raw) != {'node_id', 'kind', 'depends_on'}:
            raise GraphConflict('invalid typed graph node')
        nid, kind, deps = raw['node_id'], raw['kind'], raw['depends_on']
        if not isinstance(nid, str) or not _NODE.fullmatch(nid) or nid in seen:
            raise GraphConflict('duplicate or invalid node_id')
        if type(kind) is not str or kind not in _SUPPORTED:
            raise GraphConflict('unsupported read-only node kind')
        if (type(deps) is not list or len(deps) > 8
                or any(type(d) is not str for d in deps)
                or len(deps) != len(set(deps))):
            raise GraphConflict('invalid dependencies')
        if any(type(d) is not str or d not in seen for d in deps):
            # All edges must point backward: canonical topological order, no cycles.
            raise GraphConflict('missing, forward or cyclic graph dependency')
        seen.add(nid)
        normalized.append({'node_id': nid, 'kind': kind, 'depends_on': deps[:]})
    return normalized


class ExtendedCoordinator(ReadOnlyGraph):
    MAX_QUEUE = 16
    PLAN = [
        {'node_id': 'observe', 'kind': 'observe_cells', 'depends_on': []},
        {'node_id': 'coverage', 'kind': 'report_coverage', 'depends_on': ['observe']},
        {'node_id': 'permission', 'kind': 'report_permission', 'depends_on': ['observe']},
    ]

    def __init__(self, database):
        super().__init__(database)
        # No migration of E-P0 or other existing tables; explicit fresh names.
        with self.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS cplus_e1_tasks (
                    task_id TEXT PRIMARY KEY,
                    request_id TEXT NOT NULL UNIQUE,
                    requester_id TEXT NOT NULL,
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL, session_id TEXT NOT NULL,
                    observation_id TEXT NOT NULL,
                    sequence INTEGER NOT NULL, payload_hash TEXT NOT NULL,
                    snapshot_json TEXT NOT NULL,
                    graph_json TEXT NOT NULL,
                    state TEXT NOT NULL CHECK(state IN
                    ('QUEUED','HOLD','DISPATCHING','CANCEL_PENDING','CANCELLED','READ_ONLY_VERIFIED')),
                    lease_id TEXT NOT NULL DEFAULT '',
                    generation INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(world_id, actor_id, sequence)
                );
                CREATE TABLE IF NOT EXISTS cplus_e1_nodes (
                    task_id TEXT NOT NULL REFERENCES cplus_e1_tasks(task_id),
                    node_id TEXT NOT NULL, kind TEXT NOT NULL, depends_json TEXT NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('WAITING','READ_VERIFIED')),
                    evidence_id TEXT NOT NULL DEFAULT '', attempt_id TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (task_id,node_id)
                );
                CREATE INDEX IF NOT EXISTS cplus_e1_fifo
                    ON cplus_e1_tasks(world_id, actor_id, sequence);
            ''')

    def _task(self, db, task_id):
        row = db.execute('''SELECT task_id,request_id,requester_id,world_id,dimension_id,actor_id,
                       session_id,observation_id,sequence,state,lease_id,generation,graph_json,
                       snapshot_json,payload_hash
                       FROM cplus_e1_tasks WHERE task_id=?''', (task_id,)).fetchone()
        if not row:
            raise GraphConflict('unknown E1 task')
        nodes = db.execute('''SELECT node_id,kind,depends_json,state,evidence_id,attempt_id
                 FROM cplus_e1_nodes WHERE task_id=? ORDER BY rowid''', (task_id,)).fetchall()
        # Stored projection integrity is rechecked before publishing status.
        try:
            snapshot=json.loads(row[13]); plan=_validate_plan(json.loads(row[12]))
            validate_v2(snapshot)
            digest=hashlib.sha256(_canonical({'requester_id':row[2],
                   'snapshot':snapshot,'plan':plan}).encode('utf-8')).hexdigest()
            if digest != row[14] or len(plan)!=len(nodes) or any(
                (p['node_id'],p['kind'],p['depends_on']) !=
                (n[0],n[1],json.loads(n[2])) for p,n in zip(plan,nodes)):
                raise GraphConflict('persisted task projection mismatch')
            if (snapshot['world_id'],snapshot['dimension_id'],snapshot['actor_id'],
                snapshot['session_id'],snapshot['observation_id']) != (
                row[3],row[4],row[5],row[6],row[7]):
                raise GraphConflict('persisted task identity mismatch')
        except (GraphConflict, ValueError, KeyError, TypeError, IdentityError):
            db.execute("UPDATE cplus_e1_tasks SET state='HOLD' WHERE task_id=?",(task_id,))
            row=list(row);row[9]='HOLD'
            nodes=[]  # Forensic state; never synthesize missing steps.
        return {'task_id':row[0], 'request_id':row[1], 'requester_id':row[2],
                'world_id':row[3], 'dimension_id':row[4], 'actor_id':row[5],
                'session_id':row[6], 'observation_id':row[7], 'sequence':row[8],
                'state':row[9], 'lease_id':row[10], 'generation':row[11],
                'nodes':[{'node_id':n[0], 'kind':n[1], 'depends_on':json.loads(n[2]),
                          'state':n[3], 'evidence_id':n[4], 'attempt_id':n[5]} for n in nodes],
                'world_effects':False, 'can_replay_world_actions':False,
                'execution_mode':'READ_ONLY'}

    def submit_task(self, request_id, requester_id, snapshot, plan=None):
        _uuid(request_id); _uuid(requester_id)
        plan = _validate_plan(self.PLAN if plan is None else plan)
        try:
            valid = validate_v2(snapshot)
        except (ValueError, TypeError, IdentityError) as exc:
            raise GraphConflict('invalid C-F2 observation: ' + type(exc).__name__) from None
        payload = _canonical({'requester_id':requester_id, 'snapshot':valid, 'plan':plan})
        digest = hashlib.sha256(payload.encode('utf-8')).hexdigest()
        world, actor = valid['world_id'], valid['actor_id']
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            old = db.execute('SELECT task_id,payload_hash FROM cplus_e1_tasks WHERE request_id=?',
                             (request_id,)).fetchone()
            if old:
                if old[1] != digest:
                    raise GraphConflict('conflicting request_id reuse')
                return self._task(db,old[0])
            count = db.execute('''SELECT COUNT(*) FROM cplus_e1_tasks WHERE world_id=? AND actor_id=?
                AND state IN ('QUEUED','HOLD','DISPATCHING','CANCEL_PENDING')''',
                (world,actor)).fetchone()[0]
            if count >= self.MAX_QUEUE:
                raise GraphConflict('bounded actor queue full')
            nextseq = (db.execute('''SELECT MAX(sequence) FROM cplus_e1_tasks
                   WHERE world_id=? AND actor_id=?''',(world,actor)).fetchone()[0] or 0)+1
            task_id=str(uuid4())
            db.execute('''INSERT INTO cplus_e1_tasks(task_id,request_id,requester_id,
                world_id,dimension_id,actor_id,session_id,observation_id,sequence,payload_hash,
                snapshot_json,graph_json,state)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED')''',
                (task_id,request_id,requester_id,world,valid['dimension_id'],actor,
                 valid['session_id'],valid['observation_id'],nextseq,digest,
                 _canonical(valid),_canonical(plan)))
            db.executemany('''INSERT INTO cplus_e1_nodes
                  (task_id,node_id,kind,depends_json,state) VALUES(?,?,?,?,'WAITING')''',
                  [(task_id,n['node_id'],n['kind'],_canonical(n['depends_on'])) for n in plan])
            return self._task(db,task_id)

    def task_status(self, task_id):
        _uuid(task_id)
        with self.connection() as db:
            return self._task(db, task_id)

    def task_queue(self, world_id, actor_id):
        _uuid(world_id); _uuid(actor_id)
        with self.connection() as db:
            ids=db.execute('''SELECT task_id FROM cplus_e1_tasks WHERE world_id=?
              AND actor_id=? AND state IN ('QUEUED','HOLD','DISPATCHING','CANCEL_PENDING')
              ORDER BY sequence ASC LIMIT 16''', (world_id,actor_id)).fetchall()
            return {'world_id':world_id,'actor_id':actor_id,
                    'tasks':[self._task(db,t[0]) for t in ids],
                    'world_effects':False}

    def cancel_task(self, task_id, requester_id):
        _uuid(task_id); _uuid(requester_id)
        # Caller identity was extracted by Forge from its operator player.
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            task=self._task(db,task_id)
            if task['requester_id']!=requester_id:
                raise GraphConflict('requester mismatch')
            if task['state'] in ('QUEUED','HOLD'):
                db.execute("UPDATE cplus_e1_tasks SET state='CANCELLED' WHERE task_id=?", (task_id,))
            elif task['state']=='DISPATCHING':
                db.execute("UPDATE cplus_e1_tasks SET state='CANCEL_PENDING' WHERE task_id=?", (task_id,))
            return self._task(db,task_id)
