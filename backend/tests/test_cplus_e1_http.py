"""Gate1 HTTP authentication, exact payload, forged permission and player FIFO."""
import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from uuid import uuid4
from npcortex_backend.server import make_http_server
from test_cplus_f2_observation import sample
from npcortex_backend.cplus_e_coordinator import ExtendedCoordinator

SECRET='gate1-authenticated-secret-123456789012345'
class E1HTTP(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        path=Path(self.tmp.name)/'db.sqlite3'
        service=SimpleNamespace(_require_world_store=lambda:SimpleNamespace(db_path=path),model='mock')
        self.http=make_http_server(service,port=0)
        self.worker=threading.Thread(target=self.http.serve_forever,daemon=True);self.worker.start()
    def tearDown(self):
        self.http.shutdown();self.http.server_close();self.worker.join(5);self.tmp.cleanup()
    def post(self,route,body,secret=SECRET):
        with patch('npcortex_backend.server.read_secret',return_value=SECRET):
            con=http.client.HTTPConnection('127.0.0.1',self.http.server_port,timeout=4)
            con.request('POST',route,json.dumps(body),{'Content-Type':'application/json','X-NPCortex-CPlus-Token':secret})
            rsp=con.getresponse();status=rsp.status;payload=json.loads(rsp.read());con.close()
            return status,payload
    def test_player_fifo_and_admin_bridge(self):
        obs=sample();players=[str(uuid4()),str(uuid4())]
        plan=ExtendedCoordinator.PLAN
        body=lambda who:dict(request_id=str(uuid4()),requester_id=who,snapshot=obs,plan=plan)
        a,b=body(players[0]),body(players[1])
        self.assertEqual(self.post('/v1/cplus/e1/submit',a,secret='wrong')[0],403)
        status,x=self.post('/v1/cplus/e1/submit',a);self.assertEqual(status,200)
        self.assertEqual(x['sequence'],1);self.assertEqual(x['state'],'QUEUED')
        status,y=self.post('/v1/cplus/e1/submit',b);self.assertEqual(status,200)
        self.assertEqual(y['sequence'],2)
        self.assertEqual(self.post('/v1/cplus/e1/submit',a)[1]['task_id'],x['task_id'])
        status,q=self.post('/v1/cplus/e1/queue',{'world_id':obs['world_id'],'actor_id':obs['actor_id']})
        self.assertEqual([t['requester_id'] for t in q['tasks']],players)
        self.assertEqual(self.post('/v1/cplus/e1/cancel',{'task_id':x['task_id'],'requester_id':players[1]})[0],409)
        self.assertEqual(self.post('/v1/cplus/e1/cancel',{'task_id':x['task_id'],'requester_id':players[0]})[1]['state'],'CANCELLED')
    def test_bad_observation_rejected(self):
        obs=sample();obs['permission']['decision']='ALLOW'
        status,_=self.post('/v1/cplus/e1/submit',{'request_id':str(uuid4()),'requester_id':str(uuid4()),'snapshot':obs,'plan':ExtendedCoordinator.PLAN})
        self.assertEqual(status,409)
if __name__=='__main__':unittest.main()
