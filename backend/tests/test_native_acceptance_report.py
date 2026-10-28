"""The Stage 4 gameplay-acceptance collector (scripts/native_acceptance_report.py) and the safety rules of its PowerShell wrapper.

The ledger side uses the REAL NativeEffectStore (typed receipts through reserve/start/finalize/hold); the game side is a synthetic
latest.log. Offline evidence only: it proves the verdict rules fail closed, not that a real game produced the lines.
"""
import hashlib
import importlib.util
import json
import os
import shutil
import sqlite3
import subprocess
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from test_cplus_wn_store import block_target, break_evidence, ident, item_target, pickup_evidence, receipt, reserve_body, uid
from npcortex_backend.cplus_e_graph import GraphConflict
from npcortex_backend.cplus_wn_store import NativeEffectStore

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('native_acceptance_report', ROOT / 'scripts' / 'native_acceptance_report.py')
rep = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rep)

ANCHOR = (100.5, 64.0, 200.5)
ANCHOR_LINE = '[10:00:00] [Render thread/INFO]: [CHAT] [NPCortex native] anchor (SW corner, feet level): [100.5d, 64.0d, 200.5d]'


def cell(dx, dz, dy=0):
    return block_target(int(ANCHOR[0] + dx), int(ANCHOR[1] + dy), int(ANCHOR[2] + dz))


