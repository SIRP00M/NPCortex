"""G3-1 terminal-evidence contract DRY RUN only.

This is a schema/correlation guard, NOT an authoritative terminal receipt.
Neither this module nor any G3-1 route finalizes or unlocks a G2 attempt.
The future G3-2 bridge MUST collect evidence live on Forge's server thread,
then authenticate, persist and acknowledge it before reporting COMPLETED.
"""
import math
from .cplus_e_graph import GraphConflict, _uuid
from .identity import dimension_id

IDENTITY = ('task_id', 'request_id', 'attempt_id', 'requester_id', 'world_id',
            'dimension_id', 'session_id', 'actor_id', 'observation_id', 'goal_id')
FIELDS = set(IDENTITY) | {'schema_version', 'action_id', 'node_id', 'action_kind',
    'target', 'tolerance', 'outcome', 'c1', 'c2', 'c0', 'world_effects'}


def _reject(message):
    raise GraphConflict('G3-1 contract HOLD: '+message)


def _uuid_field(obj, key):
    try:
        return _uuid(obj[key])
    except (KeyError, ValueError, TypeError):
        _reject('invalid '+key)


def _position(obj):
    if type(obj) is not dict or set(obj) != {'x','y','z'}:
        _reject('invalid position shape')
    position=[]
    for key in ('x','y','z'):
        value=obj[key]
        if type(value) not in (int,float) or not math.isfinite(value) or abs(value)>=2**24:
            _reject('invalid position '+key)
        position.append(value)
    return position


def validate_terminal_candidate(candidate, admitted):
    """Validate exact matching ID/goal/action and typed *candidate* fields.

    Returns only a DRY_RUN verdict; never authorizes finalization, motion,
    permissions, world writes, lease release, or reconciliation of HOLD rows.
    """
    if type(candidate) is not dict or set(candidate)!=FIELDS or type(admitted) is not dict:
        _reject('unexpected fields')
    if type(candidate['schema_version']) is not int or candidate['schema_version'] != 1:
        _reject('schema version')
    for field in IDENTITY:
        if field=='dimension_id':
            try: dimension_id(candidate[field])
            except (KeyError, ValueError, TypeError): _reject('dimension_id')
        else:
            _uuid_field(candidate,field)
        if admitted.get(field)!=candidate[field]:
            _reject('admission identity mismatch: '+field)
    _uuid_field(candidate,'action_id')
    if admitted.get('state') not in ('C2_ACCEPTED','CANCEL_PENDING'):
        _reject('admission not C2_ACCEPTED; HOLD remains fenced')
    if candidate['action_kind']!='move_to' or candidate['node_id']!='move':
        _reject('nonmovement action')
    if candidate['outcome']!='SUCCEEDED':
        _reject('G3-1 only models a successful terminal candidate')
    if candidate['world_effects'] is not False:
        _reject('world effect not permitted')
    if type(candidate['target']) is not dict or set(candidate['target'])!={'x','y','z'}:
        _reject('target shape')
    if any(type(candidate['target'][k]) is not int for k in ('x','y','z')):
        _reject('target integers required')
    coords=_position(candidate['target'])
    if candidate['target']!=admitted.get('target'):
        _reject('target mismatch')
    tolerance=candidate['tolerance']
    if type(tolerance) not in (int,float) or not math.isfinite(tolerance) or not 0.20<=tolerance<=0.75:
        _reject('tolerance range')
    if type(admitted.get('tolerance')) not in (int,float) or tolerance!=admitted['tolerance']:
        _reject('tolerance mismatch')
    c1,c2,c0 = candidate['c1'],candidate['c2'],candidate['c0']
    if type(c1) is not dict or set(c1)!={'phase','active','position'}:
        _reject('c1 evidence shape')
    if c1['phase']!='COMPLETE' or c1['active'] is not False:
        _reject('c1 receipt not terminal')
    live=_position(c1['position'])
    if sum((a-b)**2 for a,b in zip(coords,live))>tolerance*tolerance:
        _reject('actual position outside tolerance')
    if type(c2) is not dict or set(c2)!={'goal_id','action_id','state','local_verified'}:
        _reject('c2 evidence shape')
    if c2['goal_id']!=candidate['goal_id'] or c2['action_id']!=candidate['action_id'] or (
            c2['state']!='COMPLETED' or c2['local_verified'] is not True):
        _reject('c2 terminal mismatch')
    if type(c0) is not dict or set(c0)!={'goal_id','action_id','status','verified'}:
        _reject('c0 evidence shape')
    if c0['goal_id']!=candidate['goal_id'] or c0['action_id']!=candidate['action_id'] or (
            c0['status']!='completed' or c0['verified'] is not True):
        _reject('c0 terminal mismatch')
    return {'schema_version':1,'task_id':candidate['task_id'],
            'attempt_id':candidate['attempt_id'], 'goal_id':candidate['goal_id'],
            'action_id':candidate['action_id'], 'contract_valid':True,
            'state':'DRY_RUN_VALIDATED','completion_verified':False,
            'durable_ack':False,'release_actor_fence':False,'world_effects':False}
