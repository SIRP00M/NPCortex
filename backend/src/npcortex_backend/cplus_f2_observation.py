"""C-F2 read-only, *reported* ServerWorld evidence contract.

This validates shape and conservative permissions; it CANNOT authenticate JSON.
Only Forge's on-server-thread sampler is a trusted data origin. Do not accept
this structure over legacy unauthenticated endpoints as authority for actions.
"""
import json
import math
from .agent_contracts import resource, block_position, position, validate_snapshot
from .identity import IdentityError, canonical_uuid, dimension_id

MAX_BYTES = 16000
MAX_BLOCKS = 27
MAX_ENTITIES = 8
MAX_DROPS = 8
_STATUSES = {"OBSERVED", "UNKNOWN_CHUNK", "OUT_OF_HEIGHT", "OUT_OF_BORDER"}
_OPERATIONS = {"observe", "break_block", "place_block", "use_block"}


def decide(status, operation):
    """Non-executing permission *decision*; unknown claim mods are never ALLOW."""
    if status not in _STATUSES or operation not in _OPERATIONS:
        return "DENY", "unsupported_operation_or_status"
    if status in ("OUT_OF_HEIGHT", "OUT_OF_BORDER"):
        return "DENY", "outside_world_bounds"
    if status == "UNKNOWN_CHUNK":
        return "UNKNOWN", "chunk_not_loaded"
    if operation == "observe":
        return "ALLOW", "server_read_only_observation"
    return "UNKNOWN", "mutation_claim_adapter_unverified"


def _integer(value, name, lo, hi):
    if type(value) is not int or not lo <= value <= hi:
        raise IdentityError(f"{name} invalid")
    return value


def _flag(value, name):
    if type(value) is not bool:
        raise IdentityError(f"{name} must be boolean")
    return value


def _uuid(value, name):
    return canonical_uuid(value, name)


def _entries(items, max_size, kind):
    if not isinstance(items, list) or len(items) > max_size:
        raise IdentityError(f"{kind} exceeds bound or not a list")
    return items


