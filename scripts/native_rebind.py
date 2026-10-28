"""Operator-directed fork / rename of a world's backend binding (Stage 4 native effects).

A world copy or a renamed world folder is refused by the backend (fail closed). If YOU decide that this folder is now the
world that owns the database record, this tool moves the record's folder key. It never deletes or edits a ledger row, a claim
or a HOLD; every move is appended to the binding audit table. Usage:
  python scripts\\native_rebind.py --world-id <uuid> --folder-key <new folder name> --reason "renamed the world" [--url http://127.0.0.1:8765]
The local credential is read from the backend's own secret file and is never printed."""
import argparse
import http.client
import json
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'backend' / 'src'))
from npcortex_backend.cplus_local_auth import read_secret  # noqa: E402


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--world-id', required=True)
    p.add_argument('--folder-key', required=True)
    p.add_argument('--reason', required=True)
    p.add_argument('--url', default='http://127.0.0.1:8765')
    a = p.parse_args()
    u = urllib.parse.urlparse(a.url)
    if u.hostname not in ('127.0.0.1', 'localhost'):
        print('refusing: the backend must be local')
        return 2
    body = {'schema_version': 1, 'world_id': a.world_id, 'folder_key': a.folder_key, 'confirm': 'REBIND', 'reason': a.reason}
    c = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=10)
    c.request('POST', '/v1/cplus/wn/rebind', json.dumps(body), {'Content-Type': 'application/json', 'X-NPCortex-CPlus-Token': read_secret()})
    r = c.getresponse()
    print(r.status, r.read().decode('utf-8', 'replace')[:400])
    return 0 if r.status == 200 else 1


if __name__ == '__main__':
    sys.exit(main())
