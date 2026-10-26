"""Non-Forge safety/contract regression for the one-command offline M04 runner."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from m04_auto_accept import (Gate, run_gate, held, outcome, make_environment,
                             create_report_zip, redact, summary_markdown)


class M04AutoAcceptTests(unittest.TestCase):
    def test_outcome_fail_closed(self):
        self.assertEqual(outcome([held('skip', 'Skipped', 'no world')]), 'HOLD')
        self.assertEqual(outcome([{'status': 'PASS'}]), 'PASS_AUTOMATED_ONLY')
        self.assertEqual(outcome([{'status': 'HOLD'}, {'status': 'FAIL'}]), 'FAIL')

    def test_no_shell_injection_and_log_capture(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            logdir = root / 'logs'
            logdir.mkdir()
            result = run_gate(Gate('safe', 'non-shell invocation', [[sys.executable, '-c',
                              "print('PASS literal && command not executed')"]]),
                              root, make_environment(root), logdir)
            self.assertEqual(result['status'], 'PASS')
            self.assertIn('&& command not executed', (logdir / 'safe.log').read_text())

    def test_fail_and_missing_tool_not_pass(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            fail = run_gate(Gate('fail', 'expected', [[sys.executable, '-c', 'import sys;sys.exit(3)']]),
                            root, make_environment(root), root)
            self.assertEqual(fail['status'], 'FAIL')
            self.assertIn('exit code 3', fail['reason'])
            missing = run_gate(Gate('missing', 'expected', [['does-not-exist-npcortex-runner-123']]),
                               root, make_environment(root), root)
            self.assertEqual(missing['status'], 'HOLD')

    def test_pythonpath_propogates_to_child(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            env = make_environment(root)
            self.assertTrue(env['PYTHONPATH'].startswith(str(root / 'backend/src')))
            self.assertIn(str(root / 'backend/tests'), env['PYTHONPATH'])

    def test_bounded_logs_redacted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            result = run_gate(Gate('secret', 'fake secret', [[sys.executable, '-c',
                  "print('Authorization: Bearer EXAMPLESECRET'); print('password=EXAMPLE2')"]]),
                  root, make_environment(root), root)
            data = (root / 'secret.log').read_text()
            self.assertEqual(result['status'], 'PASS')
            self.assertNotIn('EXAMPLESECRET', data)
            self.assertNotIn('EXAMPLE2', data)
            self.assertNotIn('EXAMPLESECRET', redact('Bearer EXAMPLESECRET'))

    def test_report_zip_cannot_include_world_db(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / 'results'
            root.mkdir()
            (root / 'logs').mkdir()
            (root / 'logs' / 'S01.log').write_text('OK')
            (root / 'summary.json').write_text(json.dumps({'automated_result': 'HOLD'}))
            archive = root.with_suffix('.zip')
            result = create_report_zip(root, archive)
            self.assertEqual(len(result), 64)
            with zipfile.ZipFile(archive) as z:
                self.assertEqual(set(z.namelist()), {'logs/S01.log', 'summary.json'})
                self.assertIsNone(z.testzip())
            with self.assertRaises(FileExistsError):
                create_report_zip(root, archive)

    def test_markdown_labels_live_gameplay_hold(self):
        report = {'automated_result': 'PASS_AUTOMATED_ONLY',
                  'gates': [{'id': 'S01', 'label': 'test', 'status': 'PASS', 'reason': None}]}
        m = summary_markdown(report)
        self.assertIn('HOLD — NOT TESTED', m)
        self.assertNotIn('GameTest PASS', m)


if __name__ == '__main__':
    unittest.main()
