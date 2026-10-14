"""B1 identities: a character template is NOT a world actor instance."""
import re
from uuid import UUID

DIMENSION_PATTERN = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]{1,120}$")
PLAYER_PATTERN = re.compile(r"^[a-zA-Z0-9_-]{1,64}$")


class IdentityError(ValueError):
    """Invalid caller-supplied world, actor or participant identity."""


def canonical_uuid(value: object, field: str) -> str:
    if not isinstance(value, str):
        raise IdentityError(f"{field} must be a canonical UUID string")
    try:
        identifier = UUID(value)
    except (ValueError, AttributeError) as exc:
        raise IdentityError(f"{field} must be a canonical UUID string") from exc
    if str(identifier) != value or identifier.int == 0:
        raise IdentityError(f"{field} must be a lowercase canonical non-nil UUID")
    return value


def dimension_id(value: object) -> str:
    if not isinstance(value, str) or not DIMENSION_PATTERN.fullmatch(value):
        raise IdentityError("dimension_id must be a namespaced resource ID, e.g. minecraft:overworld")
    return value


def player_id(value: object) -> str:
    if not isinstance(value, str) or not PLAYER_PATTERN.fullmatch(value):
        raise IdentityError("player_id must be 1-64 letters, digits, _ or -")
    return value


def world_name(value: object) -> str:
    if not isinstance(value, str) or not 1 <= len(value.strip()) <= 80:
        raise IdentityError("world name must be 1-80 characters")
    return value.strip()
