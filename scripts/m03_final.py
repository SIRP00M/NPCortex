"""Integrated M03 gates, evidence and packaging. No Git or live world mutation."""
from pathlib import Path
from contextlib import closing
import argparse
import datetime as dt
import hashlib
import json
import os
import platform
import shutil
import sqlite3
import subprocess
import sys
import time
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def save(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    # Unique run directories; results replaced atomically after each gate.
    tmp=path.with_suffix(path.suffix+'.tmp')
    with tmp.open('w',encoding='utf-8') as f:
        json.dump(value,f,indent=2);f.write('\n');f.flush();os.fsync(f.fileno())
    tmp.replace(path)


def run(args):
    stamp=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    evidence=(args.evidence or ROOT/'docs/m03-final/runs'/stamp).resolve()
    evidence.mkdir(parents=True,exist_ok=False)
    env=os.environ.copy();env['PYTHONPATH']=str(ROOT/'backend/src')
    result={'candidate':'M03-FINAL','started_utc':stamp,'environment':{
        'platform':platform.platform(),'python':sys.version,'cpu':platform.processor(),
        'logical_cpu_count':os.cpu_count()},'gates':[],'release_ready':False}
    save(evidence/'results.json',result)
    print('Evidence:',evidence,flush=True)
    def gate(name,commands,timeout=900):
        start=time.monotonic();state='AUTOMATED PASS';reason=''
        with (evidence/(name+'.log')).open('w',encoding='utf-8') as log:
            for cmd,cwd in commands:
                print(name,':',cmd[0],flush=True)
                try:
                    p=subprocess.run(cmd,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=timeout)
                    if p.returncode:
                        state='FAIL';reason='nonzero command exit '+str(p.returncode);break
                except (OSError,subprocess.TimeoutExpired) as ex:
                    state='HOLD / BLOCKED';reason=type(ex).__name__;break
        result['gates'].append({'gate':name,'status':state,'reason':reason,'duration_seconds':round(time.monotonic()-start,3)})
        save(evidence/'results.json',result)
        print(name,state,flush=True)
        return state=='AUTOMATED PASS'
    gradle=str(ROOT/'forge-mod/gradlew.bat')
    ps=shutil.which('pwsh') or shutil.which('powershell')
    compilation=[([gradle,'compileJava','compileTestJava','--console=plain'],ROOT/'forge-mod'),
        ([sys.executable,'-m','py_compile',str(ROOT/'scripts/m03_final.py'),str(ROOT/'scripts/m03_process_checks.py')],ROOT),
        ([ps,'-NoProfile','-File',str(ROOT/'scripts/test-m03-scripts.ps1')],ROOT),
        ([sys.executable,str(ROOT/'scripts/test_m03_harness.py'),'-v'],ROOT)]
    gates=[('G1-infrastructure',compilation),
           ('G2-backend',[([sys.executable,'-m','unittest','discover','-s','backend/tests','-v'],ROOT)]),
           ('G3-forge-java',[([gradle,'build','verifyTransportE','verifyTransportOutbox','verifyG2Identity',
              'verifyG3TerminalContract','verifyM03','--console=plain'],ROOT/'forge-mod')]+
              [([ps,'-NoProfile','-File',str(ROOT/'scripts'/f)],ROOT) for f in [
                  'test-test5-harness.ps1','test-c1-p1-inventory.ps1','test-c1-p1-inventory-saveddata.ps1',
                  'test-c1-r0-foundation.ps1','test-c2-terrain-native-follow-c.ps1']]),
           ('G4-process-6B',[([sys.executable,str(ROOT/'scripts/m03_process_checks.py'),'--gate','6b','--evidence',str(evidence/'process-6b')],ROOT)]),
           ('G5-response-7',[([sys.executable,str(ROOT/'scripts/m03_process_checks.py'),'--gate','7','--evidence',str(evidence/'response-7')],ROOT)])]
    blocked=False
    for name,commands in gates:
        if blocked:
            result['gates'].append({'gate':name,'status':'HOLD / BLOCKED','reason':'previous automated gate failed; not run'})
        elif not gate(name,commands):
            blocked=True
    for gate_name in ['G4-gameplay-6B','G5-gameplay-7','G6-save-rejoin-8','G7-gameplay-regression-9','G8-performance-10']:
        result['gates'].append({'gate':gate_name,'status':'PENDING / NOT TESTED',
            'reason':'requires live operator acceptance; later acceptance depends on preceding gameplay gates'})
    result['gates'].append({'gate':'G9-acceptance','status':'HOLD / BLOCKED','reason':'critical gameplay gates remain'})
    result['completed_utc']=dt.datetime.now(dt.timezone.utc).isoformat()
    save(evidence/'results.json',result)
    print('Automated preparation complete. M03 NOT closed. Gameplay 6B–10 pending.',flush=True)
    return 1 if blocked else 0


def collect(args):
    world=args.world.resolve(strict=True)
    if not (world/'level.dat').is_file():raise ValueError('not a Minecraft world')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=False)
    if output==world or world in output.parents:raise ValueError('evidence output must be outside world')
    # Read only. Do not copy tokens/config/chat logs. Preserve actual evidence.
    files=list((world/'data/npcortex/m03-evidence').glob('*.json'))
    files+=list((world/'data/npcortex/transport').glob('*'))
    files+=list((world/'data').glob('npcortex*.dat'))
    for name in ['cplus_f1_diagnostics.bin','cplus_f1_delivery.cursor',
                 'cplus_f1_diagnostics.bin.tmp','cplus_f1_delivery.cursor.tmp']:
        path=world/'data/npcortex'/name
        if path.exists():files.append(path)
    manifest=[]
    for source in files:
        if not source.is_file() or source.is_symlink():raise ValueError('unsafe evidence source')
        data=source.read_bytes();rel=source.relative_to(world)
        dest=output/'world'/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(data)
        manifest.append({'file':rel.as_posix(),'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)})
    if args.database:
        db=args.database.resolve(strict=True)
        with closing(sqlite3.connect(db.as_uri()+'?mode=ro',uri=True)) as conn:
            conn.row_factory=sqlite3.Row
            conn.execute('BEGIN')
            tables={r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            export={}
            for name in ['cplus_e3_g2_moves','cplus_e3_g3_terminals','cplus_e3_g3_cancel_intents',
                         'cplus_f1_bridge_checkpoint','cplus_f1_bridge_receipts']:
                if name in tables:export[name]=[dict(r) for r in conn.execute('SELECT * FROM '+name)]
            # F1 cursor/schema varies; preserve named bridge metadata only, never transcript tables.
            export['table_counts']={name:conn.execute('SELECT count(*) FROM "'+name.replace('"','""')+'"').fetchone()[0]
                                    for name in sorted(tables) if name.startswith('cplus_')}
            save(output/'backend-readonly.json',export)
    save(output/'manifest.json',{'captured_utc':dt.datetime.now(dt.timezone.utc).isoformat(),
        'status':'COLLECTED_NOT_ACCEPTED','files':manifest,'note':'live files are not an atomic world snapshot; capture again after Save & Quit'})
    print(output)


def compare(args):
    before=json.loads(args.before.read_text());after=json.loads(args.after.read_text())
    failures=[];holds=[]
    for key in ('world_id','actor_id','profile_id','dimension_id','inventory_nbt','equipment'):
        if key not in before or before.get(key)!=after.get(key):failures.append(key+' changed/missing')
    if before.get('session_id')==after.get('session_id'):holds.append('no new server session established')
    for key in ('c1_active','c2_active','follow_active','g2_pending','g3_pending','recovery_pending'):
        if key not in after or after[key]:holds.append(key+' active/missing')
    if before.get('c2_journal')!=after.get('c2_journal'):holds.append('C2 journal changed: inspect legitimate terminal reconciliation')
    for key in ('task_id','attempt_id','goal_id','actor_id','world_id','session_id'):
        b=before.get('backend',{});a=after.get('backend',{})
        if key not in b or key not in a or b[key]!=a[key]:holds.append('backend '+key+' missing/changed')
    state=before.get('backend',{}).get('state')
    if state in ('COMPLETED','CANCELLED'):
        observed=after.get('backend',{}).get('state')
        if observed is None:holds.append('terminal backend state unavailable')
        elif state!=observed:failures.append('terminal changed')
    else:holds.append('outstanding HOLD requires protocol inspection; never force-release')
    old={r['file']:r['sha256'] for r in before.get('outbox',[])}
    new={r['file']:r['sha256'] for r in after.get('outbox',[])}
    if not old:holds.append('no original outbox evidence')
    for key,value in old.items():
        if new.get(key)!=value:failures.append('original outbox changed/missing: '+key)
    answer={'status':'FAIL' if failures else 'HOLD' if holds else 'AUTOMATED METADATA CHECK PASS',
            'failures':failures,'holds':holds,'gameplay':'PENDING: observe no motion/replay, inspect F1 cursor/checkpoint, explicitly test new task'}
    save(args.output,answer);print(json.dumps(answer,indent=2))
    return 1 if failures else 0


def owned(args):
    from m03_process_checks import OwnedBackend
    db=args.database.resolve(strict=True)
    if db==ROOT/'backend/data/npcortex.sqlite3':raise ValueError('Use an explicitly prepared COPY database for gameplay acceptance')
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False)
    backend=OwnedBackend(evidence,args.port,db,private=False)
    try:
        backend.start()
        print('Owned test backend healthy. Configure copied Forge instance to loopback port',args.port)
        print('Only this child is controlled. Existing services are never adopted. Same DB/config on restart.')
        while True:
            command=input('[status / stop / start / quit] > ').strip().lower()
            if command=='stop':backend.stop();print('Owned child stopped. Capture offline Forge evidence.')
            elif command=='start':backend.start();print('Owned child restarted with identical DB/config.')
            elif command=='status':print('ONLINE' if backend.process and backend.process.poll() is None else 'OFFLINE')
            elif command=='quit':break
    finally:
        backend.stop();save(evidence/'ownership.json',{'owned_pid_generations':backend.generations,
            'database':str(db),'port':args.port,'gameplay':'PENDING; process operation alone is not acceptance'})


def package(args):
    output=args.output.resolve()
    if output.exists():raise ValueError('candidate already exists; will not overwrite')
    excluded={'.git','.gradle','build','run','__pycache__','.venv','node_modules','dist','acceptance'}
    files=[]
    for p in sorted(ROOT.rglob('*')):
        rel=p.relative_to(ROOT)
        if not p.is_file() or p.is_symlink() or p==output or any(x in excluded for x in rel.parts):continue
        if rel.parts[:2]==('backend','data') or p.name.startswith('.env') or p.suffix in {'.secret','.key'}:continue
        files.append(p)
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'x',zipfile.ZIP_DEFLATED) as z:
        for p in files:z.write(p,'NPCortex/'+p.relative_to(ROOT).as_posix())
        z.writestr('NPCortex/CANDIDATE_MANIFEST.json',json.dumps({
            'scope':'Full source repository + evidence. Live worlds/DB/secrets/cache intentionally not distributed.',
            'files':[{'path':p.relative_to(ROOT).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]},indent=2))
    with zipfile.ZipFile(output) as z:
        if z.testzip():raise RuntimeError('ZIP CRC failed')
    print(json.dumps({'zip':str(output),'files':len(files),'sha256':hashlib.sha256(output.read_bytes()).hexdigest()}))


if __name__=='__main__':
    p=argparse.ArgumentParser();sub=p.add_subparsers(dest='command',required=True)
    r=sub.add_parser('run');r.add_argument('--evidence',type=Path)
    r=sub.add_parser('collect');r.add_argument('--world',type=Path,required=True);r.add_argument('--database',type=Path);r.add_argument('--output',type=Path,required=True)
    r=sub.add_parser('compare-rejoin');r.add_argument('--before',type=Path,required=True);r.add_argument('--after',type=Path,required=True);r.add_argument('--output',type=Path,required=True)
    r=sub.add_parser('owned-backend');r.add_argument('--database',type=Path,required=True);r.add_argument('--port',type=int,default=18765);r.add_argument('--evidence',type=Path,required=True)
    r=sub.add_parser('package');r.add_argument('--output',type=Path,required=True)
    a=p.parse_args();sys.exit({'run':run,'collect':collect,'compare-rejoin':compare,'owned-backend':owned,'package':package}[a.command](a) or 0)
