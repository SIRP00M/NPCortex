"""C0 capability contracts, not Minecraft implementations.

Forge advertises which registered skills are actually available in a snapshot.
A skill's result must be backed by a *new* observation, not Qwen prose.
"""
import math
from .agent_contracts import block_position, position, resource, observed
from .identity import IdentityError


class SkillRegistry:
    DESCRIPTIONS = {
        "move_to": "Move toward a target world position (verified by post-position).",
        "break_block": "Break one observed non-air block (verified by post-block state).",
        "place_block": "Place an owned block into observed air (verified by post-block state).",
        "pickup_item": "Collect at least one observed nearby dropped item (verified by inventory increase).",
    }

    # Planner-facing contracts describe ONLY currently advertised skills.
    # The actual authority remains prepare() and trusted post-action observations.
    ARGUMENT_SCHEMAS = {
        "move_to": {"type": "object", "properties": {"position": {
            "type": "array", "items": {"type": "number"}, "minItems": 3, "maxItems": 3}},
            "required": ["position"], "additionalProperties": False},
        "break_block": {"type": "object", "properties": {"position": {
            "type": "array", "items": {"type": "integer"}, "minItems": 3, "maxItems": 3}},
            "required": ["position"], "additionalProperties": False},
        "place_block": {"type": "object", "properties": {"position": {
            "type": "array", "items": {"type": "integer"}, "minItems": 3, "maxItems": 3},
            "block_id": {"type": "string"}},
            "required": ["position", "block_id"], "additionalProperties": False},
        "pickup_item": {"type": "object", "properties": {"item_id": {"type": "string"}},
            "required": ["item_id"], "additionalProperties": False},
    }

    @classmethod
    def planner_contract(cls, snapshot):
        """Build a bounded JSON-schema decision union and grounded examples.

        No example can contain a block, item or capability that the trusted
        observation did not provide. These are hints, never a bypass of prepare().
        """
        skills = cls.available(snapshot)
        alternatives = [{
            "type": "object", "properties": {
                "action": {"const": "blocked"}, "reason": {"type": "string", "minLength": 1, "maxLength": 200}},
            "required": ["action", "reason"], "additionalProperties": False}]
        details = {}
        for skill_id in skills:
            alternatives.append({
                "type": "object", "properties": {
                    "action": {"const": "use_skill"}, "skill_id": {"const": skill_id},
                    "args": cls.ARGUMENT_SCHEMAS[skill_id]},
                "required": ["action", "skill_id", "args"], "additionalProperties": False})
            examples = []
            if skill_id == "move_to":
                # A desired destination comes from the goal, not necessarily a
                # currently observed block. Do not invent a destination hint.
                pass
            elif skill_id == "break_block":
                for key, block in snapshot["blocks"].items():
                    if block != "minecraft:air":
                        examples.append({"position": [int(x) for x in key.split(",")]})
                        if len(examples) == 4:
                            break
            elif skill_id == "place_block":
                air = next((key for key, value in snapshot["blocks"].items()
                            if value == "minecraft:air"), None)
                item = next((key for key, count in snapshot["inventory"].items()
                             if count > 0 and key != "minecraft:air"), None)
                if air and item:
                    examples.append({"position": [int(x) for x in air.split(",")], "block_id": item})
            elif skill_id == "pickup_item":
                for drop in snapshot["nearby_drops"][:4]:
                    args = {"item_id": drop["item_id"]}
                    if args not in examples:
                        examples.append(args)
            details[skill_id] = {"description": cls.DESCRIPTIONS[skill_id],
                                 "args_schema": cls.ARGUMENT_SCHEMAS[skill_id],
                                 "observed_argument_examples": examples}
        return {"type": "object", "oneOf": alternatives}, details

    @classmethod
    def available(cls, snapshot):
        # The runtime cannot grant an unadvertised Forge capability.
        return [skill for skill in snapshot["available_skills"] if skill in cls.DESCRIPTIONS]

    @classmethod
    def prepare(cls, skill_id, args, before, goal_criteria=None):
        if skill_id not in cls.available(before):
            raise IdentityError("skill is not supported or was not advertised by the sensor")
        if not isinstance(args, dict):
            raise IdentityError("skill args must be an object")
        if skill_id == "move_to" and set(args) == {"position"}:
            target = position(args["position"])
            # For coordinate-goal C2, use the goal's own accepted radius for both
            # preconditions and post-action verification. Legacy C0 goals retain
            # the original default when no criterion matches the action target.
            radius = 1.5
            if goal_criteria is not None:
                matching = [c["distance"] for c in goal_criteria
                            if c.get("kind") == "position_near"
                            and math.dist(c["position"], target) <= 0.05]
                if matching:
                    radius = min(matching)
            expected = {"kind": "position_near", "position": target, "distance": radius}
            if observed(expected, before):
                raise IdentityError("already at movement target")
            return {"position": target}, expected
        if skill_id == "break_block" and set(args) == {"position"}:
            target = block_position(args["position"])
            key = ",".join(map(str, target))
            if before["blocks"].get(key) in (None, "minecraft:air"):
                raise IdentityError("block to break must be observed and non-air")
            return {"position": target}, {"kind": "block_is", "position": target, "block_id": "minecraft:air"}
        if skill_id == "place_block" and set(args) == {"position", "block_id"}:
            target = block_position(args["position"])
            block = resource(args["block_id"], "block_id")
            key = ",".join(map(str, target))
            if before["blocks"].get(key) != "minecraft:air" or before["inventory"].get(block, 0) < 1:
                raise IdentityError("placement needs observed air and available block item")
            if block == "minecraft:air":
                raise IdentityError("cannot place air")
            return {"position": target, "block_id": block}, {"kind": "block_is", "position": target, "block_id": block}
        if skill_id == "pickup_item" and set(args) == {"item_id"}:
            item = resource(args["item_id"], "item_id")
            if not any(d["item_id"] == item and d["count"] >= 1 for d in before["nearby_drops"]):
                raise IdentityError("pickup requires an observed nearby item drop")
            previous = before["inventory"].get(item, 0)
            return {"item_id": item}, {"kind": "inventory_at_least", "item_id": item, "count": previous + 1}
        raise IdentityError("unsupported skill arguments")
