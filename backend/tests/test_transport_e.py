"""Option E: real concurrent HTTP initialization and durable metadata recovery."""
import concurrent.futures
import copy
import http.client
import json
import threading
import time
from unittest.mock import patch
from test_cplus_e3_g2_http import G2HTTP, TOKEN
from test_cplus_e3_g2_motion import ack, body
from npcortex_backend.cplus_e3_g2_motion import GuardedMotionStore

class TransportE(G2HTTP):
    def test_concurrent_first_requests_initialize_once(self):
        calls=[]
        def create(db):
            calls.append(1);time.sleep(.05)
            return GuardedMotionStore(db)
        def post():
            c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
            c.request('POST','/v1/cplus/e3/reserve',json.dumps(self.b),
                      {'X-NPCortex-CPlus-Token':TOKEN,'Content-Type':'application/json'})
            r=c.getresponse();answer=(r.status,json.loads(r.read()));c.close();return answer
        with patch('npcortex_backend.server.read_secret',return_value=TOKEN),patch(
                'npcortex_backend.server.GuardedMotionStore',side_effect=create):
            with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
                results=list(pool.map(lambda _:post(),range(10)))
        self.assertEqual(len(calls),1)
        self.assertTrue(all(code==200 and value['state']=='RESERVED' for code,value in results))

    def proof(self):
        p={k:self.b[k] for k in ('task_id','attempt_id','world_id','actor_id','session_id','requester_id')}
        p['no_handoff']=True
        return p

    def test_lost_reserve_ack_abandon_and_new_work(self):
        self.post('/v1/cplus/e3/reserve',self.b) # discard response, simulating ACK loss
        p=self.proof()
        for _ in range(2):
            code,r=self.post('/v1/cplus/e3/abandon',p)
            self.assertEqual(code,200);self.assertEqual(r['state'],'CANCELLED')
        self.assertEqual(self.post('/v1/cplus/e3/reserve',body(self.b['snapshot']))[0],200)

    def test_abandon_rejects_accepted_goal_and_bad_identity(self):
        self.post('/v1/cplus/e3/reserve',self.b)
        p=self.proof();p['no_handoff']=False
        self.assertEqual(self.post('/v1/cplus/e3/abandon',p)[0],409)
        self.assertEqual(self.post('/v1/cplus/e3/abandon',self.proof(),'bad')[0],403)
        self.post('/v1/cplus/e3/report',ack(self.b))
        self.assertEqual(self.post('/v1/cplus/e3/abandon',self.proof())[0],409)

    def test_lost_report_and_cancel_ack_idempotency(self):
        self.post('/v1/cplus/e3/reserve',self.b); report=ack(self.b)
        self.post('/v1/cplus/e3/report',report)
        self.assertEqual(self.post('/v1/cplus/e3/report',report)[1]['goal_id'],report['goal_id'])
        identity=self.proof();identity.pop('no_handoff');identity['goal_id']=report['goal_id']
        for _ in range(2):self.assertEqual(self.post('/v1/cplus/e3/cancel-begin',identity)[1]['state'],'CANCEL_PENDING')
        proof={**identity,'action_id':None,'c2_state':'CANCELLED','c1_stopped':True}
        for _ in range(2):self.assertEqual(self.post('/v1/cplus/e3/cancel-ack',proof)[1]['state'],'CANCELLED')

    def test_completion_wins_when_cancel_arrived_after_live_completion(self):
        from test_cplus_e3_g3_http import G3LifecycleFixture
        self.post('/v1/cplus/e3/reserve',self.b);report=ack(self.b)
        self.post('/v1/cplus/e3/report',report)
        identity=self.proof();identity.pop('no_handoff');identity['goal_id']=report['goal_id']
        self.post('/v1/cplus/e3/cancel-begin',identity)
        candidate=G3LifecycleFixture(self.b,report['goal_id']).candidate()
        code,out=self.post('/v1/cplus/e3/finalize',candidate)
        self.assertEqual(code,200);self.assertEqual(out['state'],'COMPLETED')
        proof={**identity,'action_id':None,'c2_state':'CANCELLED','c1_stopped':True}
        self.assertEqual(self.post('/v1/cplus/e3/cancel-ack',proof)[0],409)
