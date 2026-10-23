"""Loopback authenticated E3-D G1 preview endpoint; never live movement."""
import copy
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
from test_cplus_f2_observation import sample

TOKEN='e3-g1-long-local-auth-secret-test-token'

class E3HTTP(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        db=Path(self.temp.name)/'world.db'
        service=SimpleNamespace(_require_world_store=lambda:SimpleNamespace(db_path=db),model='mock')
        self.server=make_http_server(service,port=0)
        self.worker=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.worker.start()
        self.snap=sample()
        self.req={'schema_version':1,'request_id':str(uuid4()),'task_id':str(uuid4()),
            'requester_id':str(uuid4()),'actor_id':self.snap['actor_id'],
            'world_id':self.snap['world_id'],'dimension_id':self.snap['dimension_id'],
            'session_id':self.snap['session_id'],'node_id':'move','attempt_id':str(uuid4()),
            'action_kind':'move_to','target':dict(zip(('x','y','z'),self.snap['center'])),
            'tolerance':0.75,'snapshot':self.snap}
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.worker.join(5);self.temp.cleanup()
    def post(self,path,body,token=TOKEN):
        with patch('npcortex_backend.server.read_secret',return_value=TOKEN):
            client=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
            client.request('POST',path,json.dumps(body),{'Content-Type':'application/json',
                            'X-NPCortex-CPlus-Token':token})
            res=client.getresponse();state=res.status;data=json.loads(res.read());client.close()
            return state,data
    def test_authenticated_and_replay_safe(self):
        self.assertEqual(self.post('/v1/cplus/e3/preview',self.req,token='bad')[0],403)
        code,p=self.post('/v1/cplus/e3/preview',self.req)
        self.assertEqual(code,200)
        self.assertEqual(p['state'],'PREVIEW_ONLY')
        self.assertFalse(p['can_execute'])
        self.assertFalse(p['motion_started'])
        self.assertEqual(self.post('/v1/cplus/e3/preview',self.req),(code,p))
        wrong=copy.deepcopy(self.req);wrong['target']['x']+=1
        self.assertEqual(self.post('/v1/cplus/e3/preview',wrong)[0],409)
    def test_reject_world_mutation_and_not_in_e1_queue(self):
        modified=copy.deepcopy(self.req);modified['action_kind']='break_block'
        self.assertEqual(self.post('/v1/cplus/e3/preview',modified)[0],409)
        status,queue=self.post('/v1/cplus/e1/queue',{'world_id':self.snap['world_id'],
                                                    'actor_id':self.snap['actor_id']})
        self.assertEqual(status,200)
        self.assertEqual(queue['tasks'],[])
