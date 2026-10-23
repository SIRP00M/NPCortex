"""E2 loopback authenticated HTTP dispatch and verify integration."""
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
from npcortex_backend.cplus_e_coordinator import ExtendedCoordinator
from test_cplus_f2_observation import sample
import copy

def fresh(obs, delta=2):
    v=copy.deepcopy(obs);v["observation_id"]=str(uuid4());v["capture_tick"]+=delta
    v["permission"]["checked_tick"]+=delta;v["permission"]["evidence_id"]=v["observation_id"]
    return v

TOKEN = 'e2-local-loopback-long-test-secret-value'


class E2HTTP(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        db=Path(self.temp.name)/'world.db'
        service=SimpleNamespace(_require_world_store=lambda:SimpleNamespace(db_path=db),model='mock')
        self.server=make_http_server(service,port=0)
        self.worker=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.worker.start()
    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.worker.join(5)
        self.temp.cleanup()
    def post(self,path,body,token=TOKEN):
        with patch('npcortex_backend.server.read_secret',return_value=TOKEN):
            client=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
            client.request('POST',path,json.dumps(body),{'Content-Type':'application/json','X-NPCortex-CPlus-Token':token})
            response=client.getresponse();code=response.status;body=json.loads(response.read());client.close()
            return code,body
    def test_http_two_step_and_no_world_effect(self):
        snap=sample();who=str(uuid4())
        accepted=self.post('/v1/cplus/e1/submit',{'request_id':str(uuid4()),'requester_id':who,
                               'snapshot':snap,'plan':ExtendedCoordinator.PLAN})
        self.assertEqual(accepted[0],200)
        self.assertEqual(self.post('/v1/cplus/e2/dispatch',{'snapshot':fresh(snap)},token='bad')[0],403)
        code,claimed=self.post('/v1/cplus/e2/dispatch',{'snapshot':fresh(snap)})
        self.assertEqual(code,200);self.assertEqual(claimed['state'],'DISPATCHING')
        self.assertEqual(claimed['task_id'],accepted[1]['task_id'])
        code,proof=self.post('/v1/cplus/e2/verify',{
            'task_id':claimed['task_id'],'lease_id':claimed['lease_id'],
            'generation':claimed['generation'],'snapshot':fresh(snap,10)})
        self.assertEqual(code,200);self.assertEqual(proof['state'],'READ_ONLY_VERIFIED')
        self.assertEqual(proof['read_only_receipts'],3);self.assertFalse(proof['world_effects'])
        code,empty=self.post('/v1/cplus/e2/dispatch',{'snapshot':fresh(snap,12)})
        self.assertEqual(code,200);self.assertEqual(empty['state'],'EMPTY')
    def test_untrusted_obs_conflict(self):
        snap=sample();snap['permission']['decision']='ALLOW'
        code,_=self.post('/v1/cplus/e2/dispatch',{'snapshot':snap})
        self.assertEqual(code,409)

if __name__=='__main__':unittest.main()
