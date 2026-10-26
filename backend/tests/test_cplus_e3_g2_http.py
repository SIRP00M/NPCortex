"""G2 operator-only loopback transport/auth endpoint acceptance."""
import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from uuid import uuid4
from npcortex_backend.server import make_http_server
from test_cplus_e3_g2_motion import body,ack

TOKEN='a-long-local-e3g2-security-test-secret-token'

class G2HTTP(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        db=Path(self.temp.name)/'world.db'
        service=SimpleNamespace(_require_world_store=lambda:SimpleNamespace(db_path=db),model='mock')
        self.server=make_http_server(service,port=0)
        self.worker=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.worker.start()
        self.b=body()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.worker.join(5);self.temp.cleanup()
    def post(self,path,payload,token=TOKEN):
        with patch('npcortex_backend.server.read_secret',return_value=TOKEN):
            c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
            c.request('POST',path,json.dumps(payload),{'Content-Type':'application/json',
                      'X-NPCortex-CPlus-Token':token})
            r=c.getresponse(); result=r.status,json.loads(r.read());c.close();return result
    def test_reserve_report_and_status(self):
        self.assertEqual(self.post('/v1/cplus/e3/reserve',self.b,'unauthorized')[0],403)
        code,r=self.post('/v1/cplus/e3/reserve',self.b)
        self.assertEqual(code,200);self.assertEqual(r['state'],'RESERVED')
        result=self.post('/v1/cplus/e3/report',ack(self.b))
        self.assertEqual(result[0],200);self.assertEqual(result[1]['state'],'C2_ACCEPTED')
        self.assertFalse(result[1]['completion_verified'])
        state=self.post('/v1/cplus/e3/status',{'world_id':self.b['world_id'],
                                               'actor_id':self.b['actor_id']})
        self.assertEqual(state[1]['state'],'C2_ACCEPTED')
    def test_conflicts_and_auth(self):
        self.assertEqual(self.post('/v1/cplus/e3/report',ack(self.b))[0],409)
        self.post('/v1/cplus/e3/reserve',self.b)
        conflict=self.post('/v1/cplus/e3/reserve',body(self.b['snapshot']))
        self.assertEqual(conflict[0],409)
