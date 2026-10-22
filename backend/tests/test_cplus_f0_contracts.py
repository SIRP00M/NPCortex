"""C-F0 contract-only regression. Intentionally does not run any world mutation."""
import unittest

from npcortex_backend.cplus_contracts import (
    validate_action_contract, transition, automatic_retry_allowed, PROTOCOL_VERSION)
from npcortex_backend.identity import IdentityError


def request():
    return {
        "protocol_version": 1,
        "world_id": "11111111-1111-4111-8111-111111111111",
        "dimension_id": "minecraft:overworld",
        "actor_id": "22222222-2222-4222-8222-222222222222",
        "goal_id": "33333333-3333-4333-8333-333333333333",
        "task_id": "44444444-4444-4444-8444-444444444444",
        "action_id": "55555555-5555-4555-8555-555555555555",
        "attempt_id": "66666666-6666-4666-8666-666666666666",
        "skill_id": "minecraft:use_block",
        "target": {"kind": "block", "position": [120, 65, -84]},
        "expected_outcome": {"kind": "npcortex:block_state", "key": "open", "expected_value": "true"},
    }


class CPlusF0ContractTests(unittest.TestCase):
    def assert_rejected(self, obj):
        with self.assertRaises(IdentityError):
            validate_action_contract(obj)

    def test_wire_schema_roundtrip(self):
        r = request()
        self.assertEqual(PROTOCOL_VERSION, 1)
        self.assertEqual(validate_action_contract(r), r)
        self.assertEqual(validate_action_contract(r)["target"]["position"], [120, 65, -84])

    def test_old_or_future_protocol_rejected(self):
        for version in (0, 2, True, "1"):
            with self.subTest(version=version):
                r = request(); r["protocol_version"] = version
                self.assert_rejected(r)

    def test_unknown_field_rejected(self):
        r = request(); r["debug_admin_override"] = True
        self.assert_rejected(r)
        r = request(); del r["action_id"]
        self.assert_rejected(r)

    def test_invalid_uuid_identity_rejected(self):
        for value in ("00000000-0000-0000-0000-000000000000", "NOT-UUID", None, "abcdefab-abcd-4abc-8abc-abcdefabcdef".upper()):
            with self.subTest(value=value):
                r = request(); r["world_id"] = value
                self.assert_rejected(r)

    def test_attempt_id_distinct_from_action_id(self):
        r = request(); r["attempt_id"] = r["action_id"]
        self.assert_rejected(r)

    def test_dimension_and_skill_strict(self):
        for field, value in (("dimension_id", "minecraft:OVERWORLD"), ("skill_id", "bad-id")):
            r = request(); r[field] = value
            self.assert_rejected(r)

    def test_bad_positions_fail_closed(self):
        for coords in ([True, 65, 0], [1.2, 65, 0], [40000000, 65, 0], [1, 2], None):
            with self.subTest(coords=coords):
                r = request(); r["target"]["position"] = coords
                self.assert_rejected(r)

    def test_target_variant_contracts(self):
        r = request(); r["target"] = {"kind": "none"}
        self.assertEqual(validate_action_contract(r)["target"], {"kind": "none"})
        r["target"] = {"kind": "entity", "entity_id": r["actor_id"]}
        self.assertEqual(validate_action_contract(r)["target"]["kind"], "entity")
        r["target"]["position"] = [0, 64, 0]
        self.assert_rejected(r)
        r["target"] = {"kind": "position", "position": [1, 63, 1]}
        self.assertEqual(validate_action_contract(r)["target"]["kind"], "position")

    def test_expected_outcome_is_specification_not_receipt(self):
        r = request(); r["expected_outcome"]["expected_value"] = ""
        self.assert_rejected(r)
        r = request(); r["expected_outcome"]["kind"] = "UNKNOWN"
        self.assert_rejected(r)

    def test_success_requires_verified_proof(self):
        for kind in ("NONE", "CANCEL_ACKNOWLEDGED", "TRUSTED_RECONCILIATION"):
            with self.assertRaises(IdentityError):
                transition("VERIFYING", "COMPLETED", kind, "" if kind == "NONE" else "receipt-1")
        self.assertEqual(transition("VERIFYING", "COMPLETED", "VERIFIED_WORLD_RECEIPT", "receipt-1"), "COMPLETED")

    def test_uncertain_requires_reconciliation(self):
        with self.assertRaises(IdentityError): transition("IN_DOUBT", "VERIFYING")
        with self.assertRaises(IdentityError): transition("CANCEL_PENDING", "VERIFYING")
        self.assertEqual(transition("IN_DOUBT", "VERIFYING", "TRUSTED_RECONCILIATION", "world-1"), "VERIFYING")

    def test_cancel_requires_acknowledgement(self):
        with self.assertRaises(IdentityError): transition("CANCEL_PENDING", "CANCELLED")
        self.assertEqual(transition("CANCEL_PENDING", "CANCELLED", "CANCEL_ACKNOWLEDGED", "terminal-1"), "CANCELLED")

    def test_terminal_has_no_reexecution(self):
        for state in ("COMPLETED", "CANCELLED", "FAILED"):
            with self.assertRaises(IdentityError): transition(state, "EXECUTING")

    def test_execution_is_not_verification(self):
        with self.assertRaises(IdentityError):
            transition("EXECUTING", "VERIFYING", "VERIFIED_WORLD_RECEIPT", "fake")
        self.assertEqual(transition("EXECUTING", "VERIFYING"), "VERIFYING")

    def test_no_automatic_side_effect_retries(self):
        self.assertFalse(automatic_retry_allowed("IN_DOUBT", False))
        self.assertFalse(automatic_retry_allowed("FAILED", True))
        self.assertTrue(automatic_retry_allowed("FAILED", False))
        with self.assertRaises(IdentityError): automatic_retry_allowed("FAILED", "false")


if __name__ == "__main__":
    unittest.main()
