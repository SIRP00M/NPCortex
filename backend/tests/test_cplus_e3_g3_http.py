"""G3 localhost token + HTTP lifecycle, with synthetic server-origin evidence."""
import unittest
from uuid import uuid4
from test_cplus_e3_g2_http import G2HTTP
from test_cplus_e3_g2_motion import ack
from test_cplus_e3_g3_lifecycle import G3Lifecycle

class G3HTTP(G2HTTP):
    def test_authenticated_terminal_contract_and_completion(self):
        self.assertEqual(self.post('/v1/cplus/e3/reserve',self.b)[0],200)
        goal=str(uuid4())
        self.assertEqual(self.post('/v1/cplus/e3/report',ack(self.b,goal_id=goal))[0],200)
        with G3LifecycleFixture(self.b,goal) as fixture:
            candidate=fixture.candidate()
            self.assertEqual(self.post('/v1/cplus/e3/finalize',candidate,'bad')[0],403)
            self.assertEqual(self.post('/v1/cplus/e3/finalize',{**candidate,'world_effects':True})[0],409)
            code,r=self.post('/v1/cplus/e3/finalize',candidate)
            self.assertEqual(code,200);self.assertEqual(r['state'],'COMPLETED')
            self.assertTrue(r['durable_ack']);self.assertTrue(r['release_actor_fence'])
            self.assertEqual(self.post('/v1/cplus/e3/finalize',candidate)[0],200)
            self.assertEqual(self.post('/v1/cplus/e3/reserve',G3LifecycleFixture.new_body(self.b))[0],200)
    def test_authenticated_cancel_requires_terminal_proof(self):
        self.assertEqual(self.post('/v1/cplus/e3/reserve',self.b)[0],200)
        goal=str(uuid4())
        self.assertEqual(self.post('/v1/cplus/e3/report',ack(self.b,goal_id=goal))[0],200)
        ident={k:self.b[k] for k in ('task_id','attempt_id','requester_id','world_id','actor_id','session_id')}
        ident['goal_id']=goal
        self.assertEqual(self.post('/v1/cplus/e3/cancel-begin',ident)[0],200)
        self.assertEqual(self.post('/v1/cplus/e3/cancel-ack',
            {**ident,'action_id':None,'c2_state':'CANCEL_PENDING','c1_stopped':True})[0],409)
        self.assertEqual(self.post('/v1/cplus/e3/cancel-ack',
            {**ident,'action_id':None,'c2_state':'CANCELLED','c1_stopped':False})[0],409)
        code,r=self.post('/v1/cplus/e3/cancel-ack',
            {**ident,'action_id':None,'c2_state':'CANCELLED','c1_stopped':True})
        self.assertEqual(code,200);self.assertEqual(r['state'],'CANCELLED')
        self.assertEqual(self.post('/v1/cplus/e3/reserve',G3LifecycleFixture.new_body(self.b))[0],200)

class G3LifecycleFixture:
    def __init__(self,body,goal):self.body=body;self.goal=goal
    def __enter__(self):return self
    def __exit__(self,*_):pass
    def candidate(self):
        from test_cplus_e3_g3_terminal import fixture
        c,_=fixture()
        for key in ('task_id','request_id','attempt_id','requester_id','world_id',
                    'dimension_id','session_id','actor_id'):
            c[key]=self.body[key]
        c['observation_id']=self.body['snapshot']['observation_id']
        c['goal_id']=self.goal;c['action_id']=str(uuid4())
        c['target']=self.body['target'];c['tolerance']=self.body['tolerance']
        c['c1']['position']={'x':c['target']['x']+0.5,'y':float(c['target']['y']),'z':c['target']['z']+0.5}  # block centre, where C2 stops
        c['c2']['goal_id']=c['c0']['goal_id']=self.goal
        c['c2']['action_id']=c['c0']['action_id']=c['action_id']
        return c
    @staticmethod
    def new_body(b):
        from test_cplus_e3_g2_motion import body
        return body(b['snapshot'])

if __name__=='__main__':unittest.main()
