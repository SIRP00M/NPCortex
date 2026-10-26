"""M04 D-Lite: strict, read-only sequential contract validation.

This module exposes NO HTTP route, permission grant or world mutation dispatch.
Actual world effects require a separate server-thread native executor with
proven cancellation, ownership, journal and authoritative observations.
"""
from __future__ import annotations
import re
from dataclasses import dataclass
from uuid import UUID

STEPS = ("OBSERVE", "EQUIP", "NAVIGATE", "REOBSERVE", "BREAK", "VERIFY_BLOCK",
         "OBSERVE_DROP", "MOVE_TO_DROP", "PICKUP", "VERIFY_INVENTORY", "TERMINAL")
EFFECTS = frozenset(("NONE", "VERIFIED", "PARTIAL", "UNKNOWN", "EXTERNALLY_CONSUMED"))
HEX64 = re.compile(r"[0-9a-fA-F]{64}\Z")


class ContractError(ValueError):
    pass


def parse_identity(envelope: dict) -> tuple[str, ...]:
    fields = ("world_id", "actor_id", "session_id", "requester_id", "task_id", "attempt_id")
    if type(envelope) is not dict or set(envelope) != set(fields):
        raise ContractError("exact identity fields required")
    result = []
    for field in fields:
        value = envelope[field]
        if type(value) is not str:
            raise ContractError(f"{field} must be UUID string")
        try:
            parsed = UUID(value)
        except ValueError as exc:
            raise ContractError(f"{field} invalid UUID") from exc
        if str(parsed) != value:
            raise ContractError(f"{field} must use canonical UUID")
        result.append(value)
    return tuple(result)


@dataclass(frozen=True)
class Witness:
    step: str
    tick: int
    observation_id: str
    sha256: str
    effect: str

    def __post_init__(self):
        if self.step not in STEPS or self.effect not in EFFECTS:
            raise ContractError("unknown step or effect")
        if type(self.tick) is not int or self.tick < 0 or not HEX64.fullmatch(self.sha256):
            raise ContractError("unverifiable tick/hash")
        try:
            if str(UUID(self.observation_id)) != self.observation_id:
                raise ValueError("not canonical")
        except ValueError as exc:
            raise ContractError("invalid observation UUID") from exc


@dataclass(frozen=True)
class Workflow:
    identity: tuple[str, ...]
    witnesses: tuple[Witness, ...] = ()
    state: str = "PLANNED"
    effect: str = "NONE"

    def __post_init__(self):
        if len(self.identity) != 6 or self.state not in ("PLANNED", "ACTIVE", "HOLD", "COMPLETED"):
            raise ContractError("invalid state/identity")
        if self.effect not in EFFECTS or len(self.witnesses) > len(STEPS):
            raise ContractError("invalid effect/witness count")
        if any(w.step != STEPS[i] for i, w in enumerate(self.witnesses)):
            raise ContractError("out of order witnesses")
        if self.state == "COMPLETED" and (len(self.witnesses) != len(STEPS) or self.effect != "VERIFIED"):
            raise ContractError("invalid completion")

    def accept(self, identity: tuple[str, ...], witness: Witness) -> "Workflow":
        if identity != self.identity or self.state not in ("PLANNED", "ACTIVE"):
            raise ContractError("foreign identity or fenced task")
        if len(self.witnesses) == len(STEPS) or witness.step != STEPS[len(self.witnesses)]:
            raise ContractError("step dependency missing")
        if witness.effect not in ("NONE", "VERIFIED"):
            raise ContractError("unknown/partial effect must HOLD")
        if witness.step in ("VERIFY_BLOCK", "PICKUP", "VERIFY_INVENTORY", "TERMINAL") and witness.effect != "VERIFIED":
            raise ContractError("inventory custody not verified")
        events = (*self.witnesses, witness)
        terminal = len(events) == len(STEPS)
        return Workflow(self.identity, events, "COMPLETED" if terminal else "ACTIVE",
                        "VERIFIED" if terminal else witness.effect)

    def hold(self, identity: tuple[str, ...], effect: str) -> "Workflow":
        if identity != self.identity or self.state == "COMPLETED" or effect not in EFFECTS:
            raise ContractError("cannot hold terminal or foreign task")
        return Workflow(self.identity, self.witnesses, "HOLD", effect)

    def metadata_retry(self, identity: tuple[str, ...]) -> "Workflow":
        if identity != self.identity:
            raise ContractError("foreign retry")
        return self  # never re-executes a world effect
