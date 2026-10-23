"""C-F3/E gated preview: durable FIFO read-only DAG over *server-origin* C-F2 samples.

This is NOT a world mutation engine. Operator-authorized, loopback-only requests
arrive from Forge. It never dispatches or retries Minecraft actions. Pending
work from a crash stays QUEUED/HOLD and only operator calls drive it forward.
"""
from contextlib import contextmanager
from pathlib import Path
import hashlib
import json
import sqlite3
from uuid import UUID, uuid4
from .cplus_f2_observation import validate_v2
from .identity import IdentityError


class GraphConflict(ValueError):
    pass


def _uuid(value):
    if type(value) is not str:
        raise GraphConflict('noncanonical UUID')
    try:
        parsed = UUID(value)
    except ValueError:
        raise GraphConflict('invalid UUID') from None
    if parsed.int == 0 or str(parsed) != value:
        raise GraphConflict('noncanonical UUID')
    return value


class ReadOnlyGraph:
    """Single authoritative SQLite projection, not a second world executor."""
    MAX_QUEUE = 16
    STEPS = ('capture_observation', 'evaluate_permission', 'report_summary')

    def __init__(self, database):
        self.db = Path(database)
        self.db.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as conn:
            conn.executescript('''
                CREATE TABLE IF NOT EXISTS cplus_e_tasks (
                    task_id TEXT PRIMARY KEY, request_id TEXT NOT NULL UNIQUE,
                    world_id TEXT NOT NULL, dimension_id TEXT NOT NULL,
                    actor_id TEXT NOT NULL, session_id TEXT NOT NULL,
                    sequence INTEGER NOT NULL, snapshot_hash TEXT NOT NULL,
                    snapshot_json TEXT NOT NULL, observation_id TEXT NOT NULL,
                    state TEXT NOT NULL CHECK (state IN ('QUEUED','RUNNING','READ_ONLY_VERIFIED','HOLD')),
                    UNIQUE(world_id,actor_id,sequence)
                );
                CREATE TABLE IF NOT EXISTS cplus_e_steps (
                    task_id TEXT NOT NULL REFERENCES cplus_e_tasks(task_id),
                    ordinal INTEGER NOT NULL, kind TEXT NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('WAITING','READ_VERIFIED')),
                    evidence_id TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY(task_id,ordinal)
                );
                CREATE INDEX IF NOT EXISTS cplus_e_fifo ON
                    cplus_e_tasks(world_id,actor_id,sequence);
            ''')

    @contextmanager
    def connection(self):
        conn = sqlite3.connect(self.db, timeout=3.0)
        try:
            conn.execute('PRAGMA busy_timeout=3000')
            conn.execute('PRAGMA foreign_keys=ON')
            conn.execute('PRAGMA journal_mode=WAL')
            conn.execute('PRAGMA synchronous=FULL')
            with conn:
                yield conn
        finally:
            conn.close()

    def submit(self, request_id, snapshot):
        _uuid(request_id)
        try:
            valid = validate_v2(snapshot)
        except (ValueError, TypeError, IdentityError) as err:
            raise GraphConflict('invalid C-F2 observation: ' + type(err).__name__) from None
        canonical = json.dumps(valid,sort_keys=True,separators=(',', ':'),ensure_ascii=False)
        digest = hashlib.sha256(canonical.encode('utf-8')).hexdigest()
        world,actor = valid['world_id'],valid['actor_id']
        with self.connection() as conn:
            conn.execute('BEGIN IMMEDIATE')
            old = conn.execute('SELECT task_id,snapshot_hash,world_id,actor_id FROM cplus_e_tasks WHERE request_id=?',
                               (request_id,)).fetchone()
            if old:
                if old[1:] != (digest,world,actor):
                    raise GraphConflict('conflicting request_id reuse')
                return self._status(conn,old[0])
            queued = conn.execute('''SELECT COUNT(*) FROM cplus_e_tasks WHERE world_id=? AND actor_id=?
                                     AND state IN ('QUEUED','RUNNING','HOLD')''',(world,actor)).fetchone()[0]
            if queued >= self.MAX_QUEUE:
                raise GraphConflict('bounded actor queue full')
            last = conn.execute('SELECT MAX(sequence) FROM cplus_e_tasks WHERE world_id=? AND actor_id=?',
                                (world,actor)).fetchone()[0] or 0
            task_id = str(uuid4())
            conn.execute('''INSERT INTO cplus_e_tasks
               (task_id,request_id,world_id,dimension_id,actor_id,session_id,sequence,
                snapshot_hash,snapshot_json,observation_id,state)
                VALUES(?,?,?,?,?,?,?,?,?,?, 'QUEUED')''',
                (task_id,request_id,world,valid['dimension_id'],actor,valid['session_id'],last+1,
                 digest,canonical,valid['observation_id']))
            conn.executemany('INSERT INTO cplus_e_steps(task_id,ordinal,kind,state) VALUES(?,?,?,\'WAITING\')',
                             [(task_id,i,kind) for i,kind in enumerate(self.STEPS)])
            return self._status(conn,task_id)

    def _status(self, conn, task_id):
        row = conn.execute('''SELECT task_id,request_id,world_id,actor_id,sequence,state,observation_id,
                             snapshot_json FROM cplus_e_tasks WHERE task_id=?''',(task_id,)).fetchone()
        if not row:
            raise GraphConflict('unknown task')
        steps = conn.execute('''SELECT kind,state,evidence_id FROM cplus_e_steps
                              WHERE task_id=? ORDER BY ordinal''',(task_id,)).fetchall()
        try:
            snapshot = json.loads(row[7])
            observed = snapshot['coverage']['recorded']
            unknown = snapshot['coverage']['unknown']
            permission = snapshot['permission']['decision']
        except (ValueError, TypeError, KeyError, AttributeError):
            # Read-only diagnostic only: expose HOLD-compatible minimal state;
            # never claim that corrupted stored evidence proves an effect.
            observed, unknown, permission = -1, -1, 'UNKNOWN'
        return {'task_id':row[0],'request_id':row[1],'world_id':row[2],'actor_id':row[3],
                'sequence':row[4],'state':row[5],'observation_id':row[6],
                'steps':[{'kind':k,'state':s,'evidence_id':e} for k,s,e in steps],
                'observed':observed,
                'unknown':unknown,
                'permission':permission,
                'world_effects':False,'can_replay_world_actions':False}

    def status(self,task_id):
        _uuid(task_id)
        with self.connection() as conn:
            return self._status(conn,task_id)

    def advance(self,task_id):
        """Operator-driven, no world calls. On restart stale session is never auto-resumed.

        A QUEUED immutable *snapshot* can be verified. For a live executor this
        method deliberately cannot grant a mutation/real-time permission.
        """
        _uuid(task_id)
        with self.connection() as conn:
            conn.execute('BEGIN IMMEDIATE')
            state = self._status(conn,task_id)
            if state['state'] in ('READ_ONLY_VERIFIED','HOLD'):
                return state
            preceding = conn.execute('''SELECT task_id FROM cplus_e_tasks WHERE world_id=? AND actor_id=?
                 AND sequence<? AND state IN ('QUEUED','RUNNING','HOLD') LIMIT 1''',
                 (state['world_id'],state['actor_id'],state['sequence'])).fetchone()
            if preceding:
                return state
            row=conn.execute('SELECT snapshot_json,snapshot_hash FROM cplus_e_tasks WHERE task_id=?',
                             (task_id,)).fetchone()
            if hashlib.sha256(row[0].encode('utf-8')).hexdigest()!=row[1]:
                conn.execute('UPDATE cplus_e_tasks SET state=\'HOLD\' WHERE task_id=?',(task_id,))
                return self._status(conn,task_id)
            snapshot = json.loads(row[0])
            try:
                validate_v2(snapshot)
            except (ValueError,TypeError,IdentityError):
                conn.execute('UPDATE cplus_e_tasks SET state=\'HOLD\' WHERE task_id=?',(task_id,))
                return self._status(conn,task_id)
            conn.execute('UPDATE cplus_e_tasks SET state=\'RUNNING\' WHERE task_id=?',(task_id,))
            # DAG edges 0 -> 1 -> 2; each step requires previously verified step.
            for i,kind in enumerate(self.STEPS):
                if i:
                    predecessor = conn.execute('SELECT state FROM cplus_e_steps WHERE task_id=? AND ordinal=?',
                                               (task_id,i-1)).fetchone()[0]
                    if predecessor!='READ_VERIFIED':
                        raise GraphConflict('DAG dependency not verified')
                if kind=='evaluate_permission' and snapshot['permission']['decision']=='ALLOW':
                    # Read-only preview must not issue an action permit even if
                    # some other future policy starts returning ALLOW.
                    pass
                evidence=f"{snapshot['observation_id']}:{i}"
                conn.execute('''UPDATE cplus_e_steps SET state='READ_VERIFIED',evidence_id=?
                                WHERE task_id=? AND ordinal=?''',(evidence,task_id,i))
            conn.execute("UPDATE cplus_e_tasks SET state='READ_ONLY_VERIFIED' WHERE task_id=?",(task_id,))
            return self._status(conn,task_id)
