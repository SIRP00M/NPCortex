"""B3 bounded headless runtime: real SQLite, faked deterministic AI, HTTP API."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from http.server import ThreadingHTTPServer
from threading import Thread
from unittest.mock import Mock, patch
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4
import json
import sqlite3
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend' / 'src'))
from npcortex_backend.identity import IdentityError
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.world_store import WorldStore, RegistryConflictError, SCHEMA_VERSION
from npcortex_backend.ollama import ModelError
from npcortex_backend.server import make_handler, make_http_server
from npcortex_backend.social import SocialStore
from npcortex_backend.dialogues import DialogueStore
from sqlite_test_utils import sqlite_test_connection


class FakeSocialAI:
    def __init__(self):
        self.prompts = []
        self.decisions = []
        self.invalid_decisions = 0
        self.model_fail = False
        self.stop_after = None

    def chat(self, messages):
        self.prompts.append(messages)
        system = messages[0]['content']
        if 'turn-taking coordinator' in system:
            if self.model_fail:
                raise ModelError('AI unavailable')
            context = json.loads(messages[1]['content'])
            self.decisions.append(context)
            if self.invalid_decisions:
                self.invalid_decisions -= 1
                return 'not valid json'
            if self.stop_after is not None and len(context['recent_shared_turns']) >= self.stop_after:
                return json.dumps({'action': 'stop'})
            return json.dumps({'action': 'speak', 'actor_id': context['allowed_next_actor_ids'][0]})
        try:
            context = json.loads(messages[-1]['content'])
        except json.JSONDecodeError:
            return 'An ordinary private B1 reply'
        return 'Speech from ' + context['next_speaker_actor_id']


class B3Tests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / 'db.sqlite3'
        self.ai = FakeSocialAI()
        self.service = self.make_service(self.ai)
        self.world = self.service.register_world('Test world')
        self.yui1 = self.service.register_actor(self.world['world_id'], 'minecraft:overworld', 'yui')
        self.yui2 = self.service.register_actor(self.world['world_id'], 'minecraft:overworld', 'yui')
        self.guide = self.service.register_actor(self.world['world_id'], 'minecraft:overworld', 'guide')

    def make_service(self, ai):
        return ChatService(ROOT/'character-packs', 'fake', ai, SessionStore(), world_store=WorldStore(self.path))

    def create_dialogue(self, actors=None, players=None, max_turns=12):
        return self.service.create_dialogue(self.world['world_id'], 'minecraft:overworld',
                [x['actor_id'] for x in (actors or [self.yui1, self.yui2, self.guide])],
                players or [], 'A friendly introduction', max_turns)

    def create_run(self, steps=4, dialogue=None):
        d = dialogue or self.create_dialogue()
        return self.service.create_social_run(d['dialogue_id'], steps)

    def step(self, run, rid=None):
        return self.service.social_step(run['run_id'], rid or str(uuid4()))

    def test_model_selects_and_generates_distinct_speakers(self):
        run = self.create_run(4)
        for _ in range(4):
            response = self.step(run)
            self.assertEqual(response['step']['action'], 'speak')
            self.assertEqual(response['turn']['speaker_id'], response['step']['speaker_id'])
        saved = self.service.get_social_run(run['run_id'])
        self.assertEqual(saved['status'], 'completed')
        self.assertEqual(saved['steps_completed'], 4)
        self.assertEqual(len(self.service.get_dialogue(run['dialogue_id'])['turns']), 4)
        self.assertEqual(self.service.get_dialogue(run['dialogue_id'])['status'], 'closed')
        seq = [s['speaker_id'] for s in saved['steps']]
        self.assertTrue(all(a != b for a,b in zip(seq,seq[1:])))
        self.assertTrue(all(sid in {self.yui1['actor_id'],self.yui2['actor_id'],self.guide['actor_id']} for sid in seq))

    def test_request_idempotent_replay_across_restart(self):
        run = self.create_run()
        request_id = str(uuid4())
        before = self.step(run, request_id)
        restarted_ai = FakeSocialAI()
        restarted = self.make_service(restarted_ai)
        after = restarted.social_step(run['run_id'], request_id)
        self.assertTrue(after['replayed'])
        self.assertEqual(after['turn'], before['turn'])
        self.assertEqual(restarted_ai.prompts, [])
        self.assertEqual(restarted.get_social_run(run['run_id'])['steps_completed'], 1)

    def test_restart_and_resume_new_step(self):
        run = self.create_run()
        self.step(run)
        restarted = self.make_service(FakeSocialAI())
        result = restarted.social_step(run['run_id'], str(uuid4()))
        self.assertEqual(result['turn']['seq'], 2)
        self.assertEqual(restarted.get_social_run(run['run_id'])['steps_completed'], 2)

    def test_model_can_stop_after_two_turns(self):
        self.ai.stop_after = 2
        run = self.create_run(6)
        self.step(run); self.step(run)
        stop = self.step(run)
        self.assertEqual(stop['step']['action'], 'stop')
        self.assertEqual(stop['status'], 'stopped')
        self.assertEqual(len(self.service.get_dialogue(run['dialogue_id'])['turns']), 2)
        self.assertTrue(self.step(run, stop['step']['request_id'])['replayed'])
        with self.assertRaises(RegistryConflictError):
            self.step(run)

    def test_planner_invalid_output_retries_with_no_fallback_speaker(self):
        self.ai.invalid_decisions = 1
        run = self.create_run()
        self.step(run)
        self.assertEqual(len(self.ai.decisions), 2)
        self.assertEqual(self.service.get_social_run(run['run_id'])['steps_completed'], 1)

    def test_planner_invalid_twice_does_not_write_turn(self):
        run = self.create_run()
        self.ai.invalid_decisions = 2
        with self.assertRaises(ModelError):
            self.step(run)
        self.assertEqual(len(self.service.get_dialogue(run['dialogue_id'])['turns']), 0)
        self.assertEqual(self.service.get_social_run(run['run_id'])['consecutive_failures'], 1)
        self.step(run)
        self.assertEqual(self.service.get_social_run(run['run_id'])['consecutive_failures'], 0)

    def test_provider_failure_budget_closes_run_after_three(self):
        run = self.create_run()
        self.ai.model_fail = True
        for _ in range(3):
            with self.assertRaises(ModelError):
                self.step(run)
        self.assertEqual(self.service.get_social_run(run['run_id'])['status'], 'failed')
        self.assertEqual(self.service.get_dialogue(run['dialogue_id'])['status'], 'closed')
        self.assertEqual(len(self.service.get_dialogue(run['dialogue_id'])['turns']), 0)
        with self.assertRaises(RegistryConflictError):
            self.step(run)

    def test_two_identical_character_packs_never_share_private_history(self):
        self.service.chat_actor(self.world['world_id'], 'minecraft:overworld', self.yui1['actor_id'],
                                'p1', 'SECRET_PRIVATE_WORD')
        self.ai.prompts.clear()
        run = self.create_run()
        self.step(run)
        self.assertNotIn('SECRET_PRIVATE_WORD', json.dumps(self.ai.prompts))

    def test_b2_manual_turn_not_allowed_while_social_run_active(self):
        run = self.create_run()
        with self.assertRaises(RegistryConflictError):
            self.service.dialogue_turn(run['dialogue_id'], 'actor', self.yui1['actor_id'])
        self.assertEqual(len(self.service.get_dialogue(run['dialogue_id'])['turns']), 0)
        self.service.stop_social_run(run['run_id'])
        self.assertEqual(self.service.get_dialogue(run['dialogue_id'])['status'], 'closed')

    def test_external_b2_close_ends_social_run(self):
        run = self.create_run()
        self.service.close_dialogue(run['dialogue_id'])
        self.assertEqual(self.service.get_social_run(run['run_id'])['status'], 'stopped')

    def test_invalid_run_creation_never_persists(self):
        with self.assertRaises(RegistryConflictError):
            self.create_run(dialogue=self.create_dialogue(players=['p1']))
        d = self.create_dialogue()
        for val in (0, 1, 25, True, '4', 14):
            with self.subTest(value=val), self.assertRaises(IdentityError):
                self.create_run(val, d)
        self.service.dialogue_turn(d['dialogue_id'], 'actor', self.yui1['actor_id'])
        with self.assertRaises(RegistryConflictError):
            self.create_run(dialogue=d)

    def test_two_runs_cannot_own_one_dialogue(self):
        d = self.create_dialogue()
        self.create_run(dialogue=d)
        with self.assertRaises(RegistryConflictError):
            self.create_run(dialogue=d)

    def test_concurrent_step_requests_are_serialized(self):
        run = self.create_run(4)
        def task():
            return self.step(run)
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(lambda _: task(), range(2)))
        self.assertEqual(sorted(r['turn']['seq'] for r in responses), [1,2])
        self.assertEqual(self.service.get_social_run(run['run_id'])['steps_completed'], 2)

    def test_partial_commit_replay_recovers_without_new_model_call(self):
        run = self.create_run()
        rid = str(uuid4())
        # Emulate process terminating between B2 transcript commit and B3 step journal.
        actor_id = self.yui1['actor_id']
        with self.service.sessions.conversation('b2-dialogue', run['dialogue_id']):
            turn = self.service._dialogue_turn_locked(run['dialogue_id'], 'actor', actor_id, None, rid)
        ai = FakeSocialAI()
        restarted = self.make_service(ai)
        recovered = restarted.social_step(run['run_id'], rid)
        self.assertTrue(recovered['replayed'])
        self.assertEqual(recovered['turn']['seq'], turn['turn']['seq'])
        self.assertEqual(ai.prompts, [])
        self.assertEqual(restarted.get_social_run(run['run_id'])['steps_completed'], 1)

    def test_failed_journal_write_replays_existing_dialogue_turn(self):
        run = self.create_run()
        rid = str(uuid4())
        with patch.object(SocialStore, 'finish', side_effect=sqlite3.OperationalError('simulated disk error')):
            with self.assertRaises(sqlite3.OperationalError):
                self.step(run, rid)
        count = len(self.ai.prompts)
        result = self.step(run, rid)
        self.assertTrue(result['replayed'])
        self.assertEqual(len(self.ai.prompts), count)
        self.assertEqual(self.service.get_social_run(run['run_id'])['steps_completed'], 1)

    def test_new_step_cannot_skip_unjournaled_turn(self):
        run = self.create_run()
        rid = str(uuid4())
        with self.service.sessions.conversation('b2-dialogue', run['dialogue_id']):
            self.service._dialogue_turn_locked(run['dialogue_id'], 'actor', self.yui1['actor_id'], None, rid)
        with self.assertRaisesRegex(RegistryConflictError, 'Previous social turn'):
            self.step(run, str(uuid4()))
        self.assertTrue(self.step(run, rid)['replayed'])
        self.assertEqual(self.step(run)['turn']['seq'], 2)

    def test_invalid_step_id_is_rejected(self):
        run = self.create_run()
        with self.assertRaises(IdentityError):
            self.step(run, 'not-uuid')

    def test_open_dialogue_kept_closed_on_budget(self):
        dialogue = self.create_dialogue(max_turns=12)
        run = self.create_run(2, dialogue)
        self.step(run); final = self.step(run)
        self.assertEqual(final['status'], 'completed')
        self.assertEqual(self.service.get_dialogue(dialogue['dialogue_id'])['status'], 'closed')
        with self.assertRaises(RegistryConflictError):
            self.step(run)

    def test_schema_v3_additive_migration_from_v2_with_data(self):
        self.assertEqual(SCHEMA_VERSION, 3)
        d = self.create_dialogue()
        self.service.dialogue_turn(d['dialogue_id'], 'actor', self.yui1['actor_id'])
        with sqlite_test_connection(self.path) as db:
            db.execute('DROP TABLE social_steps')
            db.execute('DROP TABLE social_runs')
            db.execute('PRAGMA user_version=2')
        upgraded = self.make_service(FakeSocialAI())
        self.assertEqual(len(upgraded.get_dialogue(d['dialogue_id'])['turns']), 1)
        with sqlite_test_connection(self.path) as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 3)
            self.assertEqual(db.execute('SELECT COUNT(*) FROM actors').fetchone()[0], 3)


class B3HTTPTests(B3Tests):
    def setUp(self):
        super().setUp()
        server = make_http_server(self.service, '127.0.0.1', 0)
        self.addCleanup(server.server_close)
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(lambda: (server.shutdown(), thread.join(timeout=4)))
        self.url = f'http://127.0.0.1:{server.server_port}'

    def request(self, method, path, data=None):
        raw = json.dumps(data).encode() if data is not None else None
        req = Request(self.url+path, raw, {'Content-Type':'application/json'}, method=method)
        try:
            with urlopen(req, timeout=10) as res:
                return res.status,json.loads(res.read())
        except HTTPError as ex:
            return ex.code,json.loads(ex.read())

    def test_http_social_flow_and_legacy_b2(self):
        d = self.create_dialogue()
        status,run = self.request('POST','/v1/social-runs',{'dialogue_id':d['dialogue_id'],'max_steps':2})
        self.assertEqual(status,201)
        rid = str(uuid4())
        status,first = self.request('POST',f'/v1/social-runs/{run["run_id"]}/step',{'request_id':rid})
        self.assertEqual(status,200)
        self.assertEqual(first['turn']['seq'],1)
        _, replay = self.request('POST',f'/v1/social-runs/{run["run_id"]}/step',{'request_id':rid})
        self.assertTrue(replay['replayed'])
        _, second = self.request('POST',f'/v1/social-runs/{run["run_id"]}/step',{'request_id':str(uuid4())})
        self.assertEqual(second['status'],'completed')
        _,saved = self.request('GET',f'/v1/social-runs/{run["run_id"]}')
        self.assertEqual(saved['steps_completed'],2)
        _,original = self.request('GET',f'/v1/dialogues/{d["dialogue_id"]}')
        self.assertEqual(len(original['turns']),2)

    def test_http_errors_and_manual_stop(self):
        d = self.create_dialogue()
        _,run = self.request('POST','/v1/social-runs',{'dialogue_id':d['dialogue_id']})
        status,error = self.request('POST',f'/v1/social-runs/{run["run_id"]}/step',{})
        self.assertEqual((status,error['code']), (400,'invalid_request'))
        status,error = self.request('POST',f'/v1/social-runs/{run["run_id"]}/step',{'request_id':'bad'})
        self.assertEqual((status,error['code']), (400,'invalid_identity'))
        status,error = self.request('POST',f'/v1/dialogues/{d["dialogue_id"]}/turns',
                                    {'speaker_type':'actor','speaker_id':self.yui1['actor_id']})
        self.assertEqual((status,error['code']), (409,'identity_conflict'))
        status,stop = self.request('POST',f'/v1/social-runs/{run["run_id"]}/stop',{})
        self.assertEqual(status,200)
        self.assertEqual(stop['status'],'stopped')
        status,error = self.request('POST',f'/v1/social-runs/{run["run_id"]}/step',{'request_id':str(uuid4())})
        self.assertEqual((status,error['code']),(409,'identity_conflict'))


if __name__=='__main__':
    unittest.main()

class B3RealHTTPTransportTest(unittest.TestCase):
    def test_ollama_json_mode_for_planner_plain_chat_for_actor(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        from npcortex_backend.ollama import OllamaProvider
        received = []
        class OllamaStub(BaseHTTPRequestHandler):
            def do_POST(self):
                data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                received.append(data)
                if 'format' in data:
                    context = json.loads(data['messages'][-1]['content'])
                    reply = json.dumps({'action':'speak', 'actor_id':context['allowed_next_actor_ids'][0]})
                else:
                    reply = 'NPC greeting through local HTTP'
                raw = json.dumps({'message': {'content': reply}}).encode()
                self.send_response(200)
                self.send_header('Content-Length',str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            def log_message(self,*_):
                pass
        httpd = ThreadingHTTPServer(('127.0.0.1',0),OllamaStub)
        th=Thread(target=httpd.serve_forever,daemon=True)
        th.start()
        try:
            with tempfile.TemporaryDirectory() as tmp:
                provider=OllamaProvider(f'http://127.0.0.1:{httpd.server_port}','qwen3:8b')
                svc=ChatService(ROOT/'character-packs','qwen3:8b',provider,SessionStore(),
                                world_store=WorldStore(Path(tmp)/'db.sqlite3'))
                world=svc.register_world('Transport test')
                a=svc.register_actor(world['world_id'],'minecraft:overworld','yui')
                b=svc.register_actor(world['world_id'],'minecraft:overworld','guide')
                dialogue=svc.create_dialogue(world['world_id'],'minecraft:overworld',
                    [a['actor_id'],b['actor_id']],[],'Introduce yourselves',max_turns=4)
                run=svc.create_social_run(dialogue['dialogue_id'],2)
                result=svc.social_step(run['run_id'],str(uuid4()))
                self.assertEqual(result['turn']['content'], 'NPC greeting through local HTTP')
                self.assertEqual(len(received),2)
                self.assertEqual(received[0]['format'],'json')
                self.assertNotIn('format',received[1])
                self.assertFalse(received[0]['think'])
                self.assertFalse(received[1]['stream'])
        finally:
            httpd.shutdown();httpd.server_close();th.join(timeout=3)
