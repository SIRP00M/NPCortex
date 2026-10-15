"""C0 headless bridge contracts. Observations are supplied by a future Forge sensor.

This module validates *reported* facts; it does not have access to Minecraft.
The local HTTP API is unauthenticated, so only trusted local callers should use it.
"""
import json
import math
import re
from .identity import IdentityError, canonical_uuid, dimension_id

ITEM = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]{1,120}$")
POS = re.compile(r"^-?(?:0|[1-9][0-9]*),-?(?:0|[1-9][0-9]*),-?(?:0|[1-9][0-9]*)$")
MAX_SNAPSHOT_BYTES = 6000  # fits the existing 8192-byte HTTP envelope


def resource(value, field):
    if not isinstance(value, str) or not ITEM.fullmatch(value):
        raise IdentityError(f"{field} must be a namespaced Minecraft resource ID")
    return value


def position(value, field="position"):
    if not isinstance(value, list) or len(value) != 3 or any(
            isinstance(x, bool) or not isinstance(x, (int, float)) or
            not math.isfinite(x) or abs(x) > 30000000 for x in value):
        raise IdentityError(f"{field} must be three finite world coordinates")
    return [float(x) for x in value]


def block_position(value, field="position"):
    coords = position(value, field)
    if any(int(x) != x for x in coords):
        raise IdentityError(f"{field} must be integer block coordinates")
    return [int(x) for x in coords]


def block_key(value):
    if not isinstance(value, str) or not POS.fullmatch(value):
        raise IdentityError("blocks keys must be x,y,z integer positions")
    xyz = [int(x) for x in value.split(",")]
    block_position(xyz)
    return ",".join(str(x) for x in xyz)


def validate_criterion(obj):
    if not isinstance(obj, dict) or "kind" not in obj:
        raise IdentityError("criterion must be a typed object")
    kind = obj["kind"]
    if kind == "inventory_at_least" and set(obj) == {"kind", "item_id", "count"}:
        if type(obj["count"]) is not int or not 1 <= obj["count"] <= 100000:
            raise IdentityError("inventory criterion count must be 1-100000")
        return {"kind": kind, "item_id": resource(obj["item_id"], "item_id"), "count": obj["count"]}
    if kind == "block_is" and set(obj) == {"kind", "position", "block_id"}:
        return {"kind": kind, "position": block_position(obj["position"]),
                "block_id": resource(obj["block_id"], "block_id")}
    if kind == "position_near" and set(obj) == {"kind", "position", "distance"}:
        distance = obj["distance"]
        if isinstance(distance, bool) or not isinstance(distance, (int, float)) or not 0.1 <= distance <= 32:
            raise IdentityError("distance must be between 0.1 and 32")
        return {"kind": kind, "position": position(obj["position"]), "distance": float(distance)}
    raise IdentityError("unsupported or malformed success criterion")


def validate_criteria(criteria):
    if not isinstance(criteria, list) or not 1 <= len(criteria) <= 8:
        raise IdentityError("success_criteria must contain 1-8 criteria")
    return [validate_criterion(item) for item in criteria]


def validate_snapshot(snapshot, world_id, dimension, actor_id):
    required = {"world_id", "dimension_id", "actor_id", "position", "inventory", "blocks", "available_skills"}
    if (not isinstance(snapshot, dict) or not required.issubset(snapshot) or
            not set(snapshot).issubset(required | {"nearby_entities", "nearby_drops"})):
        raise IdentityError("snapshot needs identity, position, inventory, blocks and available_skills")
    if (canonical_uuid(snapshot["world_id"], "world_id") != world_id or
            dimension_id(snapshot["dimension_id"]) != dimension or
            canonical_uuid(snapshot["actor_id"], "actor_id") != actor_id):
        raise IdentityError("snapshot identity differs from goal's world/dimension/actor")
    pos = position(snapshot["position"])
    inventory = snapshot["inventory"]
    if not isinstance(inventory, dict) or len(inventory) > 64:
        raise IdentityError("inventory must be a bounded map")
    for item, amount in inventory.items():
        resource(item, "inventory item")
        if type(amount) is not int or not 0 <= amount <= 100000:
            raise IdentityError("inventory counts must be non-negative integers")
    blocks = snapshot["blocks"]
    if not isinstance(blocks, dict) or len(blocks) > 128:
        raise IdentityError("blocks must be a bounded observed-position map")
    for key, value in blocks.items():
        if block_key(key) != key:
            raise IdentityError("blocks keys must use canonical coordinates")
        resource(value, "block ID")
    skills = snapshot["available_skills"]
    if (not isinstance(skills, list) or len(skills) > 16 or
            any(not isinstance(x, str) or len(x) > 64 for x in skills) or len(set(skills)) != len(skills)):
        raise IdentityError("available_skills must be a distinct bounded list")
    entities = snapshot.get("nearby_entities", [])
    if not isinstance(entities, list) or len(entities) > 24:
        raise IdentityError("nearby_entities must be a bounded list")
    known_entities = set()
    for entity in entities:
        if not isinstance(entity, dict) or set(entity) != {"entity_id", "type_id", "position"}:
            raise IdentityError("nearby entity needs entity_id, type_id and position")
        eid = canonical_uuid(entity["entity_id"], "entity_id")
        if eid in known_entities:
            raise IdentityError("duplicate nearby entity")
        known_entities.add(eid)
        resource(entity["type_id"], "type_id")
        position(entity["position"])
    drops = snapshot.get("nearby_drops", [])
    if not isinstance(drops, list) or len(drops) > 24:
        raise IdentityError("nearby_drops must be a bounded list")
    for drop in drops:
        if not isinstance(drop, dict) or set(drop) != {"item_id", "count", "position"}:
            raise IdentityError("nearby drop needs item_id, count and position")
        resource(drop["item_id"], "drop item_id")
        position(drop["position"])
        if type(drop["count"]) is not int or not 1 <= drop["count"] <= 10000:
            raise IdentityError("drop count must be a positive integer")
    canonical = {"world_id": world_id, "dimension_id": dimension, "actor_id": actor_id,
                 "position": pos, "inventory": dict(inventory), "blocks": dict(blocks),
                 "available_skills": list(skills),"nearby_entities":entities,"nearby_drops":drops}
    if len(json.dumps(canonical, ensure_ascii=False).encode("utf-8")) > MAX_SNAPSHOT_BYTES:
        raise IdentityError("snapshot exceeds safe prompt/persistence budget")
    return canonical


def observed(criterion, snapshot):
    """Missing facts are UNKNOWN, never satisfied. Only sensor snapshots decide."""
    kind = criterion["kind"]
    if kind == "inventory_at_least":
        # Full-inventory contract: item not listed means zero.
        return snapshot["inventory"].get(criterion["item_id"], 0) >= criterion["count"]
    if kind == "block_is":
        key = ",".join(map(str, criterion["position"]))
        return snapshot["blocks"].get(key) == criterion["block_id"]
    if kind == "position_near":
        distance_sq = sum((a - b) ** 2 for a, b in zip(snapshot["position"], criterion["position"]))
        return distance_sq <= criterion["distance"] ** 2
    return False


def all_observed(criteria, snapshot):
    return all(observed(c, snapshot) for c in criteria)