class Harness(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.db = self.tmp / 'native.sqlite3'
        self.store = NativeEffectStore(self.db)
        self.world, self.session = uid(), uid()
        self.store.bind({'schema_version': 1, 'world_id': self.world, 'folder_key': 'Native-0123456789abcdef', 'epoch': 0,
                         'session_id': self.session, 'contract': 'native-effects-v1'})
        self.lines = [ANCHOR_LINE]

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def chat(self, *texts):
        self.lines += [f'[10:00:01] [Render thread/INFO]: [CHAT] {t}' for t in texts]

    def reserve(self, kind='break_block', target=None, actor=None):
        b = reserve_body(self.world, self.session, kind, actor=actor, target=target)
        self.store.reserve(b)
        return b

    def run_break(self, target, actor=None, result='EFFECT_VERIFIED'):
        b = self.reserve('break_block', target, actor)
        self.store.start(ident(b, 'requester_id'))
        if result == 'EFFECT_VERIFIED':
            self.store.finalize(receipt(b))
        return b

    def run_pickup(self, evidence=None):
        b = self.reserve('pickup_item', item_target(x=ANCHOR[0] + 22.5, y=64.1, z=ANCHOR[2] + 6.5))
        self.store.start(ident(b, 'requester_id'))
        self.store.finalize(receipt(b, evidence=pickup_evidence(b, **(evidence or {}))))
        return b

    def report(self):
        log = self.tmp / 'latest.log'
        log.write_text('\n'.join(self.lines), encoding='utf-8')
        ev = rep.Evidence(self.db, [log])
        return {r['id']: r for r in rep.evaluate(ev)}, ev

    def status(self, sid):
        return self.report()[0][sid]['status']


class Verdicts(Harness):
    def test_an_empty_ledger_is_not_tested_everywhere(self):
        rows, _ = self.report()
        self.assertEqual({r['status'] for r in rows.values()}, {rep.NOT_TESTED})
        self.assertEqual(rep.overall(list(rows.values())), rep.NOT_TESTED)

    def test_a_database_without_native_tables_is_not_tested_not_a_crash(self):
        empty = self.tmp / 'empty.sqlite3'
        sqlite3.connect(empty).close()
        log = self.tmp / 'l.log'
        log.write_text(ANCHOR_LINE, encoding='utf-8')
        rows = rep.evaluate(rep.Evidence(empty, [log]))
        self.assertEqual({r['status'] for r in rows}, {rep.NOT_TESTED})

    def test_a1_needs_the_ledger_and_the_world(self):
        self.run_break(cell(4, 6))
        self.assertEqual(self.status('A1'), rep.INCONCLUSIVE, 'ledger alone is not enough')
        self.chat('S1 PASS: the log is gone')
        rows, _ = self.report()
        self.assertEqual(rows['A1']['status'], rep.PASS)
        self.assertTrue(any('removed' in n or 'EFFECT_VERIFIED' in n for n in rows['A1']['evidence'] + [rows['A1']['notes']]))

    def test_a1_world_alone_or_contradicting_world_is_never_pass(self):
        self.chat('S1 PASS: the log is gone')
        self.assertEqual(self.status('A1'), rep.INCONCLUSIVE, 'a log gone without a ledger row cannot be attributed')
        self.run_break(cell(4, 6))
        self.chat('S1 FAIL: the log is still there (or something fell into it)')
        self.assertEqual(self.status('A1'), rep.FAIL, 'the LAST world line wins')

    def test_a1_a_refused_break_is_a_typed_failure_not_a_pass(self):
        b = self.reserve('break_block', cell(4, 6))
        self.store.start(ident(b, 'requester_id'))
        ev = break_evidence(b, destroy_returned=False, after_block=b['target']['block_id'])
        for k in ('tool_before', 'tool_after', 'tool_broke', 'drops', 'collateral', 'journal'):
            ev.pop(k)
        self.store.finalize(receipt(b, 'NO_EFFECT', 'BREAK_REFUSED', evidence=ev))
        self.chat('S1 FAIL: the log is still there (or something fell into it)')
        self.assertEqual(self.status('A1'), rep.FAIL)

    def test_a2_needs_a_fully_verified_pickup_and_the_in_game_report(self):
        self.run_pickup()
        self.assertEqual(self.status('A2'), rep.INCONCLUSIVE)
        self.chat('[NPCortex] Pickup complete: EFFECT_VERIFIED (4 item(s) moved into the inventory).')
        self.assertEqual(self.status('A2'), rep.PASS)

    def test_a2_partial_is_a_valid_typed_result_but_not_a_pass(self):
        b = self.reserve('pickup_item', item_target(x=ANCHOR[0] + 22.5, y=64.1, z=ANCHOR[2] + 6.5))
        self.store.start(ident(b, 'requester_id'))
        self.store.finalize(receipt(b, 'EFFECT_PARTIAL', evidence=pickup_evidence(b, delta=2, event_count=0, entity_count_before=4, entity_count_after=2, post_count=2)))
        self.chat('[NPCortex] Pickup complete: EFFECT_PARTIAL')
        self.assertEqual(self.status('A2'), rep.INCONCLUSIVE)

    def test_a3_exactly_one_claimant_and_the_refusal_are_both_required(self):
        target = cell(13, 6)
        first = self.reserve('break_block', target)
        with self.assertRaises(GraphConflict):                                    # the real ledger refuses the second claimant
            self.reserve('break_block', target)
        self.store.start(ident(first, 'requester_id'))
        self.store.finalize(receipt(first))
        self.chat('S3 PASS: the contested log is gone')
        self.assertEqual(self.status('A3'), rep.INCONCLUSIVE, 'no refusal evidence for the second NPC in the game log')
        self.chat('[NPCortex] Backend refused the reservation: CLAIM_CONFLICT: that block is already claimed by another native action')
        self.assertEqual(self.status('A3'), rep.PASS)

    def test_a3b_both_adjacent_logs(self):
        self.run_break(cell(8, 6))
        self.chat('S2a PASS: first log gone', 'S2b PASS: second log gone')
        self.assertEqual(self.status('A3b'), rep.INCONCLUSIVE, 'only one of two logs is verified')
        self.run_break(cell(9, 6))
        self.assertEqual(self.status('A3b'), rep.PASS)

    def test_a4_needs_three_refusal_kinds_and_no_stuck_attempt(self):
        self.chat('BLOCKED: a native world action owns this NPC\'s inventory; wait for it')
        self.assertEqual(self.status('A4'), rep.INCONCLUSIVE)
        self.chat('C2-F1 refused: a native world action owns this NPC', 'Native world action active; motion refused until it is settled')
        self.assertEqual(self.status('A4'), rep.PASS)
        self.reserve('break_block', cell(30, 3))                                  # left RESERVED
        self.assertEqual(self.status('A4'), rep.INCONCLUSIVE)

    def test_a5_rejoin_hold_is_the_owners_decision_not_a_failure(self):
        b = self.run_break(cell(4, 6), result='HOLD')
        self.store.hold(dict(ident(b, 'requester_id'), reason='EXECUTOR_EXCEPTION', intended={}))
        self.assertEqual(self.status('A5'), rep.NOT_TESTED)
        self.store.bind({'schema_version': 1, 'world_id': self.world, 'folder_key': 'Native-0123456789abcdef', 'epoch': 1,
                         'session_id': uid(), 'contract': 'native-effects-v1'})
        self.assertEqual(self.status('A5'), rep.INCONCLUSIVE)

    def test_a6_tiers_and_the_row_count(self):
        z = int(ANCHOR[2] + 12)
        for i in range(5):
            self.run_break(block_target(int(ANCHOR[0]) + 2 + 2 * i, 64, z))
        rows, _ = self.report()
        self.assertEqual(rows['A6']['status'], rep.INCONCLUSIVE)
        self.assertIn('[1, 3, 5]', rows['A6']['notes'])
        self.assertIn('NOT an MSPT', rows['A6']['notes'])
        for i in range(5, 10):
            self.run_break(block_target(int(ANCHOR[0]) + 2 + 2 * i, 64, z))
        self.chat('S8: logs gone: 10 / 10 (expected 10)')
        self.assertEqual(self.status('A6'), rep.PASS)

    def test_a7_hold_and_held_claim_are_reported_never_cleared(self):
        b = self.run_break(cell(4, 6), result='HOLD')
        self.store.hold(dict(ident(b, 'requester_id'), reason='EXECUTOR_EXCEPTION', intended={}))
        rows, ev = self.report()
        self.assertEqual(rows['A7']['status'], rep.INCONCLUSIVE)
        self.assertIn('HELD', rows['A7']['notes'])
        with closing(sqlite3.connect(self.db)) as db:
            self.assertEqual(db.execute('SELECT state FROM cplus_wn_claims').fetchall(), [('HELD',)], 'the collector left the claim alone')

    def test_a7_a_leaked_claim_is_a_failure(self):
        b = self.run_break(cell(4, 6))
        with closing(sqlite3.connect(self.db)) as db, db:
            db.execute("UPDATE cplus_wn_claims SET state='ACTIVE' WHERE task_id=?", (b['task_id'],))
        self.assertEqual(self.status('A7'), rep.FAIL)

    def test_a7_typed_failures_with_nothing_unresolved_pass(self):
        b = self.reserve('break_block', cell(4, 6))
        self.store.start(ident(b, 'requester_id'))
        self.store.finalize(receipt(b, 'PRECONDITION_FAILED', 'OUT_OF_REACH', executed=False, evidence={}))
        self.assertEqual(self.status('A7'), rep.PASS)

    def test_a0_denied_targets(self):
        self.assertEqual(self.status('A0'), rep.NOT_TESTED)
        self.chat('[NPCortex] That block is not on the allow-list')
        self.assertEqual(self.status('A0'), rep.INCONCLUSIVE)
        self.chat('DENY-diamond PASS: the unlisted block is untouched', 'DENY-chest PASS: the chest is untouched')
        self.assertEqual(self.status('A0'), rep.PASS)
        self.run_break(cell(34, 6))
        self.assertEqual(self.status('A0'), rep.FAIL, 'a ledger row for a denied block')

    def test_overall_is_pass_only_when_every_scenario_passes(self):
        mixed = [{'status': rep.PASS}, {'status': rep.NOT_TESTED}]
        self.assertEqual(rep.overall(mixed), rep.INCONCLUSIVE)
        self.assertEqual(rep.overall(mixed + [{'status': rep.FAIL}]), rep.FAIL)
        self.assertEqual(rep.overall([{'status': rep.PASS}]), rep.PASS)


class SafetyAndCli(Harness):
    def sha(self):
        return hashlib.sha256(self.db.read_bytes()).hexdigest()

    def test_the_collector_never_writes_the_database(self):
        self.run_break(cell(4, 6))
        before = self.sha()
        self.chat('S1 PASS: the log is gone')
        self.report()
        self.assertEqual(self.sha(), before)

    def test_the_collector_cannot_write_even_if_asked(self):
        ev_db = rep.sqlite3.connect(self.db.resolve().as_uri() + '?mode=ro', uri=True)
        with self.assertRaises(sqlite3.OperationalError):
            ev_db.execute("UPDATE cplus_wn_claims SET state='RELEASED'")
        ev_db.close()

    def test_cli_fails_closed_without_inputs(self):
        log = self.tmp / 'x.log'
        log.write_text('\n'.join(self.lines), encoding='utf-8')
        self.assertEqual(rep.main(['--db', str(self.tmp / 'missing.sqlite3'), '--log', str(log)]), 2)
        self.assertEqual(rep.main(['--db', str(self.db), '--log', str(self.tmp / 'missing.log')]), 2)
        no_anchor = self.tmp / 'n.log'
        no_anchor.write_text('nothing here', encoding='utf-8')
        self.assertEqual(rep.main(['--db', str(self.db), '--log', str(no_anchor)]), 2)
        self.assertEqual(rep.main(['--db', str(self.db), '--log', str(no_anchor), '--anchor', '1,2']), 2)

    def test_cli_exit_code_and_json(self):
        self.run_break(cell(4, 6))
        self.chat('S1 FAIL: the log is still there (or something fell into it)')
        log = self.tmp / 'x.log'
        log.write_text('\n'.join(self.lines), encoding='utf-8')
        out = self.tmp / 'r.json'
        self.assertEqual(rep.main(['--db', str(self.db), '--log', str(log), '--json', str(out)]), 1)
        data = json.loads(out.read_text(encoding='utf-8'))
        self.assertEqual((data['overall'], len(data['scenarios'])), (rep.FAIL, 9))


class PowerShellWrapper(unittest.TestCase):
    """Fail-closed checks of scripts/native-acceptance.ps1 that need no game, no backend and touch no config."""

    def run_ps(self, *args):
        return subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(ROOT / 'scripts' / 'native-acceptance.ps1'), *args],
                              capture_output=True, text=True, timeout=60)

    def test_the_normal_backend_port_is_refused(self):
        r = self.run_ps('-Action', 'Start', '-Port', '8765', '-RunDir', tempfile.mkdtemp())
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('8765', r.stdout + r.stderr)

    def test_actions_other_than_start_need_a_session(self):
        d = tempfile.mkdtemp()
        for action in ('Client', 'Report', 'Stop', 'Allow', 'Status'):
            r = self.run_ps('-Action', action, '-RunDir', d)
            self.assertNotEqual(r.returncode, 0, action)
            self.assertIn('No acceptance session', r.stdout + r.stderr, action)

    def test_install_refuses_a_world_without_native_in_its_name(self):
        d = Path(tempfile.mkdtemp())
        (d / 'state.json').write_text('{"pid":1,"port":18765,"db":"x","secret":"y","hadToml":false,"startTicks":0,"started":"2026-01-01"}')
        r = self.run_ps('-Action', 'Install', '-WorldName', 'MyRealWorld', '-RunDir', str(d))
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('Native', r.stdout + r.stderr)

    def test_stop_never_kills_a_reused_pid_and_keeps_the_evidence(self):
        if (ROOT / 'forge-mod' / 'run' / 'config' / 'npcortex-common.toml.acceptance-bak').exists():
            self.skipTest('a real acceptance session is active: Stop would restore its config')
        d = Path(tempfile.mkdtemp())
        (d / 'native.sqlite3').write_bytes(b'evidence')
        (d / 'state.json').write_text(json.dumps({'pid': os.getpid(), 'port': 18765, 'db': str(d / 'native.sqlite3'), 'secret': 'y', 'hadToml': True,
                                                  'startTicks': 1, 'started': '2026-01-01T00:00:00'}))
        r = self.run_ps('-Action', 'Stop', '-RunDir', str(d))
        text = r.stdout + r.stderr
        self.assertIn('different process', text)                                   # this test process has another start time than recorded
        self.assertEqual((d / 'native.sqlite3').read_bytes(), b'evidence')
        self.assertFalse((d / 'state.json').exists(), 'the session ended (state archived, not deleted)')
        self.assertTrue(list(d.glob('state-ended-*.json')))


if __name__ == '__main__':
    unittest.main()
