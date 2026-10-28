"""World-effect NO-OP routes: shared secret required, typed bodies, 409 on conflict, full reserve->start->finalize flow."""
import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from npcortex_backend.server import make_http_server
from test_cplus_we_ledger import ident, receipt, reserve_body

TOKEN = 'a-long-local-world-effect-security-test-secret'


class WorldEffectHTTP(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        db = Path(self.temp.name) / 'world.db'
        service = SimpleNamespace(_require_world_store=lambda: SimpleNamespace(db_path=db), model='mock')
        self.server = make_http_server(service, port=0)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.worker.join(5); self.temp.cleanup()

    def post(self, path, payload, token=TOKEN):
        with patch('npcortex_backend.server.read_secret', return_value=TOKEN):
            c = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
            headers = {'Content-Type': 'application/json'}
            if token is not None:
                headers['X-NPCortex-CPlus-Token'] = token
            c.request('POST', path, json.dumps(payload), headers)
            r = c.getresponse()
            result = r.status, json.loads(r.read())
            c.close()
            return result

    def test_secret_is_required_on_every_route(self):
        b = reserve_body()
        for route in ('reserve', 'start', 'finalize', 'cancel', 'hold', 'reconcile', 'status'):
            self.assertEqual(self.post('/v1/cplus/we/' + route, b, 'wrong')[0], 403, route)
            self.assertEqual(self.post('/v1/cplus/we/' + route, b, None)[0], 403, route)
        self.assertEqual(self.post('/v1/cplus/we/status', {'world_id': b['world_id'], 'actor_id': b['actor_id']}, 'wrong')[0], 403)

    def test_full_flow_over_http(self):
        b = reserve_body()
        code, r = self.post('/v1/cplus/we/reserve', b)
        self.assertEqual((code, r['state'], r['native_mutation']), (200, 'RESERVED', False))
        code, r = self.post('/v1/cplus/we/start', ident(b, 'requester_id'))
        self.assertEqual((code, r['state'], r['execution_started']), (200, 'EXECUTING', True))
        code, r = self.post('/v1/cplus/we/finalize', receipt(b))
        self.assertEqual((code, r['state'], r['durable_ack'], r['release_actor_fence']), (200, 'NO_EFFECT_VERIFIED', True, True))
        self.assertEqual(self.post('/v1/cplus/we/finalize', receipt(b))[1], r)               # lost ACK replay
        code, r = self.post('/v1/cplus/we/status', {'world_id': b['world_id'], 'actor_id': b['actor_id']})
        self.assertEqual((code, r['state'], r['active']), (200, 'NO_EFFECT_VERIFIED', False))

    def test_conflicts_and_malformed_bodies(self):
        b = reserve_body()
        self.assertEqual(self.post('/v1/cplus/we/start', ident(b, 'requester_id'))[0], 409)     # unknown task
        self.assertEqual(self.post('/v1/cplus/we/reserve', {**b, 'origin': 'CONSOLE'})[0], 409)
        self.assertEqual(self.post('/v1/cplus/we/reserve', {**b, 'action_kind': 'break_block'})[0], 409)
        self.assertEqual(self.post('/v1/cplus/we/status', {'world_id': b['world_id']})[0], 400)
        self.assertEqual(self.post('/v1/cplus/we/reserve', b)[0], 200)
        second = reserve_body(world=b['world_id'], actor=b['actor_id'], session=b['session_id'])
        self.assertEqual(self.post('/v1/cplus/we/reserve', second)[0], 409)                      # fence
        self.assertEqual(self.post('/v1/cplus/we/finalize', receipt(b))[0], 409)                 # not started


if __name__ == '__main__':
    unittest.main()
