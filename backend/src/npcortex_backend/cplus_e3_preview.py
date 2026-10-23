"""E3-D G1: durable *non-executable* movement intent preview.

This table is deliberately separate from E1/E2 task/lease tables.  It is not an
admission queue and MUST NOT be used by any movement dispatcher as authority.
Only an operator-authenticated, server-origin Forge request can reach this route.
No backend path invokes C2 or mutates Minecraft.
"""
import hashlib
import json
import math
import time
from .cplus_e_graph import ReadOnlyGraph, GraphConflict, _uuid
from .cplus_f2_observation import validate_v2
from .identity import IdentityError

_FIELDS = {'schema_version', 'request_id', 'task_id', 'requester_id', 'actor_id',
           'world_id', 'dimension_id', 'session_id', 'node_id', 'attempt_id',
           'action_kind', 'target', 'tolerance', 'snapshot'}


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False,
                      allow_nan=False)


def validate_move_preview(payload):
    if not isinstance(payload, dict) or set(payload) != _FIELDS:
        raise GraphConflict('E3 typed preview requires exact fields')
    if type(payload['schema_version']) is not int or payload['schema_version'] != 1:
        raise GraphConflict('unsupported E3 preview version')
    for key in ('request_id', 'task_id', 'requester_id', 'actor_id',
                'world_id', 'session_id', 'attempt_id'):
        _uuid(payload[key])
    if payload['action_kind'] != 'move_to' or payload['node_id'] != 'move':
        raise GraphConflict('E3 G1 supports only single move_to preview')
    try:
        obs = validate_v2(payload['snapshot'])
    except (ValueError, TypeError, IdentityError) as exc:
        raise GraphConflict('invalid E3 Forge snapshot: ' + type(exc).__name__) from None
    for key in ('world_id', 'actor_id', 'dimension_id', 'session_id'):
        if payload[key] != obs[key]:
            raise GraphConflict('E3 preview snapshot identity mismatch')
    target=payload['target']
    if not isinstance(target, dict) or set(target) != {'x','y','z'}:
        raise GraphConflict('invalid E3 target')
    for value in target.values():
        if type(value) is not int or not -(2**24) < value < 2**24:
            raise GraphConflict('E3 preview coordinates must be bounded integers')
    tolerance=payload['tolerance']
    if (type(tolerance) not in (int,float) or not math.isfinite(tolerance)
            or not 0.1 <= tolerance <= 2.0):
        raise GraphConflict('invalid movement tolerance')
    # Scope guard: do not claim long-range/multichunk G1 coverage. Forge
    # independently checks loaded chunks, Follow, C1/C2 ownership and terrain.
    pos=obs['center']
    if (abs(target['x']-pos[0]) > 6 or abs(target['y']-pos[1]) > 3
            or abs(target['z']-pos[2]) > 6):
        raise GraphConflict('E3 G1 only previews local movement')
    return obs


class MovePreviewStore(ReadOnlyGraph):
    def __init__(self, database):
        super().__init__(database)
        with self.connection() as db:
            db.executescript('''
              CREATE TABLE IF NOT EXISTS cplus_e3_g1_move_previews (
                request_id TEXT PRIMARY KEY, task_id TEXT NOT NULL UNIQUE,
                attempt_id TEXT NOT NULL UNIQUE, requester_id TEXT NOT NULL,
                actor_id TEXT NOT NULL, world_id TEXT NOT NULL,
                dimension_id TEXT NOT NULL, session_id TEXT NOT NULL,
                observation_id TEXT NOT NULL, payload_hash TEXT NOT NULL,
                target_json TEXT NOT NULL, state TEXT NOT NULL
                    CHECK (state='PREVIEW_ONLY'), created_at INTEGER NOT NULL
              );
              CREATE INDEX IF NOT EXISTS cplus_e3_g1_actor
                ON cplus_e3_g1_move_previews(world_id,actor_id,created_at);
            ''')

    def preview(self, payload):
        obs = validate_move_preview(payload)
        digest = hashlib.sha256(_canonical(payload).encode('utf-8')).hexdigest()
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute('SELECT task_id,payload_hash FROM cplus_e3_g1_move_previews WHERE request_id=?',
                           (payload['request_id'],)).fetchone()
            if row:
                if row != (payload['task_id'], digest):
                    raise GraphConflict('E3 request_id reused with mismatched payload')
            else:
                try:
                    db.execute('''INSERT INTO cplus_e3_g1_move_previews
                        (request_id,task_id,attempt_id,requester_id,actor_id,world_id,
                         dimension_id,session_id,observation_id,payload_hash,target_json,
                         state,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,'PREVIEW_ONLY',?)''',
                        (payload['request_id'],payload['task_id'],payload['attempt_id'],
                         payload['requester_id'],payload['actor_id'],payload['world_id'],
                         payload['dimension_id'],payload['session_id'],obs['observation_id'],
                         digest,_canonical(payload['target']),int(time.time())))
                except __import__('sqlite3').IntegrityError:
                    raise GraphConflict('E3 duplicate task/attempt identifier') from None
        return {'schema_version':1, 'request_id':payload['request_id'],
                'task_id':payload['task_id'], 'attempt_id':payload['attempt_id'],
                'requester_id':payload['requester_id'], 'actor_id':payload['actor_id'],
                'world_id':payload['world_id'], 'dimension_id':payload['dimension_id'],
                'session_id':payload['session_id'], 'node_id':'move',
                'observation_id':obs['observation_id'],
                'action_kind':'move_to','target':payload['target'],
                'tolerance':payload['tolerance'], 'state':'PREVIEW_ONLY',
                'execution_mode':'DRY_RUN', 'can_execute':False,
                'world_effects':False, 'motion_started':False}
