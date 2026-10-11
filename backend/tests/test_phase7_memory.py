"""Regression coverage for persistent memory, memory management and AI actions."""
import http.client
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend' / 'src'))
from npcortex_backend.memory import MemoryStore
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.server import make_handler
from npcortex_backend.agent import parse_agent_result, ALLOWED_ACTIONS
from npcortex_backend.ollama import ModelError


class MemoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'db.sqlite3'
        self.store = MemoryStore(self.path)

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def test_survives_restart(self):
        self.store.record('player', 'yui:npc1', 'yui', 'Hello', 'Hi!')
        self.store.add_note('player', 'yui:npc1', 'Likes to explore caves')
        self.store.close()
        self.store = MemoryStore(self.path)
        self.assertEqual(self.store.history('player','yui:npc1')[-1]['content'], 'Hi!')
        self.assertIn('caves', self.store.notes_for_prompt('player', 'yui:npc1'))
        self.assertEqual(self.store.snapshot('player','yui:npc1')['affinity'], 1)

    def test_strict_npc_and_player_isolation(self):
        self.store.add_note('p1', 'yui:npc1', 'secret note')
        self.store.record('p1', 'yui:npc1', 'yui', 'hello', 'hi')
        for scope in [('p1','yui:npc2'), ('p2','yui:npc1'), ('p1','guide:npc1')]:
            self.assertEqual(self.store.snapshot(*scope)['notes'], [])
            self.assertEqual(self.store.snapshot(*scope)['chats'], 0)

    def test_note_delete_scoped_and_clear_only_notes(self):
        ident = self.store.add_note('p1', 'yui:n1', 'one')
        self.store.record('p1','yui:n1','yui','hi','hey')
        self.assertFalse(self.store.remove_note('p2','yui:n1',ident))
        self.store.clear_notes('p1','yui:n1')
        self.assertEqual(self.store.snapshot('p1','yui:n1')['notes'], [])
        self.assertEqual(self.store.snapshot('p1','yui:n1')['affinity'], 1)
        self.store.reset_relationship('p1','yui:n1')
        self.assertEqual(self.store.snapshot('p1','yui:n1')['affinity'], 0)
        self.assertEqual(len(self.store.history('p1','yui:n1')), 2)

    def test_notes_are_bounded(self):
        with self.assertRaises(ValueError): self.store.add_note('p','n','x'*301)
        for i in range(30): self.store.add_note('p','n',f'Note {i}')
        with self.assertRaises(ValueError): self.store.add_note('p','n','over the limit')

    def test_service_includes_saved_data_and_uses_db_history(self):
        provider = Mock()
        provider.chat.side_effect = ['Hello!', 'Welcome back!']
        svc = ChatService(ROOT/'character-packs','qwen3:8b',provider,SessionStore(),self.store)
        svc.change_memory('player','yui:npc1','add','The player likes pizza')
        svc.chat('yui','player','Hello','npc1')
        svc.chat('yui','player','Back again','npc1')
        used = provider.chat.call_args.args[0]
        self.assertIn('pizza', used[0]['content'])
        self.assertIn({'role':'assistant','content':'Hello!'},used)
        self.assertEqual(svc.memory_snapshot('player','yui:npc1')['chats'], 2)

    def test_new_actions_are_validated(self):
        self.assertTrue({'jump','pickup_nearby','equip_item','wander','drop_held_item','move_to_player'} <= ALLOWED_ACTIONS)
        self.assertEqual(parse_agent_result('{"reply":"Jumping","action":"jump"}')['action'],'jump')
        with self.assertRaises(ModelError):
            parse_agent_result('{"reply":"okay","action":"delete_inventory"}')

    def test_http_memory_read_add_delete_rejects_invalid(self):
        provider = Mock()
        service = ChatService(ROOT/'character-packs','qwen3:8b',provider,SessionStore(),self.store)
        httpd = ThreadingHTTPServer(('127.0.0.1',0),make_handler(service))
        t = threading.Thread(target=httpd.serve_forever,daemon=True)
        t.start()
        try:
            c = http.client.HTTPConnection('127.0.0.1',httpd.server_port,timeout=5)
            scope = {'player_id':'p1','session_id':'npc1','character_id':'yui'}
            def post(extra):
                c.request('POST','/v1/memory',json.dumps(dict(scope,**extra)),{'Content-Type':'application/json'})
                r = c.getresponse()
                return r.status,json.loads(r.read())
            status, data = post({'operation':'add','text':'likes pumpkins'})
            self.assertEqual(status,200)
            note_id = data['notes'][0]['id']
            self.assertEqual(post({'operation':'delete','note_id':note_id})[0],200)
            self.assertEqual(post({'operation':'explode'})[0],400)
            self.assertEqual(post({'operation':'add','text':'x'*301})[0],400)
            self.assertEqual(post({'operation':'delete','note_id':True})[0],400)
            c.request('GET','/v1/memory?player_id=p1&session_id=npc1&character_id=yui')
            r = c.getresponse()
            self.assertEqual(r.status,200)
            self.assertEqual(json.loads(r.read())['notes'],[])
            c.close()
        finally:
            httpd.shutdown();httpd.server_close();t.join(timeout=2)


if __name__ == '__main__': unittest.main()
