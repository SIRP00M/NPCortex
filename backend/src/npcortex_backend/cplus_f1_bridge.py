"""C-F1 D+ D1/D2/D4: authenticated, bounded, read-only journal delivery.

This is an ADDITIVE inbox and immutable receipt outbox, separate from legacy
C2 and the staged cplus_f1_projection. It has no world-action API. A SQLite
transaction commits inbox, checkpoint and ACK receipt atomically.
"""
import hashlib
import hmac
import os
from pathlib import Path
import sqlite3
import struct
from uuid import UUID


class BridgeConflict(ValueError):
    def __init__(self, reason, expected=None):
        super().__init__(reason)
        self.expected = expected


def _uuid(value, label):
    try:
        if type(value) is not str or str(UUID(value)) != value or UUID(value).int == 0:
            raise ValueError()
    except (TypeError, ValueError, AttributeError):
        raise BridgeConflict('invalid ' + label) from None
    return value


def _mutf(value):
    """Java DataOutputStream.writeUTF: modified UTF8 of UTF16 code units."""
    if type(value) is not str:
        raise BridgeConflict('invalid UTF string')
    raw = bytearray()
    units = value.encode('utf-16-be', 'surrogatepass')
    for i in range(0, len(units), 2):
        x = (units[i] << 8) | units[i + 1]
        if x == 0:
            raw.extend((0xc0, 0x80))
        elif x <= 0x7f:
            raw.append(x)
        elif x <= 0x7ff:
            raw.extend((0xc0 | (x >> 6), 0x80 | (x & 63)))
        else:
            raw.extend((0xe0 | (x >> 12), 0x80 | ((x >> 6) & 63), 0x80 | (x & 63)))
    if len(raw) > 65535:
        raise BridgeConflict('UTF length exceeds Java limit')
    return struct.pack('>H', len(raw)) + raw


def validate_wire(wire):
    """Verify the exact v1 Java durable ledger binary frame digest."""
    if type(wire) is not dict or set(wire) != {'version', 'event', 'previous_hash', 'record_hash'} or (type(wire['version']) is not int or wire['version'] != 1):
        raise BridgeConflict('unsupported wire envelope')
    e = wire['event']
    fields = {'sequence', 'event_id', 'world_id', 'actor_id', 'task_id', 'action_id',
              'attempt_id', 'parent_event_id', 'epoch', 'stage', 'fingerprint', 'evidence_id'}
    if type(e) is not dict or set(e) != fields:
        raise BridgeConflict('invalid event fields')
    for field in ('event_id', 'world_id', 'actor_id', 'task_id', 'action_id', 'attempt_id'):
        _uuid(e[field], field)
    if e['parent_event_id'] is not None:
        _uuid(e['parent_event_id'], 'parent_event_id')
    if e['action_id'] == e['attempt_id']:
        raise BridgeConflict('action/attempt collision')
    for key in ('sequence', 'epoch'):
        if type(e[key]) is not int or e[key] < 1 or e[key] > 2**63-1:
            raise BridgeConflict('invalid ' + key)
    stages = {'FENCE', 'INTENT', 'EXECUTION_STARTED', 'OBSERVED_PARTIAL', 'OBSERVED_EXTERNAL',
              'IN_DOUBT', 'RECONCILED', 'VERIFIED', 'CANCELLED'}
    if type(e['stage']) is not str or e['stage'] not in stages:
        raise BridgeConflict('unknown stage')
    if type(e['fingerprint']) is not str or len(e['fingerprint']) != 64 or any(c not in '0123456789abcdef' for c in e['fingerprint']):
        raise BridgeConflict('invalid fingerprint')
    if type(e['evidence_id']) is not str or len(e['evidence_id']) > 160:
        raise BridgeConflict('invalid evidence')
    if e['stage'] in {'VERIFIED','CANCELLED','RECONCILED','OBSERVED_EXTERNAL','OBSERVED_PARTIAL'} and not e['evidence_id'].strip():
        raise BridgeConflict('missing independent evidence')
    if e['stage'] == 'FENCE' and e['evidence_id']:
        raise BridgeConflict('fence cannot prove effects')
    prev, digest = wire['previous_hash'], wire['record_hash']
    for key, value in (('previous_hash', prev), ('record_hash', digest)):
        if type(value) is not str or len(value) != 64 or any(c not in '0123456789abcdef' for c in value):
            raise BridgeConflict('invalid ' + key)
    encoded = struct.pack('>iq', 1, e['sequence'])
    for key in ('event_id', 'world_id', 'actor_id', 'task_id', 'action_id', 'attempt_id'):
        encoded += _mutf(e[key])
    encoded += _mutf(e['parent_event_id'] or '')
    encoded += struct.pack('>q', e['epoch']) + _mutf(e['stage']) + _mutf(e['fingerprint']) + _mutf(e['evidence_id'])
    actual = hashlib.sha256(bytes.fromhex(prev) + encoded).hexdigest()
    if not hmac.compare_digest(actual, digest):
        raise BridgeConflict('journal record hash mismatch')
    return e


