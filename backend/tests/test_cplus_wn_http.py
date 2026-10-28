"""Native world-effect routes: shared secret required on every route, typed bodies, 409 on conflict, the full binding ->
reserve -> start -> finalize flow over real HTTP on a temporary database, replay, and separation from the no-op routes."""
import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from npcortex_backend.server import WN_ROUTES, make_http_server
from test_cplus_wn_store import break_evidence, ident, receipt, reserve_body, uid

TOKEN = 'a-long-local-native-effect-security-test-secret'


class NativeEffectHTTP(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        db = Path(self.temp.name) / 'world.db'
        service = SimpleNamespace(_require_world_store=lambda: SimpleNamespace(db_path=db), model='mock')
        self.server = make_http_server(service, port=0)
        self.worker = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.worker.start()
        self.world, self.session = uid(), uid()

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

    def bind(self, epoch=0, folder='W1', session=None):
        return self.post('/v1/cplus/wn/bind', {'schema_version': 1, 'world_id': self.world, 'folder_key': folder, 'epoch': epoch,
                                               'session_id': session or self.session, 'contract': 'native-effects-v1'})

    def test_every_route_exists_and_needs_the_secret(self):
        self.assertEqual(set(WN_ROUTES), {'/v1/cplus/wn/' + n for n in (
            'bind', 'saved', 'rebind', 'reserve', 'start', 'finalize', 'cancel', 'hold', 'repair', 'reconcile', 'status', 'active')})
        for route in WN_ROUTES:
            self.assertEqual(self.post(route, {}, 'wrong')[0], 403, route)
            self.assertEqual(self.post(route, {}, None)[0], 403, route)

    def test_full_flow_over_http_with_binding_and_replay(self):
        code, bound = self.bind()
        self.assertEqual((code, bound['state'], bound['capabilities']), (200, 'BOUND', ['native-effects-v1']))
        b = reserve_body(self.world, self.session)
        code, r = self.post('/v1/cplus/wn/reserve', b)
        self.assertEqual((code, r['state'], r['native_mutation']), (200, 'RESERVED', True))
        code, r = self.post('/v1/cplus/wn/start', ident(b, 'requester_id'))
        self.assertEqual((code, r['state'], r['execution_started']), (200, 'EXECUTING', True))
        rc = receipt(b, evidence=break_evidence(b))
        code, done = self.post('/v1/cplus/wn/finalize', rc)
        self.assertEqual((code, done['state'], done['backend_commit_ack'], done['release_actor_fence']), (200, 'EFFECT_VERIFIED', True, True))
        self.assertEqual(self.post('/v1/cplus/wn/finalize', rc)[1], done)                       # lost ACK: identical replay
        code, st = self.post('/v1/cplus/wn/status', ident(b, 'requester_id'))
        self.assertEqual((code, st['state'], st['receipt_hash']), (200, 'EFFECT_VERIFIED', done['receipt_hash']))
        code, act = self.post('/v1/cplus/wn/active', {'world_id': b['world_id'], 'actor_id': b['actor_id'], 'requester_id': b['requester_id']})
        self.assertEqual((code, act['active']), (200, False))

    def test_refusals_are_409_with_their_own_error_code(self):
        b = reserve_body(self.world, self.session)
        code, body = self.post('/v1/cplus/wn/reserve', b)                                       # not bound yet
        self.assertEqual(code, 409)
        self.assertIn('BINDING_UNVERIFIED', json.dumps(body))
        self.bind()
        self.assertEqual(self.post('/v1/cplus/wn/start', ident(b, 'requester_id'))[0], 409)     # unknown task
        self.assertEqual(self.post('/v1/cplus/wn/reserve', {**b, 'origin': 'CONSOLE'})[0], 409)
        self.assertEqual(self.post('/v1/cplus/wn/reserve', {**b, 'action_kind': 'place_block'})[0], 409)
        self.assertEqual(self.post('/v1/cplus/wn/reserve', {**b, 'extra': 1})[0], 409)
        self.assertEqual(self.post('/v1/cplus/wn/bind', {'schema_version': 1})[0], 409)
        self.assertEqual(self.post('/v1/cplus/wn/reserve', b)[0], 200)
        second = reserve_body(self.world, self.session, actor=b['actor_id'])
        self.assertEqual(self.post('/v1/cplus/wn/reserve', second)[0], 409)                       # actor fence
        same_block = reserve_body(self.world, self.session)
        self.assertEqual(self.post('/v1/cplus/wn/reserve', same_block)[0], 409)                   # block claim
        self.assertEqual(self.post('/v1/cplus/wn/finalize', receipt(b))[0], 409)                  # not started

    def test_copies_and_regressions_are_refused_over_http(self):
        self.assertEqual(self.bind()[0], 200)
        self.assertEqual(self.bind(epoch=1, folder='W1 - Copy', session=uid())[0], 409)
        self.assertEqual(self.bind(epoch=5, session=uid())[0], 409)
        self.assertEqual(self.bind(epoch=1, session=uid())[0], 200)

    def test_the_no_op_and_native_routes_do_not_stand_in_for_each_other(self):
        self.bind()
        b = reserve_body(self.world, self.session)
        self.assertEqual(self.post('/v1/cplus/we/reserve', b)[0], 409)                              # a native body is not a no-op body
        self.assertEqual(self.post('/v1/cplus/wn/reserve', {**b, 'action_kind': 'noop'})[0], 409)   # and noop is not a native action


if __name__ == '__main__':
    unittest.main()
