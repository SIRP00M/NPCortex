"""Real owned backend process + socket response loss; isolated synthetic actors.
Never uses the operator's SQLite, token, game world or running backend process.
These are protocol tests, NOT Forge/Windows gameplay acceptance.
"""
from pathlib import Path
from contextlib import closing
import argparse
import copy
import http.client
import json
import os
import secrets
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'backend/tests'))
sys.path.insert(0, str(ROOT / 'backend/src'))
from test_cplus_e3_g2_motion import body, ack
from test_cplus_e3_g3_http import G3LifecycleFixture


def other_actor_body():
    value=body()
    value['actor_id']=value['snapshot']['actor_id']=str(uuid4())
    value['snapshot']['observation_id']=str(uuid4())
    value['snapshot']['permission']['evidence_id']=value['snapshot']['observation_id']
    return value


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class OwnedBackend:
    """Only terminates its retained Popen handle; no PID adoption or process scan."""
    def __init__(self, directory, port=None, database=None, private=True):
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.port = port or free_port()
        self.db = Path(database).resolve() if database else self.directory / 'isolated.sqlite3'
        self.token = secrets.token_hex(32) if private else None
        self.env = os.environ.copy()
        self.env.update(PYTHONPATH=str(ROOT / 'backend/src'), NPCORTEX_HOST='127.0.0.1',
                        NPCORTEX_PORT=str(self.port), NPCORTEX_DB_PATH=str(self.db),
                        NPCORTEX_PACKS_DIR=str(ROOT / 'character-packs'))
        if private:
            self.env['NPCORTEX_CPLUS_F1_TOKEN'] = self.token
        self.process = None
        self.log = None
        self.generations = []

    def start(self):
        if self.process is not None:
            raise RuntimeError('owned process already exists')
        # Refuse an occupied port BEFORE starting; never adopt a responding service.
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', self.port))
        self.log = (self.directory / 'backend.log').open('ab')
        self.process = subprocess.Popen([sys.executable, '-m', 'npcortex_backend'],
            cwd=ROOT, env=self.env, stdout=self.log, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        self.generations.append(self.process.pid)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                self.stop()
                raise RuntimeError('owned backend exited; inspect local log')
            try:
                c = http.client.HTTPConnection('127.0.0.1', self.port, timeout=.5)
                try:
                    c.request('GET', '/health')
                    r = c.getresponse()
                    if r.status == 200:
                        r.read()
                        return
                finally:
                    c.close()
            except OSError:
                pass
            time.sleep(.1)
        self.stop()
        raise TimeoutError('owned backend readiness timeout')

    def stop(self):
        if self.process is None:
            return
        try:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(10)
                except subprocess.TimeoutExpired:
                    self.process.kill()  # ONLY this owned child handle
                    self.process.wait(10)
        finally:
            self.process = None
            self.log.close()
            self.log = None

    def post(self, route, payload, port=None):
        c = http.client.HTTPConnection('127.0.0.1', port or self.port, timeout=5)
        try:
            c.request('POST', '/v1/cplus/e3/' + route, json.dumps(payload),
                {'Content-Type': 'application/json', 'X-NPCortex-CPlus-Token': self.token})
            r = c.getresponse()
            return r.status, json.loads(r.read())
        finally:
            c.close()

    def rows(self, table):
        if table not in {'cplus_e3_g2_moves', 'cplus_e3_g3_terminals', 'cplus_e3_g3_cancel_intents'}:
            raise ValueError('table')
        with closing(sqlite3.connect(self.db.as_uri() + '?mode=ro', uri=True)) as db:
            db.row_factory = sqlite3.Row
            return [dict(r) for r in db.execute('SELECT * FROM ' + table)]


class DropAfterCommit:
    """Bound to one route AND one task. Drop after upstream returns HTTP 200.
    Separate from never-sent injection. Other routes/tasks get normal replies.
    """
    def __init__(self, backend, route, task):
        self.events = []
        outer = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                c = http.client.HTTPConnection('127.0.0.1', backend.port, timeout=5)
                try:
                    c.request('POST', self.path, json.dumps(payload),
                        {'Content-Type': 'application/json', 'X-NPCortex-CPlus-Token': backend.token})
                    response = c.getresponse()
                    data = response.read()
                    if self.path == '/v1/cplus/e3/' + route and payload.get('task_id') == task and response.status == 200:
                        outer.events.append({'route': route, 'task_id': task, 'upstream_status': 200,
                                             'response': json.loads(data), 'payload': payload})
                        self.close_connection = True
                        self.connection.shutdown(socket.SHUT_RDWR)
                        self.connection.close()
                        return
                    self.send_response(response.status)
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                finally:
                    c.close()
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)


EVIDENCE = None


