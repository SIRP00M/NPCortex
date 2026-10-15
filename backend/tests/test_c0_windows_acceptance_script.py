"""Static guardrails for the Windows C0 acceptance assertion script.

These do not execute PowerShell; native Windows PowerShell verification remains
an explicit release gate.
"""

import re
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "test-c0-headless.ps1"


class C0WindowsAcceptanceScriptTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT.read_text(encoding="utf-8")
        cls.verify_block = cls.source.split("if ($VerifyGoalId -ne '') {", 1)[1].split("exit 0", 1)[0]

    def test_single_match_status_filters_are_materialized_as_arrays(self):
        for name, status in (("unverifiedActions", "unverified"), ("succeededActions", "succeeded")):
            with self.subTest(status=status):
                self.assertIn(
                    f"${name} = @($actions | Where-Object {{ $_.status -eq '{status}' }})",
                    self.verify_block,
                )
                self.assertIn(f"Check (${name}.Count -eq 1)", self.verify_block)

    def test_legacy_pipeline_scalar_count_assertions_are_absent(self):
        self.assertNotRegex(
            self.verify_block,
            r"\(\$saved\.actions\s*\|\s*Where-Object\b[^\n]+\)\.Count",
        )
        self.assertIn("$actions = @($saved.actions)", self.verify_block)
        self.assertIn("Check ($actions.Count -eq 2)", self.verify_block)

    def test_action_status_diagnostics_available_when_counts_disagree(self):
        self.assertRegex(
            self.verify_block,
            r"if \(\$actions\.Count -ne 2 -or \$unverifiedActions\.Count -ne 1 -or \$succeededActions\.Count -ne 1\)",
        )
        self.assertIn("Select-Object action_id, skill_id, status, verdict", self.verify_block)
        self.assertIn("Action ledger diagnostics:", self.verify_block)


if __name__ == "__main__":
    unittest.main()
