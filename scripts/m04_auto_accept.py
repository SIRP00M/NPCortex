#!/usr/bin/env python3
"""Single-command, local, non-mutating NPCortex M04 acceptance orchestrator.

Only invokes source/contract/backend/Forge build tests and an optional read-only
world+SQLite evidence REVIEW.  It never starts Minecraft, rewrites a world or DB,
changes running services, invokes mutation commands, or claims gameplay PASS.

All output is a newly-created timestamped folder OUTSIDE the repository by
default.  The ZIP contains the report and test logs, not raw world/DB files.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def timestamp():
    return datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')


def write_json(path: Path, data):
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def redact(text: str) -> str:
    """Defensive log redaction; do not copy real app config or secrets into ZIP."""
    text = re.sub(r'(?i)(\b(?:authorization|x-npcortex-cplus-token)\s*[:=]\s*)(?:bearer\s+)?[^\s,;]+',
                  r'\1[REDACTED]', text)
    text = re.sub(r'(?i)(\b(?:api[_-]?key|secret|password)\s*[:=]\s*)[^\s,;]+',
                  r'\1[REDACTED]', text)
    text = re.sub(r'(?i)(bearer\s+)\S+', r'\1[REDACTED]', text)
    return text


@dataclass
class Gate:
    id: str
    label: str
    commands: list[list[str]]
    timeout: int = 240


def run_gate(gate: Gate, cwd: Path, env: dict, log_directory: Path) -> dict:
    start = time.monotonic()
    log_name = f'{gate.id}.log'
    log_path = log_directory / log_name
    status = 'PASS'
    reason = None
    with log_path.open('w', encoding='utf-8', errors='replace') as out:
        for command_index, command in enumerate(gate.commands, 1):
            # Args are constructed by this module, not from shell evaluation.
            out.write(f'--- {gate.label}: command {command_index}/{len(gate.commands)} ---\n')
            out.flush()
            try:
                # Always enforce an upper bound; no process or shell adoption.
                with tempfile.TemporaryFile(mode='w+t', encoding='utf-8', errors='replace') as captured:
                    result = subprocess.run(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                                            stdout=captured, stderr=subprocess.STDOUT,
                                            text=True, timeout=gate.timeout, shell=False, check=False)
                    captured.seek(0)
                    for part in iter(lambda: captured.read(64 * 1024), ''):
                        out.write(redact(part))
                    if result.returncode:
                        status = 'FAIL'
                        reason = f'exit code {result.returncode} (command {command_index})'
            except FileNotFoundError as exc:
                status = 'HOLD'
                reason = 'missing test tool: ' + Path(exc.filename or command[0]).name
            except PermissionError as exc:
                status = 'HOLD'
                reason = 'test tool or output directory inaccessible: ' + type(exc).__name__
            except subprocess.TimeoutExpired:
                status = 'FAIL'
                reason = f'command {command_index} exceeded {gate.timeout}s (subprocess terminated)'
            except OSError as exc:
                status = 'FAIL'
                reason = f'cannot execute test: {type(exc).__name__}'
            if status != 'PASS':
                out.write(f'\n--- {status}: {reason} ---\n')
                break
    return {'id': gate.id, 'label': gate.label, 'status': status, 'reason': reason,
            'duration_seconds': round(time.monotonic() - start, 2), 'log': f'logs/{log_name}'}


def held(id: str, label: str, reason: str) -> dict:
    return {'id': id, 'label': label, 'status': 'HOLD', 'reason': reason, 'duration_seconds': 0, 'log': None}


def make_environment(root: Path) -> dict:
    env = os.environ.copy()
    # Required by nested -c subprocess in the C-F1 crash boundary test (also in CI).
    paths = [str(root / 'backend' / 'src'), str(root / 'backend' / 'tests')]
    if env.get('PYTHONPATH'):
        paths.append(env['PYTHONPATH'])
    env['PYTHONPATH'] = os.pathsep.join(paths)
    env['PYTHONDONTWRITEBYTECODE'] = '1'
    return env


def pure_gates(root: Path) -> list[Gate]:
    python = sys.executable
    scripts = root / 'scripts'
    backend = root / 'backend/tests'
    java_package = root / 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
    return [
        Gate('S01-boundaries', 'M04 mutation guard / source boundaries',
             [[python, '-B', str(scripts / 'test_m04_boundaries.py')]], 45),
        Gate('S02-option-d', 'Option D datapack and storage static guard',
             [[python, '-B', str(scripts / 'test_m04_option_d.py')]], 45),
        Gate('S03-m04-contracts', 'M04 typed contract / store backend tests',
             [[python, '-B', '-m', 'unittest', 'discover', '-s', str(backend),
               '-p', 'test_cplus_m04*.py', '-v']], 120),
        Gate('S04-reviewer', 'M04 evidence reviewer regression fixtures',
             [[python, '-B', '-m', 'unittest', 'discover', '-s', str(backend),
               '-p', 'test_m04_acceptance_review.py', '-v']], 60),
        Gate('S05-runner', 'Orchestrator fail-closed self-tests',
             [[python, '-B', '-m', 'unittest', 'discover', '-s', str(scripts),
               '-p', 'test_m04_auto_accept.py', '-v']], 60),
    ]


def java_contract_gate(root: Path, classes: Path) -> Gate:
    p = root / 'forge-mod/src/main/java/io/github/sirp00m/npcortex/cplus/CPlusM04Contract.java'
    test = root / 'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusM04ContractSelfTest.java'
    return Gate('S06-java-contract', 'Java 17 M04 contract (not Forge gameplay)', [
        ['javac', '--release', '17', '-d', str(classes), str(p), str(test)],
        ['java', '-cp', str(classes), 'io.github.sirp00m.npcortex.CPlusM04ContractSelfTest'],
    ], 120)


def backend_gate(root: Path) -> Gate:
    return Gate('S07-backend-all', 'Full backend regression suite', [
        [sys.executable, '-B', '-m', 'unittest', 'discover',
         '-s', str(root / 'backend/tests'), '-v']
    ], 420)


def forge_gate(root: Path) -> Gate:
    wrapper = root / 'forge-mod' / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    if os.name == 'nt':
        command = ['cmd.exe', '/d', '/c', str(wrapper), 'build', 'verifyM04Contract',
                   'verifyTransportE', 'verifyTransportOutbox', 'verifyG2Identity',
                   'verifyG3TerminalContract', '--console=plain']
    else:
        command = ['bash', str(wrapper), 'build', 'verifyM04Contract',
                   'verifyTransportE', 'verifyTransportOutbox', 'verifyG2Identity',
                   'verifyG3TerminalContract', '--console=plain']
    return Gate('S08-forge', 'Forge build + transport/M04 contract tasks', [command], 900)


def outcome(gates: list[dict]) -> str:
    if any(g['status'] == 'FAIL' for g in gates):
        return 'FAIL'
    if any(g['status'] == 'HOLD' for g in gates):
        return 'HOLD'
    return 'PASS_AUTOMATED_ONLY'


def summary_markdown(report: dict) -> str:
    lines = ['# NPCortex M04 — Automated Acceptance', '',
             f"**Automated result:** {report['automated_result']}",
             '**Minecraft gameplay / native mining / pickup / real crash recovery:** HOLD — NOT TESTED',
             '', '| Gate | Result | Note |', '| --- | --- | --- |']
    for gate in report['gates']:
        reason = (gate['reason'] or '').replace('|', '/')
        lines.append(f"| {gate['id']} — {gate['label']} | {gate['status']} | {reason} |")
    lines += ['', '## Boundaries',
              '- No Minecraft instance started; no world mutation or automatic Qwen dispatch.',
              '- No backend process is killed/restarted by the acceptance runner.',
              '- The optional evidence stage is read-only and requires explicit world and DB paths.',
              '- A successful build or unit-test run does NOT satisfy live M04 Gates 0–6.',
              '- Logs/report are test evidence only; no raw world, database, secrets, or outbox included.',
              '']
    return '\n'.join(lines)


def create_report_zip(directory: Path, destination: Path) -> str:
    if destination.exists():
        raise FileExistsError('will not overwrite an existing report ZIP')
    report_files = [p for p in directory.rglob('*') if p.is_file() and p != destination]
    with zipfile.ZipFile(destination, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(report_files):
            # Local generated text logs/reports only; no symlink traversal.
            if file.is_symlink():
                raise ValueError('symlink in report output')
            archive.write(file, arcname=file.relative_to(directory).as_posix())
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('report CRC validation failed')
    return hashlib.sha256(destination.read_bytes()).hexdigest()


def run(args) -> int:
    if bool(args.world) != bool(args.database):
        raise ValueError('provide both --world and --database for read-only review, or neither')
    if args.world:
        world = args.world.expanduser().resolve(strict=True)
        database = args.database.expanduser().resolve(strict=True)
        if not (world / 'level.dat').is_file() or not database.is_file():
            raise ValueError('read-only review requires world/level.dat and an existing SQLite file')
    root = ROOT
    # Explicit source check; never mutate source when invoked from another cwd.
    if not (root / 'forge-mod/build.gradle').is_file() or not (root / 'backend/src').is_dir():
        raise RuntimeError('invalid NPCortex full repository')
    base = args.output_root or (root.parent / 'NPCortex-Automated-Reports')
    base = base.resolve()
    base.mkdir(parents=True, exist_ok=True)
    if base == root or root in base.parents:
        raise ValueError('refuse to put captured output inside source repository')
    report_dir = base / f'M04-Auto-{timestamp()}-{uuid.uuid4().hex[:8]}'
    report_dir.mkdir(mode=0o700)
    logs = report_dir / 'logs'
    logs.mkdir()
    report = {
        'schema': 1,
        'generated_utc': datetime.now(timezone.utc).isoformat(),
        'source': 'M04 Option D full source candidate (not GitHub state)',
        'python': platform.python_version(), 'host_os': platform.system(),
        'gates': [], 'automated_result': 'HOLD',
        'live_gameplay_status': 'HOLD_NOT_TESTED',
        'effect_authority': 'NONE',
        'mutation_enabled_by_runner': False,
    }
    env = make_environment(root)
    try:
        for gate in pure_gates(root):
            print(f'[RUN] {gate.id} — {gate.label}', flush=True)
            result = run_gate(gate, root, env, logs)
            report['gates'].append(result)
            print(f"[{result['status']}] {gate.id}: {result['reason'] or gate.label}", flush=True)
        with tempfile.TemporaryDirectory(prefix='npcortex-m04-java-') as classdir:
            gate = java_contract_gate(root, Path(classdir))
            print(f'[RUN] {gate.id} — {gate.label}', flush=True)
            result = run_gate(gate, root, env, logs)
            report['gates'].append(result)
            print(f"[{result['status']}] {gate.id}: {result['reason'] or gate.label}", flush=True)
        gate = backend_gate(root)
        if args.skip_backend_full:
            result = held('S07-backend-all', gate.label,
                          'explicit --skip-backend-full; full backend suite not verified')
        else:
            print(f'[RUN] {gate.id} — {gate.label} (can be long)', flush=True)
            result = run_gate(gate, root, env, logs)
        report['gates'].append(result)
        print(f"[{result['status']}] {gate.id}: {result['reason'] or gate.label}", flush=True)
        if args.skip_forge:
            result = held('S08-forge', 'Forge build + transport/M04 contract tasks',
                          'explicit -SkipForge / --skip-forge; not verified')
        else:
            gate = forge_gate(root)
            print(f'[RUN] {gate.id} — {gate.label} (may fetch Gradle dependencies)', flush=True)
            result = run_gate(gate, root / 'forge-mod', env, logs)
        report['gates'].append(result)
        print(f"[{result['status']}] S08-forge: {result['reason'] or 'done'}", flush=True)
        if args.world:
            world = args.world.expanduser().resolve(strict=True)
            db = args.database.expanduser().resolve(strict=True)
            if not (world / 'level.dat').is_file() or not db.is_file():
                raise ValueError('read-only review requires world/level.dat and an existing SQLite file')
            gate = Gate('S09-review-live', 'Read-only M04 evidence/SQLite correlation', [[
                sys.executable, '-B', str(root / 'scripts/m04_acceptance_review.py'),
                '--world', str(world), '--database', str(db),
                '--output', str(report_dir / 'm04-readonly-review.json')]], 60)
            result = run_gate(gate, root, env, logs)
        else:
            result = held('S09-review-live', 'Read-only M04 evidence/SQLite correlation',
                          'world and database not supplied; live evidence unverified')
        report['gates'].append(result)
        print(f"[{result['status']}] S09-review-live: {result['reason'] or 'done'}", flush=True)
        # Read-only evidence is not a gameplay PASS even when S09 returns success.
        report['automated_result'] = outcome(report['gates'][:8])
        if result['status'] == 'FAIL':
            report['automated_result'] = 'FAIL'
    except Exception as exc:
        report['automated_result'] = 'FAIL'
        report['fatal_error'] = f'{type(exc).__name__}: {exc}'
        print('FAIL:', report['fatal_error'], file=sys.stderr, flush=True)
    finally:
        write_json(report_dir / 'summary.json', report)
        (report_dir / 'summary.md').write_text(summary_markdown(report), encoding='utf-8')
        zipped = report_dir.with_suffix('.zip')
        digest = create_report_zip(report_dir, zipped)
        print(f'\nAUTO RESULT: {report["automated_result"]} | GAMEPLAY: HOLD (not tested)')
        print(f'REPORT: {report_dir / "summary.md"}')
        print(f'EVIDENCE ZIP: {zipped}')
        print(f'ZIP SHA256: {digest}')
    return 1 if report['automated_result'] == 'FAIL' else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--skip-forge', action='store_true', help='Mark Forge gate HOLD, do not run Gradle')
    parser.add_argument('--skip-backend-full', action='store_true', help='Mark full backend suite HOLD (developer smoke run)')
    parser.add_argument('--world', type=Path, help='Existing Minecraft world directory (read-only)')
    parser.add_argument('--database', type=Path, help='Existing backend SQLite path (read-only)')
    parser.add_argument('--output-root', type=Path, help='Directory OUTSIDE NPCortex repository')
    args = parser.parse_args()
    try:
        return run(args)
    except (OSError, ValueError, RuntimeError) as exc:
        print('Cannot start acceptance:', str(exc), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
