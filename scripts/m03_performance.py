"""Summarize live M03 samples without inventing acceptance thresholds."""
import argparse
import json
import math
import statistics
from pathlib import Path


def stats(values):
    values=sorted(values)
    if not values:return {'status':'UNAVAILABLE','count':0}
    return {'count':len(values),'average':statistics.mean(values),'p95':values[math.ceil(len(values)*.95)-1],'max':max(values)}


def summarize(directory):
    objects=[]
    for path in directory.rglob('*.json'):
        try:
            obj=json.loads(path.read_text(encoding='utf-8'))
            if isinstance(obj,dict):objects.append(obj)
        except (ValueError,OSError):pass
    rounds=[]
    for m in (o for o in objects if o.get('kind')=='performance'):
        snapshots=[o for o in objects if o.get('measurement_id')==m.get('measurement_id') and 'backend_request_ms' in o]
        starts={};latencies=[];goals={};bad=[]
        for t in m.get('navigation_transitions',[]):
            goal=t.get('goal_id');actor=t['actor_id']
            if not goal:continue
            if goal in goals and goals[goal]!=actor:bad.append('cross-NPC goal ownership collision')
            goals[goal]=actor
            if t['active'] and not t['initial']:starts.setdefault(goal,t['seconds'])
            if not t['active'] and t['state'] in ('COMPLETED','CANCELLED','FAILED') and goal in starts:
                latencies.append((t['seconds']-starts.pop(goal))*1000)
        # Retained envelopes are not a delivery queue: classify only with correlated backend state.
        outstanding=set();unknown=set();terminal={};growth=[]
        for name in {s.get('name') for s in snapshots}:
            a=[s for s in snapshots if s.get('name')==name]
            before=next((s for s in a if s.get('label')=='performance-start'),None)
            after=next((s for s in a if s.get('label')=='performance-end'),None)
            if not before or not after:bad.append('missing paired snapshot: '+str(name));continue
            growth.append({'npc':name,'retained_outbox_bytes_delta':after['outbox_retained_bytes']-before['outbox_retained_bytes'],
                'retained_envelopes_delta':after['outbox_retained_count']-before['outbox_retained_count']})
            b=after.get('backend',{});task=b.get('task_id');state=b.get('state')
            if state in ('COMPLETED','CANCELLED'):terminal[task]=state
            elif task:outstanding.add(task)
            for record in after.get('outbox',[]):
                candidate=record['record']['body'].get('task_id')
                if candidate!=task:unknown.add(candidate)
        completed={t.get('goal_id') for t in m.get('navigation_transitions',[]) if not t.get('initial') and t.get('state')=='COMPLETED'}
        failed={t.get('goal_id') for t in m.get('navigation_transitions',[]) if not t.get('initial') and t.get('state') in ('FAILED','HOLD','INTERRUPTED')}
        rounds.append({**m,'navigation_latency_ms_observed':stats(latencies),
            'backend_request_ms_including_retries':stats([s['backend_request_ms'] for s in snapshots]),
            'observed_completed_goals':len(completed),'observed_failed_or_hold_goals':len(failed),
            'completion_rate_per_second':len(completed)/m['duration_seconds'],
            'unresolved_latest_attempts':len(outstanding),'unknown_historical_tasks':len(unknown),
            'outbox_queue_depth':'UNAVAILABLE: retained envelopes have no delivery-dequeue marker; unresolved latest attempts counted separately',
            'journal_growth':growth,'actor_fence_conflicts':'inspect correlated G2 HOLD/HTTP_409 log; unavailable from tick samples alone',
            'limitations':bad,'status':'HOLD' if bad or not completed or m['status']=='HOLD' else 'MEASURED_NOT_ACCEPTED'})
    return {'status':'MEASURED_NOT_ACCEPTED' if rounds else 'PENDING / NOT TESTED','rounds':rounds,
        'coverage':{str(n):sum(r['npc_count']==n for r in rounds) for n in (1,3,5,10)},
        'requirements':'At least 3 rounds per count; same fixture/duration/hardware. Agree baseline/thresholds before acceptance.',
        'tick_metric':'Forge START-to-END event interval; separate server rolling average MSPT; profiler needed for full tick distribution.',
        'cpu_memory':'JVM heap sampled; CPU utilization/RSS unavailable unless separately measured.'}


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();a.output.write_text(json.dumps(summarize(a.directory),indent=2)+'\n',encoding='utf-8');print(a.output)
