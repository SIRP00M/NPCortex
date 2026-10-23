"""Read-only Java->Python v2 parity check from LOCAL Minecraft latest.log.

Usage: python scripts/verify-cplus-f2-observation.py forge-mod/run/logs/latest.log
Does not connect to Backend or write any Minecraft world data.
"""
import json
import sys
from pathlib import Path
from npcortex_backend.cplus_f2_observation import validate_v2

PREFIX = 'NPCortex C-F2 operator read-only snapshot: '


def main():
    if len(sys.argv) != 2:
        raise SystemExit('usage: python scripts/verify-cplus-f2-observation.py <latest.log>')
    path = Path(sys.argv[1])
    # Bounded log parsing. Never print raw logs or private keys.
    if path.stat().st_size > 100 * 1024 * 1024:
        raise SystemExit('HOLD: log exceeds 100 MiB; use copied trimmed log')
    last = None
    for line in path.read_text(encoding='utf-8', errors='replace').splitlines():
        if PREFIX in line:
            last = line.split(PREFIX, 1)[1]
    if last is None:
        raise SystemExit('HOLD: no C-F2 observation in log; run /npcortex debug cplus world yui')
    try:
        validated = validate_v2(json.loads(last))
    except (ValueError, TypeError) as exc:
        raise SystemExit(f'HOLD: invalid read-only v2 snapshot: {type(exc).__name__}') from None
    summary = validated['coverage']
    print('PASS Java->Python C-F2 v2 JSON contract parity: '
          f'cells={summary["recorded"]} unknown={summary["unknown"]} '
          f'permission={validated["permission"]["decision"]} '
          f'schema={validated["schema_version"]}')


if __name__ == '__main__':
    main()
