"""B2-C regression: explicit UoW/connection lifetime and Windows HTTP 404."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sqlite3
import sys
import tempfile
from threading import Thread
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'backend' / 'src'))
from npcortex_backend.persistence import SQLiteDatabase
from npcortex_backend.server import make_http_server
from npcortex_backend.service import ChatService
from npcortex_backend.session import SessionStore
from npcortex_backend.world_store import WorldStore


class TrackedConnection(sqlite3.Connection):
    """Assert close() is called, not just __exit__() / garbage collection."""
    opened = 0
    closed = 0

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        type(self).opened += 1
        self._tracked_closed = False

    def close(self):
        if not self._tracked_closed:
            type(self).closed += 1
            self._tracked_closed = True
        return super().close()


class SQLiteLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'local.sqlite3'

    def test_read_write_transactions_and_rollback(self):
        store = SQLiteDatabase(self.path)
        with store.write() as db:
            db.execute('CREATE TABLE values_test (id INTEGER PRIMARY KEY, value TEXT)')
            db.execute('INSERT INTO values_test(value) VALUES (?)', ('safe',))
        with self.assertRaisesRegex(ValueError, 'abort'):
            with store.write() as db:
                db.execute('INSERT INTO values_test(value) VALUES (?)', ('bad',))
                raise ValueError('abort')
        with store.read() as db:
            self.assertEqual([row[0] for row in db.execute('SELECT value FROM values_test')], ['safe'])
        with self.assertRaises(sqlite3.OperationalError):
            with store.read() as db:
                db.execute('INSERT INTO values_test(value) VALUES (?)', ('not allowed',))
        with store.read() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM values_test').fetchone()[0], 1)

    def test_connections_are_explicitly_closed_on_success_and_failure(self):
        original = sqlite3.connect
        TrackedConnection.opened = TrackedConnection.closed = 0

        def tracked(*args, **kwargs):
            return original(*args, factory=TrackedConnection, **kwargs)

        with patch('npcortex_backend.persistence.sqlite3.connect', side_effect=tracked):
            store = WorldStore(self.path)
            world = store.register_world('Windows Test')
            a1 = store.register_actor(world['world_id'], 'minecraft:overworld', 'yui')
            a2 = store.register_actor(world['world_id'], 'minecraft:overworld', 'guide')
            cid = store.conversation_for(world['world_id'], a1['actor_id'], 'tester', 'minecraft:overworld')
            store.append_exchange(cid, 'hello', 'hi')
            self.assertEqual(store.get_conversation(cid)['messages'][0]['content'], 'hello')
            with self.assertRaises(ValueError):
                with store.database.write() as db:
                    db.execute('INSERT INTO worlds(world_id,name) VALUES (?,?)', ('broken', 'temp'))
                    raise ValueError('forced rollback')
            from npcortex_backend.dialogues import DialogueStore
            dialogue = DialogueStore(store)
            created = dialogue.create(world['world_id'], 'minecraft:overworld',
                                      [a1['actor_id'], a2['actor_id']], [], 'Hi', 4)
            self.assertEqual(created['status'], 'open')
            d_id = created['dialogue_id']
            from uuid import uuid4
            dialogue.append(d_id, str(uuid4()), 'actor', a1['actor_id'], 'first', 0)
            self.assertEqual(len(dialogue.get(d_id)['turns']), 1)
            dialogue.close(d_id)
            # Re-opening/migrating an existing database must also close connections.
            WorldStore(self.path)
        self.assertGreater(TrackedConnection.opened, 0)
        self.assertEqual(TrackedConnection.opened, TrackedConnection.closed)

    def test_concurrent_writes_are_serialized_without_lost_rows(self):
        store = WorldStore(self.path)
        world = store.register_world('Concurrent Test')
        def insert(i):
            return store.register_actor(world['world_id'], 'minecraft:overworld',
                                        'yui' if i % 2 else 'guide')
        with ThreadPoolExecutor(max_workers=8) as executor:
            actors = list(executor.map(insert, range(30)))
        self.assertEqual(len({a['actor_id'] for a in actors}), 30)
        with store.database.read() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM actors').fetchone()[0], 30)

    def test_maintenance_failure_closes_and_rolls_back(self):
        store = SQLiteDatabase(self.path)
        with store.maintenance() as db:
            db.execute('CREATE TABLE fixture (n INTEGER)')
        with self.assertRaises(sqlite3.OperationalError):
            with store.maintenance() as db:
                db.executescript('BEGIN IMMEDIATE; INSERT INTO fixture VALUES (5); '
                                 'INSERT INTO missing_table VALUES (8); COMMIT;')
        with store.read() as db:
            self.assertEqual(db.execute('SELECT COUNT(*) FROM fixture').fetchone()[0], 0)


class HTTPBadRouteTests(unittest.TestCase):
    def setUp(self):
        provider = Mock()
        provider.chat.return_value = 'Hello'
        service = ChatService(ROOT / 'character-packs', 'qwen3:8b', provider, SessionStore())
        self.httpd = make_http_server(service)
        self.thread = Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)
        self.base = f'http://127.0.0.1:{self.httpd.server_port}'

    def stop(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=3)

    def test_unknown_post_with_body_returns_json_404_many_times(self):
        for _ in range(25):
            req = Request(self.base + '/nope', method='POST', data=b'{"hi": "there"}',
                          headers={'Content-Type': 'application/json'})
            with self.assertRaises(HTTPError) as error:
                urlopen(req, timeout=3)
            with error.exception as response:
                self.assertEqual(response.code, 404)
                self.assertEqual(response.read().decode('utf-8').count('not_found'), 1)

    def test_http_server_can_shutdown_and_close_while_idle(self):
        with urlopen(self.base + '/health', timeout=3) as response:
            self.assertEqual(response.status, 200)
        self.assertTrue(self.thread.is_alive())


if __name__ == '__main__':
    unittest.main()
