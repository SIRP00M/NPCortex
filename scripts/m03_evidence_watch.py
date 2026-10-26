#!/usr/bin/env python3
"""NPCortex M03 read-only, cross-system evidence watcher.

Watches the existing TEMP Forge M03 bridge output, durable outbox and the explicitly
selected SQLite DB. Never sends commands/HTTP requests, touches savedata, or replays
requests. Each checkpoint carries a DB/world/actor correlation verdict.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import time
import uuid
import zipfile
from urllib.request import urlopen
from urllib.error import URLError, HTTPError
from urllib.parse import urlsplit

TABLES = (
    'cplus_e3_g2_moves', 'cplus_e3_g3_terminals', 'cplus_e3_g3_cancel_intents',
    'cplus_f1_bridge_checkpoint', 'cplus_f1_bridge_receipts'
)
CAP_BYTES = 8 * 1024 * 1024
MAX_ROWS = 3000


def utc():
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def dump(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = (json.dumps(data, indent=2, ensure_ascii=False, default=str) + '\n').encode('utf-8')
    tmp = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    with tmp.open('xb') as f:
        f.write(raw); f.flush(); os.fsync(f.fileno())
    tmp.replace(path)


def load(path: Path):
    with path.open('r', encoding='utf-8') as f:
        return json.load(f)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def safe_read(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError('not a regular non-symlink file: ' + str(path))
    if path.stat().st_size > CAP_BYTES:
        raise ValueError('evidence exceeds per-file safety limit: ' + str(path))
    return path.read_bytes()


def safe_json_file(path):
    return json.loads(safe_read(path).decode('utf-8-sig'))


def root_state(world, db):
    world = world.expanduser().resolve(strict=True)
    db = db.expanduser().resolve(strict=True)
    if not (world / 'level.dat').is_file():
        raise ValueError('world path missing level.dat: ' + str(world))
    if not db.is_file() or db.suffix.lower() not in ('.sqlite', '.sqlite3', '.db'):
        raise ValueError('provide the REAL backend SQLite file using --database: ' + str(db))
    return world, db


def db_read(db_path: Path, snapshots, outbox):
    worlds = {s.get('world_id') for s in snapshots if s.get('world_id')}
    actors = {s.get('actor_id') for s in snapshots if s.get('actor_id')}
    outbox_tasks = {o.get('task_id') for o in outbox if o.get('task_id')}
    result = {'observed_at_utc': utc(), 'source_database': str(db_path),
              'world_ids': sorted(worlds), 'actor_ids': sorted(actors),
              'outbox_task_ids': sorted(outbox_tasks), 'tables': {}, 'counts_global': {},
              'correlation': {'status': 'UNVERIFIED', 'matched_g2_rows': 0,
                              'matched_outbox_tasks': [], 'unmatched_outbox_tasks': sorted(outbox_tasks)}}
    try:
        uri = db_path.resolve().as_uri() + '?mode=ro'
        with sqlite3.connect(uri, uri=True, timeout=3) as con:
            con.row_factory = sqlite3.Row
            con.execute('PRAGMA query_only = ON')
            con.execute('PRAGMA busy_timeout = 3000')
            con.execute('BEGIN')  # one consistent DB read, not atomic with Forge
            available = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            for table in TABLES:
                if table not in available:
                    result['tables'][table] = {'status': 'MISSING', 'rows': []}
                    continue
                result['counts_global'][table] = con.execute('SELECT count(*) FROM "'+table+'"').fetchone()[0]
                columns = [r[1] for r in con.execute('PRAGMA table_info("'+table+'")')]
                where = []; params = []
                if table == 'cplus_e3_g2_moves':
                    # A match by world and actor is stronger than a task-only match.
                    if worlds and actors:
                        where = ['world_id IN ('+','.join('?' for _ in worlds)+')',
                                 'actor_id IN ('+','.join('?' for _ in actors)+')']
                        params = sorted(worlds) + sorted(actors)
                elif table.startswith('cplus_e3_g3_'):
                    tasks = outbox_tasks | {r.get('task_id') for r in result['tables'].get('cplus_e3_g2_moves',{}).get('rows',[]) if r.get('task_id')}
                    if tasks and 'task_id' in columns:
                        where = ['task_id IN ('+','.join('?' for _ in tasks)+')']
                        params = sorted(tasks)
                else:
                    if worlds and 'world_id' in columns:
                        where = ['world_id IN ('+','.join('?' for _ in worlds)+')']
                        params = sorted(worlds)
                if not where:
                    result['tables'][table] = {'status': 'UNCORRELATED_NO_SCOPE', 'rows': []}
                    continue
                sql = 'SELECT * FROM "'+table+'" WHERE ' + ' AND '.join(where) + ' LIMIT ?'
                rows = []
                for row in con.execute(sql, [*params, MAX_ROWS + 1]):
                    raw = dict(row)
                    # Never export payload/evidence JSON blobs or other unknown data:
                    # only named metadata needed for task/actor correlation.
                    keep = {'task_id','request_id','attempt_id','requester_id','actor_id','world_id',
                            'dimension_id','session_id','observation_id','payload_hash','goal_id',
                            'state','outcome','evidence_hash','created_at','updated_at','finalized_at',
                            'requested_at','sequence','record_hash','event_id','receipt_id'}
                    rows.append({k:(sha(v) if isinstance(v, bytes) else v) for k,v in raw.items() if k in keep})
                truncated = len(rows) > MAX_ROWS
                result['tables'][table] = {'status': 'TRUNCATED' if truncated else 'OK',
                                           'rows': rows[:MAX_ROWS]}
            g2 = result['tables'].get('cplus_e3_g2_moves',{}).get('rows',[])
            found_tasks = {r.get('task_id') for r in g2}
            overlap = outbox_tasks & found_tasks
            result['correlation'].update(matched_g2_rows=len(g2),
                 matched_outbox_tasks=sorted(overlap), unmatched_outbox_tasks=sorted(outbox_tasks - overlap),
                 status=('MATCHED_WORLD_ACTOR' if g2 else 'NO_MATCHING_G2_ROWS' if worlds and actors
                         else 'NO_FORGE_IDENTITY'))
            con.rollback()
    except (sqlite3.Error, OSError) as e:
        result['correlation']['status'] = 'DB_READ_ERROR'
        result['correlation']['error'] = f'{type(e).__name__}: {e}'
    return result


def scan_files(world, output, seen):
    """Immutable copy of NEW current Forge snapshots/faults/retained envelopes."""
    discovered=[]; snapshot_objects=[]; outbox_objects=[]; scan_errors=[]
    branches = [
        ('game', world / 'data/npcortex/m03-evidence'),
        ('outbox', world / 'data/npcortex/transport')
    ]
    for kind, folder in branches:
        if not folder.is_dir():
            scan_errors.append('directory missing: '+str(folder))
            continue
        for path in sorted(folder.glob('*.json')):
            try:
                raw = safe_read(path)
                obj = json.loads(raw.decode('utf-8-sig'))
                digest = sha(raw)
                rel = kind+'/'+path.name
                if seen.get(rel) != digest:
                    dest = output/'raw'/kind/path.name
                    # Same filename with revised content: retain all revisions, never replace a capture.
                    if dest.exists() and sha(dest.read_bytes()) != digest:
                        dest = output/'raw'/kind/(path.stem+'-'+digest[:12]+'.json')
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    if not dest.exists(): dest.write_bytes(raw)
                    seen[rel] = digest
                    discovered.append({'kind':kind,'filename':path.name,'sha256':digest,
                                       'captured_copy':str(dest.relative_to(output))})
                if kind == 'game' and isinstance(obj,dict):
                    if obj.get('world_id') and obj.get('actor_id'):
                        snapshot_objects.append(obj)
                    elif obj.get('kind'):
                        pass  # fault/performance has no actor; raw copy retained
                elif kind=='outbox' and isinstance(obj,dict):
                    body = obj.get('body') or {}
                    if isinstance(body, dict):
                        outbox_objects.append({'filename':path.name,'sha256':digest,
                            'route':obj.get('route'), 'task_id':body.get('task_id'),
                            'attempt_id':body.get('attempt_id'), 'world_id':body.get('world_id'),
                            'actor_id':body.get('actor_id'), 'session_id':body.get('session_id')})
            except (ValueError, OSError, UnicodeError) as e:
                scan_errors.append(f'{path.name}: {type(e).__name__}: {e}')
    return discovered, snapshot_objects, outbox_objects, scan_errors


def probe_backend(url):
    parsed=urlsplit(url)
    if parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1','localhost','::1') or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('--backend-url must be an unauthenticated loopback HTTP base URL')
    endpoint=url.rstrip('/')+'/health'
    try:
        with urlopen(endpoint,timeout=1.5) as response:
            return {'status':'ONLINE_200' if response.status==200 else 'HTTP_'+str(response.status),
                    'http_code':response.status,'endpoint':endpoint}
    except HTTPError as e:
        return {'status':'HTTP_'+str(e.code),'http_code':e.code,'endpoint':endpoint}
    except (URLError,TimeoutError,OSError) as e:
        return {'status':'OFFLINE_OR_UNREACHABLE','error_type':type(e).__name__,'endpoint':endpoint}


def checkpoint(world, db, output, seen, reason, pre=None, backend_url='http://127.0.0.1:8765'):
    fresh, snapshots, outbox, errors = pre if pre is not None else scan_files(world, output, seen)
    # DB snapshots are correlated against currently retained Forge identity,
    # never against arbitrary rows from other player/world DBs.
    if not snapshots:
        # If the first Forge snapshot has not arrived, do not infer an actor from file names.
        errors.append('No in-game M03 Forge snapshot yet; use /npcortex debug cplus m03 snapshot yui')
    correlation = db_read(db,snapshots,outbox)
    record={'timestamp_utc':utc(),'reason':reason,'new_files':fresh,'snapshot_count_visible':len(snapshots),
            'forge_world_ids':sorted({s.get('world_id') for s in snapshots if s.get('world_id')}),
            'forge_actor_ids':sorted({s.get('actor_id') for s in snapshots if s.get('actor_id')}),
            'forge_session_ids':sorted({s.get('session_id') for s in snapshots if s.get('session_id')}),
            'forge_outbox':outbox,'errors':errors,'backend_health':probe_backend(backend_url),'backend':correlation}
    p=output/'checkpoints'/f'{int(time.time()*1000)}-{uuid.uuid4().hex[:8]}.json'
    dump(p, record)
    return record


def report(output):
    checks=[]
    for f in sorted((output/'checkpoints').glob('*.json')):
        try:checks.append(load(f))
        except (OSError,ValueError):pass
    samples=[];faults=[];perf=[]
    for p in (output/'raw/game').glob('*.json'):
        try:
            o=load(p)
            if o.get('world_id') and o.get('actor_id'):samples.append(o)
            if o.get('kind'):faults.append({'file':p.name,'kind':o.get('kind'),'route':o.get('route'),
                                            'task_id':o.get('task_id')})
            if o.get('kind')=='performance':perf.append({'file':p.name,'npc_count':o.get('npc_count'),
                                             'status':o.get('status'),'duration_seconds':o.get('duration_seconds')})
        except (ValueError,OSError):pass
    ids=sorted({(s.get('world_id'),s.get('actor_id')) for s in samples})
    sessions=sorted({s.get('session_id') for s in samples if s.get('session_id')})
    statuses=[c['backend']['correlation']['status'] for c in checks]
    health=[c.get('backend_health',{}).get('status','UNKNOWN') for c in checks]
    matching=[c for c in checks if c['backend']['correlation']['status']=='MATCHED_WORLD_ACTOR']
    missed=[c for c in checks if c['backend']['correlation']['status']=='NO_MATCHING_G2_ROWS']
    original=[]
    original_seen=set()
    for s in samples:
        for r in s.get('outbox',[]):
            key=r.get('file');digest=r.get('sha256')
            if key and digest and (key,digest) not in original_seen:
                original_seen.add((key,digest));original.append({'file':key,'sha256':digest})
    last_db=(checks[-1]['backend'] if checks else {})
    last_g2=last_db.get('tables',{}).get('cplus_e3_g2_moves',{}).get('rows',[])
    last_g3=last_db.get('tables',{}).get('cplus_e3_g3_terminals',{}).get('rows',[])
    g2_states={}
    for r in last_g2:
        state=r.get('state','UNKNOWN')
        g2_states[state]=g2_states.get(state,0)+1
    terminal={}
    for r in last_g3:
        terminal.setdefault(r.get('task_id'),set()).add(r.get('outcome'))
    contradictory=sorted(str(k) for k,states in terminal.items() if len(states)>1)
    by_file={}
    for s in samples:
        for o in s.get('outbox',[]):by_file.setdefault(o.get('file'),set()).add(o.get('sha256'))
    changed=[k for k,v in by_file.items() if len(v)>1]
    report_data={'generated_utc':utc(),'status':'COLLECTED_NOT_ACCEPTED',
       'world_actor_pairs':[{'world_id':w,'actor_id':a} for w,a in ids],
       'sessions':sessions,'forge_snapshots':len(samples),'faults':faults,
       'performance_rounds':perf,'checkpoint_count':len(checks),
       'backend_db_matched_checkpoints':len(matching),'backend_db_unmatched_checkpoints':len(missed),
       'backend_correlation_last':statuses[-1] if statuses else 'NO_CHECKPOINTS',
       'backend_health_timeline':health,
       'observed_online':any(s=='ONLINE_200' for s in health),
       'observed_offline':any(s=='OFFLINE_OR_UNREACHABLE' for s in health),
       'backend_correlation_note':('At least one matching G2 row verified for the captured world/actor' if matching else
          'No matching G2 row. Could be a wrong DB OR intentionally never-handed-off attempts; cannot decide from this alone.'),
       'observed_outbox_hash_changes':changed,
       'latest_g2_states_for_observed_world_actor':g2_states,
       'latest_terminal_task_count':len(terminal),
       'contradictory_terminal_outcomes':contradictory,
       'after_successful_response_fault_count':sum(f['kind']=='AFTER_SUCCESSFUL_RESPONSE' for f in faults),
       'test6b':'HOLD until owned-process timeline, matched attempts and no-replay are verified',
       'test7':'HOLD until AFTER_SUCCESSFUL_RESPONSE + matching DB task/terminal state verified',
       'test8':'HOLD until paired before/after rejoin + protocol/attempt proof verified',
       'test9':'HOLD/PENDING: actual C1/C2/Follow/Inventory; empty-slot unequip API absent',
       'test10':'PENDING/HOLD: ≥3 measured rounds per NPC count 1,3,5,10 and profiler evidence',
       'limitations':['read-only periodic snapshots are not an atomic cross-system checkpoint',
           'no automatic gameplay PASS; external stop/start and actual rejoin cannot be inferred from a timer',
           'no live HTTP requests or backend authentication performed',
           'data may contain in-game world observations; review before sharing']}
    dump(output/'final-report.json',report_data)
    lines=['# NPCortex M03 — Evidence Watch Report','',
           f"**Status:** {report_data['status']} (no automatic gameplay certification)",
           f"**Forge snapshots:** {len(samples)} · **checkpoints:** {len(checks)} · **sessions:** {len(sessions)}",
           f"**DB matched checkpoints:** {len(matching)} · **unmatched:** {len(missed)}",
           f"**Latest DB correlation:** `{report_data['backend_correlation_last']}`",
           f"**Health transition observed:** online={report_data['observed_online']}, offline={report_data['observed_offline']} (not proof of process ownership)",'',
           '## Gates 6B–10',
           '| Gate | Evidence status |','|---|---|',
           '| 6B Backend restart | HOLD — process lifecycle + matched attempt need verification |',
           '| 7 Response/ACK loss | HOLD — check `AFTER_SUCCESSFUL_RESPONSE` and terminal rows |',
           '| 8 Save/Rejoin | HOLD — compare paired sessions and matching protocol records |',
           '| 9 Regression | PENDING — include movement/follow/inventory observations; unequip-to-empty HOLD |',
           '| 10 Performance | PENDING — 3× each of 1/3/5/10 NPC, profiler/MSPT |','',
           '## Diagnostic flags',
           f"- Changed outbox hashes observed in Forge snapshots: {changed or 'none observed'}",
           f"- Faults observed: {[(x['kind'],x['route']) for x in faults]}",
           f"- World/Actor identities observed: {len(ids)}",
           f"- Last correlated G2 states: {g2_states}",
           f"- Last correlated G3 terminal tasks: {len(terminal)}",
           f"- Contradictory terminal outcomes observed: {contradictory or 'none observed'}",
           f"- AFTER_SUCCESSFUL_RESPONSE faults observed: {report_data['after_successful_response_fault_count']}",'',
           'Evidence integrity and completeness are separate from gameplay PASS. Preserve unresolved HOLD.']
    (output/'REPORT.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    return report_data


def integrity_manifest(output):
    """Manifest covers files actually delivered, including captured DB metadata."""
    files=[]
    for p in sorted(output.rglob('*')):
        if p.is_file() and p.name != 'integrity-manifest.json' and not p.is_symlink():
            raw=p.read_bytes()
            files.append({'file':p.relative_to(output).as_posix(), 'sha256':sha(raw), 'bytes':len(raw)})
    dump(output/'integrity-manifest.json', {'generated_utc':utc(), 'files':files,
        'note':'all evidence and backend checkpoint files covered; manifest excludes itself'})
    return len(files)


def package(output):
    target=output.parent/(output.name+'.zip')
    if target.exists():
        raise FileExistsError('Export ZIP already exists; choose new output directory')
    with zipfile.ZipFile(target,'x',zipfile.ZIP_DEFLATED) as z:
        for path in sorted(output.rglob('*')):
            if path.is_file() and not path.is_symlink():
                z.write(path, arcname=output.name+'/'+path.relative_to(output).as_posix())
    return target


def arguments(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--world',type=Path,required=True,help='EXACT world folder (e.g. New World (2))')
    p.add_argument('--database',type=Path,required=True,help='Verified ACTIVE backend DB path (never guessed)')
    p.add_argument('--output',type=Path,required=True,help='NEW evidence output directory outside world')
    p.add_argument('--interval',type=float,default=4)
    p.add_argument('--heartbeat',type=float,default=5)
    p.add_argument('--backend-url',default='http://127.0.0.1:8765',help='Loopback /health only, no credentials')
    p.add_argument('--once',action='store_true')
    p.add_argument('--zip',action='store_true',help='Create ZIP after stop/capture')
    return p.parse_args(argv)


def main(argv=None):
    a=arguments(argv)
    if a.interval<1 or a.heartbeat<1:raise ValueError('interval/heartbeat must be >= 1 seconds')
    world,db=root_state(a.world,a.database)
    # Validate before creating any output directory.
    urlsplit(a.backend_url)
    u=urlsplit(a.backend_url)
    if u.scheme != 'http' or u.hostname not in ('127.0.0.1','localhost','::1') or u.username or u.password or u.query or u.fragment:
        raise ValueError('backend-url must be a loopback http base URL without credentials/query')
    out=a.output.expanduser().resolve()
    if out == world or world in out.parents:
        raise ValueError('Output MUST be outside Minecraft world')
    if out.exists():
        raise FileExistsError('Output already exists; use a NEW empty directory for each run')
    out.mkdir(parents=True)
    seen={}
    config={'started_utc':utc(),'world_path':str(world),'db_path':str(db),
            'read_only':True,'interval_seconds':a.interval,'heartbeat_seconds':a.heartbeat,
            'health_url':a.backend_url.rstrip('/')+'/health'}
    dump(out/'run.json',config)
    print('M03 EVIDENCE WATCH: read-only; will NOT issue game or backend commands',flush=True)
    print('World:',world,'\nBackend DB:',db,'\nOutput:',out,flush=True)
    print('In-game: /npcortex debug cplus m03 enable  (once per server session)')
    print('In-game: /npcortex debug cplus m03 snapshot yui  (after each phase)')
    print('End after all tests with Ctrl+C; report + optional ZIP produced.',flush=True)
    last_heartbeat=0
    try:
        while True:
            now=time.monotonic()
            # Checkpoints are periodic to witness Backend OFFLINE/ONLINE even with no new file.
            if a.once or now-last_heartbeat>=a.heartbeat:
                record=checkpoint(world,db,out,seen,'initial' if last_heartbeat==0 else 'heartbeat',backend_url=a.backend_url)
                last_heartbeat=now
                state=record['backend']['correlation']['status']
                print(f"{utc()} DB={state} HEALTH={record['backend_health']['status']} snapshots={record['snapshot_count_visible']} "
                      f"new_files={len(record['new_files'])} scan_errors={len(record['errors'])}",flush=True)
                if state=='NO_MATCHING_G2_ROWS':
                    print('  WARNING: zero backend G2 matches; DB may be wrong OR requests never sent; HOLD',flush=True)
            else:
                # Trigger a full DB checkpoint when a NEW Forge artifact arrives.
                additions,snaps,box,errs=scan_files(world,out,seen)
                if additions:
                    record=checkpoint(world,db,out,seen,'new-forge-artifact',
                                      pre=(additions,snaps,box,errs),backend_url=a.backend_url)
                    print(f"{utc()} +{len(additions)} game/outbox artifacts",flush=True)
            if a.once:break
            time.sleep(a.interval)
    except KeyboardInterrupt:
        print('\nStopping read-only capture; generating report...',flush=True)
    finally:
        result=report(out)
        print('REPORT:',out/'REPORT.md',flush=True)
        print('DB correlation:',result['backend_correlation_last'],flush=True)
        print('Files checksummed:',integrity_manifest(out),flush=True)
        if a.zip:
            print('ZIP:',package(out),flush=True)
    return 0


if __name__=='__main__':
    try:sys.exit(main())
    except (OSError,ValueError,sqlite3.Error) as e:
        print(f'ERROR: {type(e).__name__}: {e}',file=sys.stderr)
        sys.exit(2)