def validate_v2(raw, world_id=None, dimension=None, actor_id=None):
    """Strict schema/identity/coverage/freshness/decision shape check.

    This never grants execution authority; calling code must separately verify
    server origin and reevaluate an action against CURRENT server state.
    """
    required = {"schema_version", "world_id", "dimension_id", "actor_id", "session_id",
                "observation_id", "capture_tick", "source", "read_only", "center", "radius",
                "blocks", "coverage", "nearby_drops", "drops_truncated", "nearby_entities",
                "entities_truncated", "permission"}
    if not isinstance(raw, dict) or set(raw) != required:
        raise IdentityError("C-F2 v2 fields missing or unexpected")
    if len(json.dumps(raw, ensure_ascii=False, allow_nan=False).encode()) > MAX_BYTES:
        raise IdentityError("C-F2 snapshot exceeds byte limit")
    if type(raw["schema_version"]) is not int or raw["schema_version"] != 2:
        raise IdentityError("unsupported observation schema")
    world = _uuid(raw["world_id"], "world_id")
    dim = dimension_id(raw["dimension_id"])
    actor = _uuid(raw["actor_id"], "actor_id")
    _uuid(raw["session_id"], "session_id")
    _uuid(raw["observation_id"], "observation_id")
    if world_id is not None and world != _uuid(world_id, "expected world"):
        raise IdentityError("world identity mismatch")
    if dimension is not None and dim != dimension_id(dimension):
        raise IdentityError("dimension mismatch")
    if actor_id is not None and actor != _uuid(actor_id, "expected actor"):
        raise IdentityError("actor mismatch")
    tick = _integer(raw["capture_tick"], "capture_tick", 0, 2**63-1)
    if raw["source"] != "SERVER_WORLD" or raw["read_only"] is not True:
        raise IdentityError("only reported SERVER_WORLD read-only observations supported")
    center = block_position(raw["center"], "center")
    if raw["radius"] != 1 or type(raw["radius"]) is not int:
        raise IdentityError("C-F2 radius must be one")
    blocks = _entries(raw["blocks"], MAX_BLOCKS, "blocks")
    # A complete fixed 3x3x3 footprint; entries cannot be silently absent.
    if len(blocks) != 27:
        raise IdentityError("missing sampled cells")
    expected = {(center[0]+dx, center[1]+dy, center[2]+dz)
                for dx in (-1,0,1) for dy in (-1,0,1) for dz in (-1,0,1)}
    seen = set()
    unknown = 0
    center_status = None
    for cell in blocks:
        if not isinstance(cell, dict) or not {"position", "status"}.issubset(cell):
            raise IdentityError("invalid block observation")
        p = tuple(block_position(cell["position"], "cell.position"))
        if p not in expected or p in seen:
            raise IdentityError("out-of-range or duplicate observed cell")
        seen.add(p)
        status = cell["status"]
        if status not in _STATUSES:
            raise IdentityError("unrecognized cell status")
        if status == "OBSERVED":
            if set(cell) != {"position", "status", "block_id", "properties"}:
                raise IdentityError("observed blocks need typed ID and properties")
            resource(cell["block_id"], "block_id")
            props = cell["properties"]
            if not isinstance(props, dict) or len(props) > 16:
                raise IdentityError("invalid bounded block properties")
            for key, value in props.items():
                if (not isinstance(key, str) or not key or len(key) > 64
                        or not isinstance(value, str) or len(value) > 64):
                    raise IdentityError("invalid block property")
        else:
            if set(cell) != {"position", "status"}:
                raise IdentityError("unknown blocks must not invent IDs/properties")
            unknown += 1
        if list(p) == center:
            center_status = status
    coverage = raw["coverage"]
    if (not isinstance(coverage, dict) or set(coverage) !=
            {"requested", "recorded", "unknown", "complete", "truncated"}):
        raise IdentityError("coverage invalid")
    if (coverage["requested"] != 27 or coverage["recorded"] != 27
            or coverage["unknown"] != unknown
            or type(coverage["unknown"]) is not int
            or coverage["complete"] is not (unknown == 0)
            or coverage["truncated"] is not False):
        raise IdentityError("coverage inconsistent")
    for key, cap, dropped in (("nearby_entities", MAX_ENTITIES, False),
                              ("nearby_drops", MAX_DROPS, True)):
        seen_ids = set()
        for item in _entries(raw[key], cap, key):
            expected_keys = ({"entity_id", "item_id", "count", "position"} if dropped
                             else {"entity_id", "type_id", "position"})
            if not isinstance(item, dict) or set(item) != expected_keys:
                raise IdentityError("invalid nearby entity/drop")
            eid = _uuid(item["entity_id"], "entity_id")
            if eid in seen_ids:
                raise IdentityError("duplicate nearby entity/drop")
            seen_ids.add(eid)
            resource(item["item_id"] if dropped else item["type_id"], "entity type/item")
            position(item["position"], "entity position")
            if dropped:
                _integer(item["count"], "drop count", 1, 10000)
    _flag(raw["drops_truncated"], "drops_truncated")
    _flag(raw["entities_truncated"], "entities_truncated")
    perm = raw["permission"]
    if not isinstance(perm, dict) or set(perm) != {
            "operation", "position", "decision", "reason", "checked_tick", "evidence_id"}:
        raise IdentityError("invalid permission evidence")
    if perm["operation"] != "break_block" or block_position(perm["position"]) != center:
        raise IdentityError("permission target mismatch")
    decision, reason = decide(center_status, perm["operation"])
    if (perm["decision"] != decision or perm["reason"] != reason
            or perm["checked_tick"] != tick
            or perm["evidence_id"] != raw["observation_id"]):
        raise IdentityError("permission evidence contradicts observation")
    return raw


def evidence_fresh(snapshot, *, world_id, dimension, actor_id, session_id,
                   current_tick, max_age=20):
    """Fail closed on world/dimension/actor/session/clock mismatch or age expiry."""
    if type(current_tick) is not int or type(max_age) is not int or max_age < 0:
        return False
    try:
        return (snapshot["world_id"] == _uuid(world_id,"world_id")
                and snapshot["dimension_id"] == dimension_id(dimension)
                and snapshot["actor_id"] == _uuid(actor_id,"actor_id")
                and snapshot["session_id"] == _uuid(session_id,"session_id")
                and 0 <= current_tick - snapshot["capture_tick"] <= max_age)
    except (IdentityError, KeyError, TypeError, ValueError):
        return False


def legacy_v1_view(v2, existing_v1):
    """Version negotiation: C0 remains v1, without promoting v2 facts or permissions.

    The v1 view is validated as before and no new advertised skills are added.
    A future authenticated endpoint can opt into v2 without changing C0.
    """
    validate_v2(v2)
    old = validate_snapshot(existing_v1, v2["world_id"], v2["dimension_id"], v2["actor_id"])
    # Never convert v2 OBSERVED into a C0 mutation capability.
    return old
