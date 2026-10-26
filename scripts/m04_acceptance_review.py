#!/usr/bin/env python3
"""Read-only M04 Option D evidence review. Never generates gameplay PASS.

This tool runs after /function npcortex_m04:preflight or after_rejoin.
It reads a copied test world's m04-evidence JSON plus the explicitly selected
backend SQLite DB, correlates world/actor IDs and reports unresolved G2 claims.
It never executes Forge commands or changes any Minecraft/SQLite records.
"""
import argparse
import json
from pathlib import Path
import sqlite3
from contextlib import closing
from datetime import datetime, timezone
import hashlib

MAX_EVIDENCE = 1024 * 1024


def review(world: Path, database: Path) -> dict:
    if not (world / 'level.dat').is_file() or not database.is_file():
        raise ValueError('real world folder (level.dat) and explicit SQLite DB required')
    snapshots = []
    directory = world / 'data/npcortex/m04-evidence'
    if directory.is_dir():
        for file in sorted(directory.glob('m04-*.json')):
            if file.is_symlink() or not file.is_file() or file.stat().st_size > MAX_EVIDENCE:
                raise ValueError('unsafe/malformed evidence file: ' + str(file))
            raw = file.read_bytes()
            entry = json.loads(raw)
            if not isinstance(entry, dict) or entry.get('capture_kind') != 'M04_PREVIEW_ONLY':
                raise ValueError('unexpected evidence format: ' + str(file))
            snapshots.append(dict(file=file.name, hash=hashlib.sha256(raw).hexdigest(),
                                  world_id=entry.get('world_id'), actor_id=entry.get('actor_id'),
                                  session_id=entry.get('session_id'),
                                  observation_tick=entry.get('capture_tick'),
                                  g2_pending=entry.get('g2_pending'),
                                  g3_pending=entry.get('g3_pending'),
                                  recovery_pending=entry.get('transport_recovery_busy'),
                                  mutation_permitted=entry.get('mutation_permitted')))
    pairs = sorted({(s['world_id'], s['actor_id']) for s in snapshots if s['world_id'] and s['actor_id']})
    uri = database.resolve().as_uri() + '?mode=ro'
    rows = []
    claim_rows = []
    with closing(sqlite3.connect(uri, uri=True, timeout=3)) as db:
        exists = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if 'cplus_e3_g2_moves' in exists:
            for world_id, actor_id in pairs:
                for r in db.execute('SELECT task_id,attempt_id,state,goal_id FROM cplus_e3_g2_moves '
                                    'WHERE world_id=? AND actor_id=? ORDER BY rowid DESC LIMIT 50',
                                    (world_id, actor_id)):
                    rows.append(dict(world_id=world_id, actor_id=actor_id,
                                     task_id=r[0], attempt_id=r[1], state=r[2], goal_id=r[3]))
        if 'cplus_m04_runs' in exists:
            for world_id, actor_id in pairs:
                for r in db.execute('SELECT task_id,state,effect,next_step FROM cplus_m04_runs '
                                    'WHERE world_id=? AND actor_id=? ORDER BY rowid DESC LIMIT 50',
                                    (world_id, actor_id)):
                    claim_rows.append(dict(world_id=world_id, actor_id=actor_id,
                                           task_id=r[0], state=r[1], effect=r[2], next_step=r[3]))
    active_g2 = [r for r in rows if r['state'] in ('RESERVED','C2_ACCEPTED','HOLD','CANCEL_PENDING')]
    active_m04 = [r for r in claim_rows if r['state'] in ('RESERVED','ACTIVE','HOLD')]
    return {
        'generated_utc': datetime.now(timezone.utc).isoformat(),
        'verdict': 'HOLD_OR_PENDING_NOT_GAMEPLAY_PASS',
        'meaning': 'Metadata correlation only; no proof of native timed break/pickup/cancel',
        'snapshots': snapshots,
        'pairs': [{'world_id': w, 'actor_id': a} for w,a in pairs],
        'session_ids': sorted({s['session_id'] for s in snapshots if s['session_id']}),
        'historical_movement_rows': rows,
        'unresolved_movement_claims': active_g2,
        'm04_rows': claim_rows,
        'unresolved_m04_claims': active_m04,
        'gates': {'G0': 'PENDING_RUNTIME_AUTH_AND_OWNERSHIP',
                  'G1': 'PENDING_REAL_PERMISSION_ADAPTER',
                  'G2': 'PENDING_FORGE_BUILD_AND_GAMEPLAY',
                  'G3': 'HOLD_NO_NATIVE_MINING_EXECUTOR',
                  'G4': 'HOLD_NO_REAL_PICKUP_EXECUTOR',
                  'G5': 'HOLD_NO_LIVE_EFFECT_RECOVERY',
                  'G6': 'HOLD_NO_END_TO_END_WITNESS',
                  'P': 'PENDING_PERFORMANCE'},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--world', type=Path, required=True)
    parser.add_argument('--database', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = review(args.world, args.database)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f"M04 review: {len(result['snapshots'])} evidence snapshots; "
          f"{len(result['unresolved_movement_claims'])} unresolved G2 claims; "
          f"{len(result['unresolved_m04_claims'])} unresolved M04 claims")
    print(f"Verdict: {result['verdict']} | {args.output}")


if __name__ == '__main__': main()
