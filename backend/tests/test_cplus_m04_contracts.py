"""D-Lite contract verification; does not claim playable world mutation."""
import unittest
from uuid import uuid4
from npcortex_backend.cplus_m04_contracts import (
    ContractError, STEPS, Witness, Workflow, parse_identity)


def identity():
    return {k: str(uuid4()) for k in (
        "world_id", "actor_id", "session_id", "requester_id", "task_id", "attempt_id")}


def witness(step, effect="NONE"):
    return Witness(step, 1, str(uuid4()), "a" * 64, effect)


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.id = parse_identity(identity())
        self.wf = Workflow(self.id)

    def test_requires_exact_canonical_identity(self):
        raw = identity()
        self.assertEqual(len(parse_identity(raw)), 6)
        raw["extra"] = "new"
        with self.assertRaises(ContractError): parse_identity(raw)
        raw.pop("extra")
        raw["actor_id"] = "invalid"
        with self.assertRaises(ContractError): parse_identity(raw)

    def test_dependency_sequence_and_no_spoof(self):
        with self.assertRaises(ContractError): self.wf.accept(self.id, witness("BREAK"))
        with self.assertRaises(ContractError): self.wf.accept(parse_identity(identity()), witness("OBSERVE"))
        nxt = self.wf.accept(self.id, witness("OBSERVE"))
        self.assertEqual(nxt.state, "ACTIVE")
        with self.assertRaises(ContractError): nxt.accept(self.id, witness("OBSERVE"))

    def test_cannot_claim_unknown_as_completed(self):
        with self.assertRaises(ContractError): self.wf.accept(self.id, witness("OBSERVE", "UNKNOWN"))
        state = self.wf
        for step in STEPS:
            state = state.accept(self.id, witness(step, "VERIFIED"))
        self.assertEqual(state.state, "COMPLETED")
        with self.assertRaises(ContractError): state.hold(self.id, "UNKNOWN")

    def test_partial_effect_is_held_without_step_retry(self):
        state = self.wf.hold(self.id, "PARTIAL")
        self.assertIs(state, state.metadata_retry(self.id))
        with self.assertRaises(ContractError): state.accept(self.id, witness("OBSERVE"))

    def test_invalid_witnesses(self):
        with self.assertRaises(ContractError): Witness("OBSERVE", True, str(uuid4()), "a"*64, "NONE")
        with self.assertRaises(ContractError): Witness("OBSERVE", 0, "bad", "a"*64, "NONE")
        with self.assertRaises(ContractError): Witness("OBSERVE", 0, str(uuid4()), "oops", "NONE")


if __name__ == "__main__":
    unittest.main()