class ProcessChecks(unittest.TestCase):
    def setUp(self):
        self.path = EVIDENCE / (self._testMethodName + '-' + str(uuid4()))
        self.backend = OwnedBackend(self.path)
        self.backend.start()
        self.addCleanup(self.backend.stop)
        self.b = body()

    def post(self, route, payload, expected=200):
        code, result = self.backend.post(route, payload)
        self.assertEqual(code, expected, result)
        return result

    def status(self, b=None):
        b = b or self.b
        return self.post('status', {k: b[k] for k in ('world_id', 'actor_id')})

    def identity(self, report):
        return {**{k:self.b[k] for k in ('task_id','attempt_id','requester_id','world_id','actor_id','session_id')},
                'goal_id': report['goal_id']}

    def test_6b_real_process_restart(self):
        reserved = self.post('reserve', self.b)
        # Durable synthetic client envelope, explicitly not Forge SavedData.
        envelope = {'body': self.b, 'route': '/v1/cplus/e3/reserve', 'handoff_possible': False}
        journal = self.path / 'synthetic-client-envelope.json'
        journal.write_text(json.dumps(envelope, indent=2))
        original = journal.read_bytes()
        accepted_body = other_actor_body()
        self.post('reserve', accepted_body)
        report = ack(accepted_body)
        self.post('report', report)
        self.backend.stop()
        with self.assertRaises(OSError):
            self.status()
        self.backend.start()  # identical env, DB path, token, port
        held = self.status()
        for key in ('task_id','attempt_id','world_id','actor_id','session_id'):
            self.assertEqual(held[key], reserved[key])
        self.assertEqual(held['state'], 'HOLD')
        self.assertFalse(held['can_execute'])
        accepted = self.status(accepted_body)
        self.assertEqual(accepted['state'], 'HOLD')
        self.assertEqual(accepted['goal_id'], report['goal_id'])
        self.post('report', report, 409)
        self.assertEqual(journal.read_bytes(), original)
        self.assertEqual(len(self.backend.rows('cplus_e3_g2_moves')), 2)
        (self.path/'result.json').write_text(json.dumps({'status':'AUTOMATED PASS','scope':'backend process only',
            'gameplay':'PENDING','owned_process_generations':self.backend.generations,
            'unconfirmed':held,'accepted':accepted,'recovery_disposition':'HOLD; no automatic movement replay'},indent=2))

    def lose(self, route, payload):
        with DropAfterCommit(self.backend, route, self.b['task_id']) as proxy:
            for _ in range(2):  # retry the EXACT original metadata payload
                with self.assertRaises((OSError, http.client.HTTPException)):
                    self.backend.post(route, payload, proxy.server.server_port)
            self.assertEqual(len(proxy.events), 2)
            self.assertEqual(proxy.events[0]['payload'], proxy.events[1]['payload'])
            (self.path/(route+'-commit-loss.json')).write_text(json.dumps(proxy.events,indent=2))

    def test_7_reserve_loss_idempotency_stale_identity(self):
        self.lose('reserve', self.b)
        self.assertEqual(self.status()['state'], 'RESERVED')
        self.assertEqual(len(self.backend.rows('cplus_e3_g2_moves')), 1)
        p={k:self.b[k] for k in ('task_id','attempt_id','requester_id','world_id','actor_id','session_id')}
        p['no_handoff']=True
        for key in ('attempt_id','actor_id','session_id','requester_id'):
            self.post('abandon',{**p,key:str(uuid4())},409)
        self.assertEqual(self.post('abandon',p)['state'],'CANCELLED')
        self.assertEqual(self.post('abandon',p)['state'],'CANCELLED')
        self.assertEqual(len(self.backend.rows('cplus_e3_g3_terminals')),1)

    def test_7_terminal_loss_no_contradictory_effect(self):
        self.post('reserve',self.b)
        report=ack(self.b)
        self.post('report',report)
        ident=self.identity(report)
        self.post('cancel-begin',ident)
        proof={**ident,'action_id':None,'c2_state':'CANCELLED','c1_stopped':True}
        self.lose('cancel-ack',proof)
        self.assertEqual(self.status()['state'],'CANCELLED')
        self.assertEqual(len(self.backend.rows('cplus_e3_g3_terminals')),1)
        self.post('cancel-ack',{**proof,'goal_id':str(uuid4())},409)
        self.post('finalize',G3LifecycleFixture(self.b,report['goal_id']).candidate(),409)
        self.backend.stop();self.backend.start()
        self.assertEqual(self.status()['state'],'CANCELLED')

    def test_7_finalize_loss_and_foreign_actor_isolation(self):
        self.post('reserve',self.b)
        report=ack(self.b);self.post('report',report)
        foreign=other_actor_body();self.post('reserve',foreign)
        before=self.status(foreign)
        candidate=G3LifecycleFixture(self.b,report['goal_id']).candidate()
        self.lose('finalize',candidate)
        self.assertEqual(self.status()['state'],'COMPLETED')
        self.assertEqual(self.status(foreign),before)
        self.assertEqual(len(self.backend.rows('cplus_e3_g3_terminals')),1)
        self.backend.stop();self.backend.start()
        self.assertEqual(self.status()['state'],'COMPLETED')


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--evidence',type=Path,required=True)
    parser.add_argument('--gate',choices=['6b','7'],required=True)
    args=parser.parse_args()
    EVIDENCE=args.evidence.resolve();EVIDENCE.mkdir(parents=True,exist_ok=True)
    names=[n for n in unittest.defaultTestLoader.getTestCaseNames(ProcessChecks) if n.startswith('test_'+args.gate)]
    result=unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite(ProcessChecks(n) for n in names))
    sys.exit(0 if result.wasSuccessful() else 1)
