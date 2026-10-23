"""C-F3 E1-E Gate 2: explicitly driven, server-snapshot-only read execution.

No Forge C1/C2 controller, no block/inventory writes, no AI dispatch and no
automatic restart replay. Leases coordinate *read-only work* in SQLite; they
are NOT Forge claim/permission locks or world-action receipts.
"""
import hashlib
import json
import sqlite3
import time
from uuid import uuid4

from .cplus_e_coordinator import ExtendedCoordinator, GraphConflict, _canonical
from .cplus_e_graph import _uuid
from .cplus_f2_observation import validate_v2
from .identity import IdentityError


class ReadOnlyDispatcher(ExtendedCoordinator):
    LEASE_SECONDS = 600  # Operator can perform the two in-game steps manually.

    def __init__(self, database):
        super().__init__(database)
        with self.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS cplus_e2_leases (
                    resource_key TEXT PRIMARY KEY, generation INTEGER NOT NULL,
                    task_id TEXT NOT NULL DEFAULT '', lease_id TEXT NOT NULL DEFAULT '',
                    expires_at INTEGER NOT NULL DEFAULT 0
                );
                CREATE TABLE IF NOT EXISTS cplus_e2_runs (
                    task_id TEXT PRIMARY KEY REFERENCES cplus_e1_tasks(task_id),
                    lease_id TEXT NOT NULL, generation INTEGER NOT NULL,
                    resource_key TEXT NOT NULL, actor_id TEXT NOT NULL,
                    session_id TEXT NOT NULL, center_json TEXT NOT NULL,
                    start_tick INTEGER NOT NULL, start_observation_id TEXT NOT NULL,
                    attempt_id TEXT NOT NULL, state TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS cplus_e2_receipts (
                    task_id TEXT NOT NULL, node_id TEXT NOT NULL,
                    attempt_id TEXT NOT NULL, observation_id TEXT NOT NULL,
                    snapshot_hash TEXT NOT NULL, result_json TEXT NOT NULL,
                    PRIMARY KEY(task_id,node_id)
                );
                CREATE TABLE IF NOT EXISTS cplus_e2_cancel_acks (
                    task_id TEXT PRIMARY KEY, lease_id TEXT NOT NULL,
                    generation INTEGER NOT NULL, ack_id TEXT NOT NULL
                );
            ''')
            # New coordinator instance means new process/startup fence. Never
            # resume an in-flight read operation across a process restart.
            db.execute('BEGIN IMMEDIATE')
            db.execute("UPDATE cplus_e1_tasks SET state='HOLD',lease_id='',generation=0 WHERE state IN ('DISPATCHING','CANCEL_PENDING')")
            db.execute("UPDATE cplus_e2_runs SET state='HOLD' WHERE state='DISPATCHING'")
            db.execute("""UPDATE cplus_e2_leases SET task_id='',lease_id='',expires_at=0,
                         generation=generation+1 WHERE task_id!=''""")

    def _task(self, db, task_id):
        task=super()._task(db,task_id)
        if task['state']=='READ_ONLY_VERIFIED':
            run=db.execute('SELECT attempt_id,state FROM cplus_e2_runs WHERE task_id=?',
                           (task_id,)).fetchone()
            receipts=db.execute('SELECT node_id,attempt_id,observation_id,snapshot_hash,result_json FROM cplus_e2_receipts WHERE task_id=?',
                                (task_id,)).fetchall()
            try:
                proof={r[0]:r for r in receipts}
                if (not run or run[1]!='READ_ONLY_VERIFIED' or len(proof)!=len(task['nodes'])):
                    raise ValueError('missing read-only receipt')
                observed_ids=set()
                hashes=set()
                for node in task['nodes']:
                    row=proof[node['node_id']]
                    if (node['state']!='READ_VERIFIED' or node['attempt_id']!=run[0]
                            or row[1]!=run[0] or node['evidence_id']!=row[2]+':'+node['node_id']
                            or len(row[3])!=64 or not isinstance(json.loads(row[4]),dict)):
                        raise ValueError('typed receipt mismatch')
                    observed_ids.add(row[2]);hashes.add(row[3])
                if len(observed_ids)!=1 or len(hashes)!=1:
                    raise ValueError('non-uniform read-only snapshot evidence')
            except (ValueError,KeyError,TypeError,IndexError):
                db.execute("UPDATE cplus_e1_tasks SET state='HOLD' WHERE task_id=?",(task_id,))
                task['state']='HOLD'
        return task

    @staticmethod
    def _snapshot(snapshot):
        try:
            valid = validate_v2(snapshot)
        except (ValueError, TypeError, IdentityError):
            raise GraphConflict('untrusted C-F2 read-only snapshot') from None
        # The read-only dispatcher cannot claim complete evidence from an
        # unloaded/unknown cell; the original E1 admission may still queue it.
        if valid['coverage']['unknown'] or not valid['coverage']['complete']:
            raise GraphConflict('incomplete read-only observation')
        if valid['permission']['decision'] == 'ALLOW':
            # C-F2 observes; it does not issue world mutation permits.
            raise GraphConflict('unexpected mutation permission')
        return valid

    @staticmethod
    def _resource(snapshot):
        p = snapshot['center']
        return _canonical([snapshot['world_id'], snapshot['dimension_id'],
                           'READ_CELL', p[0], p[1], p[2]])

    def _acquire(self, db, key, task_id, now):
        """Atomic fenced single-resource acquisition; never steal live lease."""
        entry = db.execute('''SELECT generation,task_id,expires_at
                             FROM cplus_e2_leases WHERE resource_key=?''', (key,)).fetchone()
        if entry and entry[1]:
            if entry[2] > now:
                raise GraphConflict('READ_CELL resource busy')
            # Stale/expired owner is held for operator inspection; no retry.
            db.execute("UPDATE cplus_e1_tasks SET state='HOLD',lease_id='' WHERE task_id=? AND state='DISPATCHING'", (entry[1],))
            db.execute("UPDATE cplus_e2_runs SET state='HOLD' WHERE task_id=? AND state='DISPATCHING'", (entry[1],))
        generation = (entry[0] if entry else 0)+1
        lease_id = str(uuid4())
        db.execute('''INSERT INTO cplus_e2_leases(resource_key,generation,task_id,lease_id,expires_at)
                      VALUES(?,?,?,?,?) ON CONFLICT(resource_key) DO UPDATE SET
                      generation=excluded.generation,task_id=excluded.task_id,
                      lease_id=excluded.lease_id,expires_at=excluded.expires_at''',
                   (key,generation,task_id,lease_id,now+self.LEASE_SECONDS))
        return lease_id,generation

    @staticmethod
    def _release(db, key, task_id, lease_id, generation):
        # Fencing: stale releases cannot unlock a newer owner's lease.
        changed=db.execute('''UPDATE cplus_e2_leases SET task_id='',lease_id='',expires_at=0
             WHERE resource_key=? AND task_id=? AND lease_id=? AND generation=?''',
             (key,task_id,lease_id,generation)).rowcount
        if changed != 1:
            raise GraphConflict('stale or missing lease fence')

    def dispatch(self, snapshot):
        """Claim FIFO head, not auto-run. Called ONLY with a fresh Forge capture."""
        obs=self._snapshot(snapshot)
        world,actor=obs['world_id'],obs['actor_id']
        now=int(time.time())
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('''SELECT task_id FROM cplus_e1_tasks WHERE world_id=? AND actor_id=?
                   AND state IN ('QUEUED','HOLD','DISPATCHING','CANCEL_PENDING')
                   ORDER BY sequence LIMIT 1''',(world,actor)).fetchone()
            if not row:
                return {'world_id':world,'actor_id':actor,'world_effects':False,
                        'execution_mode':'READ_ONLY','state':'EMPTY'}
            task=self._task(db,row[0])
            if task['state']!='QUEUED':
                raise GraphConflict('FIFO head not ready; cancel/reconcile first')
            if (task['dimension_id'],task['session_id']) != (obs['dimension_id'],obs['session_id']):
                db.execute("UPDATE cplus_e1_tasks SET state='HOLD' WHERE task_id=?", (task['task_id'],))
                return self._task(db,task['task_id'])
            # Validate target read-only graph and projection from E1.
            if (not task['nodes'] or any(n['kind'] not in ('observe_cells','report_coverage','report_permission')
                   for n in task['nodes'])):
                db.execute("UPDATE cplus_e1_tasks SET state='HOLD' WHERE task_id=?", (task['task_id'],))
                return self._task(db,task['task_id'])
            key=self._resource(obs)
            lease,generation=self._acquire(db,key,task['task_id'],now)
            attempt=str(uuid4())
            db.execute('''INSERT INTO cplus_e2_runs(task_id,lease_id,generation,resource_key,
                    actor_id,session_id,center_json,start_tick,start_observation_id,attempt_id,state,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'DISPATCHING',?)
                    ON CONFLICT(task_id) DO UPDATE SET lease_id=excluded.lease_id,
                    generation=excluded.generation,resource_key=excluded.resource_key,
                    actor_id=excluded.actor_id,session_id=excluded.session_id,
                    center_json=excluded.center_json,start_tick=excluded.start_tick,
                    start_observation_id=excluded.start_observation_id,
                    attempt_id=excluded.attempt_id,state=excluded.state,updated_at=excluded.updated_at''',
                    (task['task_id'],lease,generation,key,actor,obs['session_id'],
                     _canonical(obs['center']),obs['capture_tick'],obs['observation_id'],attempt,now))
            db.execute("UPDATE cplus_e1_tasks SET state='DISPATCHING',lease_id=?,generation=? WHERE task_id=?",
                       (lease,generation,task['task_id']))
            result=self._task(db,task['task_id'])
            result.update({'lease_expires_at':now+self.LEASE_SECONDS,'attempt_id':attempt,
                           'dispatch_observation_id':obs['observation_id']})
            return result

    def verify(self, task_id, lease_id, generation, snapshot):
        _uuid(task_id);_uuid(lease_id)
        if type(generation) is not int or generation < 1:
            raise GraphConflict('invalid lease generation')
        obs=self._snapshot(snapshot)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            task=self._task(db,task_id)
            run=db.execute('''SELECT lease_id,generation,resource_key,actor_id,session_id,
                        center_json,start_tick,start_observation_id,attempt_id,state
                        FROM cplus_e2_runs WHERE task_id=?''',(task_id,)).fetchone()
            if not run or run[0]!=lease_id or run[1]!=generation or run[9]!='DISPATCHING' or task['state']!='DISPATCHING':
                raise GraphConflict('stale or inactive read-only attempt')
            if (task['world_id'],task['dimension_id'],task['actor_id'],task['session_id']) != (
                obs['world_id'],obs['dimension_id'],obs['actor_id'],obs['session_id']):
                raise GraphConflict('current Forge snapshot identity mismatch')
            if (run[3],run[4],run[5]) != (obs['actor_id'],obs['session_id'],_canonical(obs['center'])):
                raise GraphConflict('read target moved or session changed')
            if obs['capture_tick'] < run[6] or obs['observation_id']==run[7]:
                raise GraphConflict('not a new Forge observation')
            entry=db.execute('''SELECT task_id,lease_id,generation,expires_at FROM cplus_e2_leases
                               WHERE resource_key=?''',(run[2],)).fetchone()
            if not entry or entry[:3]!=(task_id,lease_id,generation) or entry[3]<=int(time.time()):
                db.execute("UPDATE cplus_e1_tasks SET state='HOLD' WHERE task_id=?", (task_id,))
                db.execute("UPDATE cplus_e2_runs SET state='HOLD' WHERE task_id=?", (task_id,))
                return self._task(db,task_id)
            # A validated *new* Forge SERVER_WORLD snapshot is the evidence for
            # read-only results only. No C-F2 permission is promoted to ALLOW.
            snap_hash=hashlib.sha256(_canonical(obs).encode('utf-8')).hexdigest()
            nodes=task['nodes']
            done=set()
            for n in nodes:
                if any(parent not in done for parent in n['depends_on']):
                    raise GraphConflict('DAG predecessor not verified')
                if n['kind']=='observe_cells':
                    result={'recorded':obs['coverage']['recorded'],'unknown':obs['coverage']['unknown']}
                elif n['kind']=='report_coverage':
                    result={'complete':obs['coverage']['complete'],'recorded':obs['coverage']['recorded']}
                elif n['kind']=='report_permission':
                    result={'decision':obs['permission']['decision'],'operation':obs['permission']['operation'],
                            'note':'not an execution permit'}
                else:
                    raise GraphConflict('non read-only node refused')
                evidence=f"{obs['observation_id']}:{n['node_id']}"
                db.execute("UPDATE cplus_e1_nodes SET state='READ_VERIFIED',evidence_id=?,attempt_id=? WHERE task_id=? AND node_id=?",
                           (evidence,run[8],task_id,n['node_id']))
                db.execute('''INSERT INTO cplus_e2_receipts(task_id,node_id,attempt_id,observation_id,snapshot_hash,result_json)
                              VALUES(?,?,?,?,?,?)''',(task_id,n['node_id'],run[8],obs['observation_id'],snap_hash,_canonical(result)))
                done.add(n['node_id'])
            self._release(db,run[2],task_id,lease_id,generation)
            db.execute("UPDATE cplus_e2_runs SET state='READ_ONLY_VERIFIED',updated_at=? WHERE task_id=?", (int(time.time()),task_id))
            db.execute("UPDATE cplus_e1_tasks SET state='READ_ONLY_VERIFIED',lease_id='' WHERE task_id=?", (task_id,))
            result=self._task(db,task_id)
            result.update({'attempt_id':run[8], 'verified_observation_id':obs['observation_id'],
                           'read_only_receipts':len(nodes)})
            return result

    def cancel_task(self, task_id, requester_id):
        """Read-only cancellation has a local durable terminal ACK; no C2 ACK."""
        _uuid(task_id);_uuid(requester_id)
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            task=self._task(db,task_id)
            if task['requester_id']!=requester_id:
                raise GraphConflict('requester mismatch')
            if task['state'] in ('QUEUED','HOLD'):
                # A held read attempt may still have an expired lease row.
                # Clear it only when its fencing tuple still owns that row.
                held=db.execute('SELECT resource_key,lease_id,generation FROM cplus_e2_runs WHERE task_id=?',(task_id,)).fetchone()
                if held:
                    db.execute('''UPDATE cplus_e2_leases SET task_id='',lease_id='',expires_at=0
                      WHERE resource_key=? AND task_id=? AND lease_id=? AND generation=?''',
                      (held[0],task_id,held[1],held[2]))
                db.execute("UPDATE cplus_e1_tasks SET state='CANCELLED',lease_id='' WHERE task_id=?", (task_id,))
            elif task['state'] in ('DISPATCHING','CANCEL_PENDING'):
                run=db.execute('''SELECT resource_key,lease_id,generation FROM cplus_e2_runs
                                  WHERE task_id=?''',(task_id,)).fetchone()
                db.execute("UPDATE cplus_e1_tasks SET state='CANCEL_PENDING' WHERE task_id=?", (task_id,))
                if not run:
                    raise GraphConflict('missing read-only run; manual HOLD required')
                self._release(db,run[0],task_id,run[1],run[2])
                ack=str(uuid4())
                db.execute('''INSERT OR REPLACE INTO cplus_e2_cancel_acks
                      (task_id,lease_id,generation,ack_id) VALUES(?,?,?,?)''',
                      (task_id,run[1],run[2],ack))
                db.execute("UPDATE cplus_e2_runs SET state='CANCELLED' WHERE task_id=?",(task_id,))
                db.execute("UPDATE cplus_e1_tasks SET state='CANCELLED',lease_id='' WHERE task_id=?",(task_id,))
            result=self._task(db,task_id)
            row=db.execute('SELECT ack_id FROM cplus_e2_cancel_acks WHERE task_id=?',(task_id,)).fetchone()
            result['cancel_ack_id']=row[0] if row else ''
            return result
