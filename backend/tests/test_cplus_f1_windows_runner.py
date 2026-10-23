"""Regression for PowerShell/Python 3.13 diagnostic credential bootstrap.

Runs the same standalone Python file invoked by the PS runner; avoids
PowerShell's platform-dependent unescaped `python -c` quote handling.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts" / "test-cplus-f1-auto-local-auth.ps1"
BOOTSTRAP = ROOT / "scripts" / "cplus-f1-auth-bootstrap-smoke.py"


class WindowsRunnerRegressionTests(unittest.TestCase):
    def test_runner_uses_argument_file_instead_of_inline_quoted_python(self):
        ps = RUNNER.read_text(encoding="utf-8")
        self.assertIn("& python (Join-Path $PSScriptRoot 'cplus-f1-auth-bootstrap-smoke.py')", ps)
        self.assertNotIn("& python -c ", ps)

    def test_bootstrap_python_file_succeeds_and_never_logs_secret(self):
        with tempfile.TemporaryDirectory(prefix="npcortex-cf1-ps-regress-") as directory:
            secret_file = Path(directory) / "bridge.secret"
            env = os.environ.copy()
            env["PYTHONPATH"] = str(ROOT / "backend" / "src")
            env["NPCORTEX_CPLUS_F1_SECRET_FILE"] = str(secret_file)
            env.pop("NPCORTEX_CPLUS_F1_TOKEN", None)
            completed = subprocess.run([sys.executable, str(BOOTSTRAP)],
                                       env=env, capture_output=True, text=True, timeout=20)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertIn("PASS isolated Backend local credential bootstrap", completed.stdout)
            token = secret_file.read_text(encoding="ascii").strip()
            self.assertEqual(len(token), 64)
            self.assertNotIn(token, completed.stdout + completed.stderr)

    def test_invalid_existing_secret_fails_closed_without_rotation(self):
        with tempfile.TemporaryDirectory(prefix="npcortex-cf1-ps-bad-") as directory:
            secret_file = Path(directory) / "bridge.secret"
            secret_file.write_text("partial-not-a-token", encoding="ascii")
            if os.name != "nt":
                secret_file.chmod(0o600)
            env = os.environ.copy()
            env["PYTHONPATH"] = str(ROOT / "backend" / "src")
            env["NPCORTEX_CPLUS_F1_SECRET_FILE"] = str(secret_file)
            env.pop("NPCORTEX_CPLUS_F1_TOKEN", None)
            completed = subprocess.run([sys.executable, str(BOOTSTRAP)],
                                       env=env, capture_output=True, text=True, timeout=20)
            self.assertNotEqual(completed.returncode, 0)
            self.assertEqual(secret_file.read_text(encoding="ascii"), "partial-not-a-token")


if __name__ == "__main__":
    unittest.main()
