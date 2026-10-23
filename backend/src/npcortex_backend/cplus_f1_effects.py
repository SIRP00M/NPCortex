"""C-F1 D+ effect evidence policy (contract-only, no world access)."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Observation:
    authoritative: bool
    target_quantity: int
    verified_in_actor_inventory: int
    verified_external_quantity: int
    unaccounted_quantity: int
    evidence_id: str

    def __post_init__(self):
        values = (self.target_quantity, self.verified_in_actor_inventory,
                  self.verified_external_quantity, self.unaccounted_quantity)
        if (type(self.authoritative) is not bool or any(type(x) is not int or x < 0 for x in values)
                or any(x > self.target_quantity for x in values[1:]) or
                sum(values[1:]) > self.target_quantity or
                not isinstance(self.evidence_id, str) or len(self.evidence_id) > 160 or
                (self.authoritative and not self.evidence_id.strip())):
            raise ValueError("invalid observed quantities or evidence")


def resolve_effect(o: Observation):
    """Return (outcome, next_action, credited, remaining, evidence_id). No implicit item creation."""
    if not isinstance(o, Observation):
        raise TypeError("expected Observation")
    if not o.authoritative:
        return ("UNKNOWN", "HOLD_FOR_EVIDENCE", 0, o.target_quantity, "")
    remaining = o.target_quantity - o.verified_in_actor_inventory
    if o.unaccounted_quantity:
        return ("UNKNOWN", "HOLD_FOR_EVIDENCE", o.verified_in_actor_inventory, remaining, o.evidence_id)
    if remaining == 0:
        return ("VERIFIED", "COMPLETE", o.verified_in_actor_inventory, 0, o.evidence_id)
    return (("EXTERNALLY_CONSUMED" if o.verified_external_quantity else "PARTIAL"),
            "REPLAN_REMAINING", o.verified_in_actor_inventory, remaining, o.evidence_id)


def classify_failure(state, effect_may_have_occurred, evidence):
    """Do not turn a possible external effect into final FAILED without no-effect proof."""
    from .cplus_contracts import STATES
    if (state not in STATES or state in ("FAILED", "CANCELLED", "COMPLETED")
            or type(effect_may_have_occurred) is not bool
            or evidence not in ("NONE", "VERIFIED_WORLD_EFFECT", "VERIFIED_NO_EFFECT", "RECONCILED_OBSERVATION")):
        raise ValueError("invalid failure state/evidence")
    if effect_may_have_occurred and evidence != "VERIFIED_NO_EFFECT":
        return "IN_DOUBT"
    return "FAILED"