class DurableBridge:
    def __init__(self, database):
        self.path = Path(database)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.executescript('''
              CREATE TABLE IF NOT EXISTS cplus_f1_bridge_inbox (
                world_id TEXT NOT NULL, sequence INTEGER NOT NULL,
                event_id TEXT NOT NULL UNIQUE, actor_id TEXT NOT NULL,
                action_id TEXT NOT NULL, epoch INTEGER NOT NULL,
                stage TEXT NOT NULL, previous_hash TEXT NOT NULL,
                record_hash TEXT NOT NULL, payload BLOB NOT NULL,
                PRIMARY KEY(world_id, sequence)
              );
              CREATE TABLE IF NOT EXISTS cplus_f1_bridge_checkpoint (
                world_id TEXT PRIMARY KEY, sequence INTEGER NOT NULL,
                record_hash TEXT NOT NULL
              );
              CREATE TABLE IF NOT EXISTS cplus_f1_bridge_receipts (
                event_id TEXT PRIMARY KEY REFERENCES cplus_f1_bridge_inbox(event_id),
                world_id TEXT NOT NULL, sequence INTEGER NOT NULL,
                receipt_id TEXT NOT NULL UNIQUE, state TEXT NOT NULL DEFAULT 'PENDING'
                  CHECK(state IN ('PENDING', 'ACKED'))
              );
              CREATE INDEX IF NOT EXISTS cplus_f1_bridge_actor_epoch
                ON cplus_f1_bridge_inbox(world_id, actor_id, epoch);
            ''')

    from contextlib import contextmanager

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path, timeout=3.0)
        db.execute('PRAGMA foreign_keys=ON')
        db.execute('PRAGMA busy_timeout=3000')
        db.execute('PRAGMA journal_mode=WAL')
        db.execute('PRAGMA synchronous=FULL')
        try:
            with db:
                yield db
        finally:
            db.close()

    @staticmethod
    def _receipt(e, digest):
        return hashlib.sha256((e['event_id'] + ':' + str(e['sequence']) + ':' + digest).encode('ascii')).hexdigest()

    def receive(self, wire):
        e = validate_wire(wire)
        world, seq = e['world_id'], e['sequence']
        digest = wire['record_hash']
        receipt = self._receipt(e, digest)
        # Serialize canonically in binary-safe sqlite BLOB; strict equality on duplicates.
        import json
        data = json.dumps(wire, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
        if len(data) > 4096:
            raise BridgeConflict('wire event too large')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            existing = db.execute('SELECT world_id,sequence,payload FROM cplus_f1_bridge_inbox WHERE event_id=?', (e['event_id'],)).fetchone()
            if existing:
                if existing[0] != world or existing[1] != seq or existing[2] != data:
                    raise BridgeConflict('conflicting event id reuse')
                return {'status':'ACKED', 'duplicate':True,'event_id':e['event_id'], 'sequence':seq, 'receipt_id':receipt}
            checkpoint = db.execute('SELECT sequence,record_hash FROM cplus_f1_bridge_checkpoint WHERE world_id=?', (world,)).fetchone()
            expected = checkpoint[0]+1 if checkpoint else 1
            if seq != expected:
                raise BridgeConflict('missing or out of order journal sequence', expected)
            previous = checkpoint[1] if checkpoint else '0'*64
            if wire['previous_hash'] != previous:
                raise BridgeConflict('journal hash-chain discontinuity')
            latest = db.execute('SELECT MAX(epoch) FROM cplus_f1_bridge_inbox WHERE world_id=? AND actor_id=?',
                                (world, e['actor_id'])).fetchone()[0]
            if e['stage'] == 'FENCE':
                if latest is not None and e['epoch'] <= latest:
                    raise BridgeConflict('stale actor session fence')
            elif latest is None or e['epoch'] != latest:
                raise BridgeConflict('missing or stale actor fence')
            # Mirror the Java ledger's non-execution state rules; receiving a wire
            # event never grants permission to create a world effect.
            prior=db.execute('SELECT actor_id,action_id,epoch,stage,payload FROM cplus_f1_bridge_inbox '
                             'WHERE world_id=? AND action_id=? AND stage!=\'FENCE\' ORDER BY sequence DESC LIMIT 1',
                             (world,e['action_id'])).fetchone()
            if e['stage']=='INTENT':
                if prior is not None: raise BridgeConflict('action id already exists')
            elif e['stage']!='FENCE':
                if prior is None: raise BridgeConflict('effect without durable intent')
                import json
                prev_event=json.loads(prior[4])['event']
                for field in ('world_id','actor_id','task_id','attempt_id','fingerprint'):
                    if prev_event[field]!=e[field]:
                        raise BridgeConflict('action owner/fingerprint conflict')
                previous_stage=prior[3]
                if previous_stage in ('VERIFIED','CANCELLED'):
                    raise BridgeConflict('terminal action immutable')
                if e['stage']=='EXECUTION_STARTED' and (previous_stage!='INTENT' or prior[2]!=e['epoch']):
                    raise BridgeConflict('old-session execution rejected')
                if e['stage']=='CANCELLED' and previous_stage!='INTENT':
                    raise BridgeConflict('cancel cannot undo world effects')
                if e['stage']=='RECONCILED' and previous_stage not in ('IN_DOUBT','OBSERVED_PARTIAL','OBSERVED_EXTERNAL'):
                    raise BridgeConflict('reconciliation needs uncertainty evidence')
                if e['stage']=='VERIFIED' and previous_stage not in ('RECONCILED','OBSERVED_PARTIAL','OBSERVED_EXTERNAL'):
                    raise BridgeConflict('verification requires observation')
            collision = db.execute('SELECT world_id,sequence FROM cplus_f1_bridge_inbox WHERE sequence=? AND world_id=?', (seq, world)).fetchone()
            if collision:
                raise BridgeConflict('sequence collision')
            db.execute('INSERT INTO cplus_f1_bridge_inbox VALUES (?,?,?,?,?,?,?,?,?,?)',
                       (world,seq,e['event_id'],e['actor_id'],e['action_id'],e['epoch'],e['stage'],wire['previous_hash'],digest,data))
            db.execute('INSERT INTO cplus_f1_bridge_receipts(event_id,world_id,sequence,receipt_id) VALUES (?,?,?,?)',
                       (e['event_id'],world,seq,receipt))
            db.execute('INSERT INTO cplus_f1_bridge_checkpoint VALUES (?,?,?) ON CONFLICT(world_id) DO UPDATE SET sequence=excluded.sequence,record_hash=excluded.record_hash',
                       (world,seq,digest))
        return {'status':'ACKED', 'duplicate':False,'event_id':e['event_id'], 'sequence':seq, 'receipt_id':receipt}

    def confirm(self, confirmation):
        """Confirm delivery checkpoint, not a Minecraft world effect."""
        if type(confirmation) is not dict or set(confirmation) != {'world_id','sequence','event_id','receipt_id'}:
            raise BridgeConflict('invalid confirmation fields')
        world = _uuid(confirmation['world_id'], 'world_id')
        event = _uuid(confirmation['event_id'], 'event_id')
        seq = confirmation['sequence']
        receipt = confirmation['receipt_id']
        if type(seq) is not int or not 1 <= seq <= 2**63-1 or type(receipt) is not str or len(receipt) != 64:
            raise BridgeConflict('invalid receipt confirmation')
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT world_id,sequence,receipt_id,state FROM cplus_f1_bridge_receipts WHERE event_id=?',
                             (event,)).fetchone()
            if row is None or (row[0],row[1],row[2]) != (world,seq,receipt):
                raise BridgeConflict('receipt/world/sequence mismatch')
            if row[3] == 'ACKED':
                return {'status':'CONFIRMED','duplicate':True,'sequence':seq}
            db.execute("UPDATE cplus_f1_bridge_receipts SET state='ACKED' WHERE event_id=?",(event,))
        return {'status':'CONFIRMED','duplicate':False,'sequence':seq}

    def recovery(self, world_id):
        """Read-only recovery classification, never a world mutation."""
        world_id = _uuid(world_id, 'world_id')
        summary = {'VERIFIED':0,'CANCELLED':0,'PREPARED':0,'PARTIAL':0,'IN_DOUBT':0}
        with self.connection() as db:
            rows = db.execute('''SELECT i.action_id,i.stage FROM cplus_f1_bridge_inbox i
                JOIN (SELECT action_id, MAX(sequence) AS last_seq
                      FROM cplus_f1_bridge_inbox WHERE world_id=? AND stage!='FENCE'
                      GROUP BY action_id) latest
                ON i.action_id=latest.action_id AND i.sequence=latest.last_seq
                WHERE i.world_id=?''', (world_id,world_id)).fetchall()
            for _action,stage in rows:
                if stage in ('CANCELLED','VERIFIED'):
                    summary[stage] += 1
                elif stage == 'INTENT':
                    summary['PREPARED'] += 1
                elif stage in ('OBSERVED_PARTIAL','OBSERVED_EXTERNAL'):
                    summary['PARTIAL'] += 1
                else:
                    summary['IN_DOUBT'] += 1
            pending=db.execute("SELECT COUNT(*) FROM cplus_f1_bridge_receipts WHERE world_id=? AND state='PENDING'",(world_id,)).fetchone()[0]
        return {'world_id':world_id,'classification':summary,'receipt_confirmations_pending':pending,
                'can_replay_world_actions':False, 'has_authoritative_world_observation':False}

    def status(self, world_id):
        world_id = _uuid(world_id, 'world_id')
        with self.connection() as db:
            row = db.execute('SELECT sequence,record_hash FROM cplus_f1_bridge_checkpoint WHERE world_id=?', (world_id,)).fetchone()
            count = db.execute('SELECT COUNT(*) FROM cplus_f1_bridge_inbox WHERE world_id=?', (world_id,)).fetchone()[0]
            unknown = db.execute("SELECT COUNT(*) FROM cplus_f1_bridge_inbox WHERE world_id=? AND stage NOT IN ('FENCE','CANCELLED','VERIFIED')", (world_id,)).fetchone()[0]
            return {'world_id':world_id,'sequence':row[0] if row else 0,
                    'record_hash':row[1] if row else '0'*64,'events':count,'nonterminal_events':unknown,
                    'world_replay_enabled':False}
