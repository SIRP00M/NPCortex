"""C-F0 proposed protocol v1 validation only. Not wired into live planner/service.

Do NOT treat a validated request as permission to execute a Minecraft action.
Forge server world + C1/C2 continue to own execution/verification.
"""
from uuid import UUID
from .identity import IdentityError, canonical_uuid, dimension_id
from .agent_contracts import resource

PROTOCOL_VERSION = 1
UUID_FIELDS = ("world_id", "actor_id", "goal_id", "task_id", "action_id", "attempt_id")
TARGET_KINDS = frozenset(("none", "position", "block", "entity"))
STATES = frozenset(("REQUESTED", "VALIDATED", "WAIT_RESOURCE", "PREPARED", "EXECUTING",
                    "VERIFYING", "CANCEL_PENDING", "IN_DOUBT", "COMPLETED", "CANCELLED", "FAILED"))
EDGES = {
    "REQUESTED": frozenset(("VALIDATED", "CANCELLED", "FAILED")),
    "VALIDATED": frozenset(("PREPARED", "WAIT_RESOURCE", "CANCEL_PENDING", "FAILED")),
    "WAIT_RESOURCE": frozenset(("PREPARED", "CANCEL_PENDING", "FAILED")),
    "PREPARED": frozenset(("EXECUTING", "WAIT_RESOURCE", "CANCEL_PENDING", "FAILED", "IN_DOUBT")),
    "EXECUTING": frozenset(("VERIFYING", "CANCEL_PENDING", "IN_DOUBT", "FAILED")),
    "VERIFYING": frozenset(("COMPLETED", "CANCEL_PENDING", "IN_DOUBT", "FAILED")),
    "CANCEL_PENDING": frozenset(("CANCELLED", "IN_DOUBT", "VERIFYING")),
    "IN_DOUBT": frozenset(("VERIFYING", "FAILED")),
}
PROOF_KINDS = frozenset(("NONE", "VERIFIED_WORLD_RECEIPT", "CANCEL_ACKNOWLEDGED", "TRUSTED_RECONCILIATION"))


def _exact(obj, keys, name):
    if not isinstance(obj, dict) or set(obj) != set(keys):
        raise IdentityError(f"{name} requires exactly {sorted(keys)}")


def _block_xyz(position):
    if (not isinstance(position, list) or len(position) != 3 or
            any(type(n) is not int or abs(n) > 30000000 for n in position)):
        raise IdentityError("target position must be three bounded integer coordinates")
    return list(position)


def validate_action_contract(value):
    """Return canonical wire v1 envelope; do not modify caller's source."""
    required = ("protocol_version", "world_id", "dimension_id", "actor_id", "goal_id",
                "task_id", "action_id", "attempt_id", "skill_id", "target", "expected_outcome")
    _exact(value, required, "C+ action")
    if type(value["protocol_version"]) is not int or value["protocol_version"] != PROTOCOL_VERSION:
        raise IdentityError("unsupported C+ protocol version")
    result = {"protocol_version": PROTOCOL_VERSION}
    for field in UUID_FIELDS:
        result[field] = canonical_uuid(value[field], field)
    if result["action_id"] == result["attempt_id"]:
        raise IdentityError("attempt_id must differ from action_id")
    result["dimension_id"] = dimension_id(value["dimension_id"])
    result["skill_id"] = resource(value["skill_id"], "skill_id")
    target = value["target"]
    if not isinstance(target, dict) or target.get("kind") not in TARGET_KINDS:
        raise IdentityError("target requires a supported kind")
    kind = target["kind"]
    if kind in ("block", "position"):
        _exact(target, ("kind", "position"), "position target")
        result["target"] = {"kind": kind, "position": _block_xyz(target["position"])}
    elif kind == "entity":
        _exact(target, ("kind", "entity_id"), "entity target")
        result["target"] = {"kind": kind, "entity_id": canonical_uuid(target["entity_id"], "entity_id")}
    else:
        _exact(target, ("kind",), "none target")
        result["target"] = {"kind": kind}
    outcome = value["expected_outcome"]
    _exact(outcome, ("kind", "key", "expected_value"), "expected_outcome")
    resource(outcome["kind"], "outcome kind")
    for field, length in (("key", 120), ("expected_value", 240)):
        if not isinstance(outcome[field], str) or not outcome[field].strip() or len(outcome[field]) > length:
            raise IdentityError(f"invalid outcome {field}")
    result["expected_outcome"] = dict(outcome)
    return result


def transition(from_state, to_state, proof_kind="NONE", receipt_id=""):
    """Only transition policy; this function does not persist or authorize anything."""
    if (from_state not in STATES or to_state not in EDGES.get(from_state, ()) or
            proof_kind not in PROOF_KINDS or not isinstance(receipt_id, str) or len(receipt_id) > 160 or
            (proof_kind == "NONE" and receipt_id != "") or
            (proof_kind != "NONE" and not receipt_id.strip())):
        raise IdentityError("invalid action state transition or proof")
    if to_state == "COMPLETED" and proof_kind != "VERIFIED_WORLD_RECEIPT":
        raise IdentityError("success requires trusted server world receipt")
    if to_state == "CANCELLED" and from_state != "REQUESTED" and proof_kind != "CANCEL_ACKNOWLEDGED":
        raise IdentityError("cancel requires terminal acknowledgement")
    if (from_state == "IN_DOUBT" or (from_state == "CANCEL_PENDING" and to_state == "VERIFYING")) and proof_kind != "TRUSTED_RECONCILIATION":
        raise IdentityError("uncertain action must reconcile")
    if to_state == "VERIFYING" and from_state == "EXECUTING" and proof_kind != "NONE":
        raise IdentityError("execution does not imply world verification")
    return to_state


def automatic_retry_allowed(state, has_side_effects):
    if state not in STATES or type(has_side_effects) is not bool:
        raise IdentityError("invalid retry decision")
    return not has_side_effects and state in ("FAILED", "CANCELLED")
