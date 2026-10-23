"""C-F1 D1/D2/D4: real HTTP / SQLite durability boundaries, no world mutation."""
import hashlib
import http.client
import json
import os
from pathlib import Path
import sqlite3
import struct
import tempfile
import threading
import unittest
from unittest.mock import patch
from uuid import uuid4
from types import SimpleNamespace

from npcortex_backend.cplus_f1_bridge import DurableBridge, BridgeConflict, _mutf
from npcortex_backend.server import make_http_server
from sqlite_test_utils import sqlite_test_connection

SECRET = 'npcortex-cf1-test-token-1234567890'

def fixture(stage='FENCE', sequence=1, previous='0'*64, **changes):
    event = {key:str(uuid4()) for key in ('event_id','world_id','actor_id','task_id','action_id','attempt_id')}
    event.update({'parent_event_id':None, 'epoch':1, 'sequence':sequence,
                  'stage':stage, 'fingerprint':'a'*64, 'evidence_id':'synthetic-no-world-effect' if stage=='CANCELLED' else ''})
    event.update(changes)
    binary=struct.pack('>iq',1,event['sequence'])
    for key in ('event_id','world_id','actor_id','task_id','action_id','attempt_id'):
        binary += _mutf(event[key])
    binary += _mutf(event['parent_event_id'] or '') + struct.pack('>q',event['epoch'])
    binary += _mutf(event['stage']) + _mutf(event['fingerprint']) + _mutf(event['evidence_id'])
    return {'version':1,'event':event,'previous_hash':previous,
            'record_hash':hashlib.sha256(bytes.fromhex(previous)+binary).hexdigest()}

class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db=Path(self.temp.name)/'npcortex.sqlite3'
        with sqlite_test_connection(self.db) as con:
            con.execute('PRAGMA user_version=4')
            con.execute('CREATE TABLE preexisting (word TEXT)')
            con.execute('INSERT INTO preexisting VALUES (?)',('retained',))
        self.bridge=DurableBridge(self.db)
        self.first=fixture()

    def tearDown(self):
        self.temp.cleanup()

    def test_commit_duplicate_and_restart_receipts(self):
        ack=self.bridge.receive(self.first)
        self.assertEqual(ack['status'],'ACKED')
        self.assertFalse(ack['duplicate'])
        self.assertEqual(len(ack['receipt_id']),64)
        self.assertTrue(self.bridge.receive(self.first)['duplicate'])
        self.bridge=DurableBridge(self.db)
        self.assertTrue(self.bridge.receive(self.first)['duplicate'])
        status=self.bridge.status(self.first['event']['world_id'])
        self.assertEqual((status['sequence'],status['events']), (1,1))
        with sqlite_test_connection(self.db) as con:
            self.assertEqual(con.execute('PRAGMA user_version').fetchone()[0],4)
            self.assertEqual(con.execute('SELECT word FROM preexisting').fetchone()[0], 'retained')
            self.assertEqual(con.execute('SELECT COUNT(*) FROM cplus_f1_bridge_receipts').fetchone()[0],1)

    def test_gap_epoch_world_isolation_and_hash_chain(self):
        one=self.first
        self.bridge.receive(one)
        e=one['event']
        second=fixture('INTENT',sequence=2,previous=one['record_hash'],
                       world_id=e['world_id'],actor_id=e['actor_id'],task_id=e['task_id'],epoch=1)
        wrong=fixture('INTENT',sequence=3,previous=one['record_hash'],
                      world_id=e['world_id'],actor_id=e['actor_id'])
        with self.assertRaises(BridgeConflict) as err: self.bridge.receive(wrong)
        self.assertEqual(err.exception.expected,2)
        malformed={**second,'previous_hash':'0'*64}
        with self.assertRaisesRegex(BridgeConflict,'hash mismatch'): self.bridge.receive(malformed)
        self.bridge.receive(second)
        final=fixture('CANCELLED',sequence=3,previous=second['record_hash'],
                      world_id=e['world_id'],actor_id=e['actor_id'],task_id=second['event']['task_id'],
                      action_id=second['event']['action_id'], attempt_id=second['event']['attempt_id'])
        self.bridge.receive(final)
        self.assertEqual(self.bridge.status(e['world_id'])['sequence'],3)
        newworld=fixture(world_id=str(uuid4()),actor_id=e['actor_id'])
        self.assertEqual(self.bridge.receive(newworld)['sequence'],1)
        stale=fixture('INTENT',sequence=4,previous=final['record_hash'],
                      world_id=e['world_id'],actor_id=str(uuid4()))
        with self.assertRaisesRegex(BridgeConflict,'fence'): self.bridge.receive(stale)

    def test_duplicate_event_id_conflict_and_immutability(self):
        self.bridge.receive(self.first)
        other=fixture(event_id=self.first['event']['event_id'], world_id=str(uuid4()))
        with self.assertRaisesRegex(BridgeConflict,'event id reuse'): self.bridge.receive(other)
        corrupt={**self.first,'record_hash':'f'*64}
        with self.assertRaises(BridgeConflict): self.bridge.receive(corrupt)
        self.assertEqual(self.bridge.status(self.first['event']['world_id'])['sequence'],1)

    def test_confirm_duplicate_and_recovery_are_read_only(self):
        first=self.first
        ack=self.bridge.receive(first)
        c={'world_id':first['event']['world_id'], 'sequence':1,
           'event_id':first['event']['event_id'], 'receipt_id':ack['receipt_id']}
        with self.assertRaisesRegex(BridgeConflict,'mismatch'):
            self.bridge.confirm({**c,'receipt_id':'f'*64})
        self.assertFalse(self.bridge.confirm(c)['duplicate'])
        self.assertTrue(self.bridge.confirm(c)['duplicate'])
        e=first['event']
        intent=fixture('INTENT',2,first['record_hash'],world_id=e['world_id'],actor_id=e['actor_id'])
        self.bridge.receive(intent)
        recovery=self.bridge.recovery(e['world_id'])
        self.assertEqual(recovery['classification']['PREPARED'],1)
        self.assertFalse(recovery['can_replay_world_actions'])
        self.assertFalse(recovery['has_authoritative_world_observation'])
        self.assertEqual(recovery['receipt_confirmations_pending'],1)

    def test_prevent_fake_success_and_wrong_actor(self):
        first=self.first
        self.bridge.receive(first)
        e=first['event']
        fake=fixture('VERIFIED',2,first['record_hash'],world_id=e['world_id'],actor_id=e['actor_id'],evidence_id='fake')
        with self.assertRaisesRegex(BridgeConflict,'intent'):
            self.bridge.receive(fake)
        intent=fixture('INTENT',2,first['record_hash'],world_id=e['world_id'],actor_id=e['actor_id'])
        self.bridge.receive(intent)
        fake=fixture('VERIFIED',3,intent['record_hash'],world_id=e['world_id'],actor_id=e['actor_id'],
                     action_id=intent['event']['action_id'],attempt_id=intent['event']['attempt_id'],
                     task_id=intent['event']['task_id'],evidence_id='fake')
        with self.assertRaisesRegex(BridgeConflict,'verification'):
            self.bridge.receive(fake)
        # A denied event never changes a durable checkpoint.
        self.assertEqual(self.bridge.status(e['world_id'])['sequence'],2)

    def test_one_three_five_ten_actor_bridge_isolation(self):
        # Synthetic transport pressure only. NOT a Forge MSPT benchmark.
        for count in (1,3,5,10):
            world=str(uuid4())
            previous='0'*64
            actors=[str(uuid4()) for _ in range(count)]
            for sequence,actor in enumerate(actors,1):
                wire=fixture('FENCE',sequence,previous,world_id=world,actor_id=actor)
                self.bridge.receive(wire)
                previous=wire['record_hash']
            state=self.bridge.status(world)
            self.assertEqual((state['sequence'],state['events']),(count,count))
            self.assertEqual(self.bridge.recovery(world)['classification']['IN_DOUBT'],0)

    def test_sqlite_locked_write_has_no_false_ack(self):
        import threading, time
        started=threading.Event()
        def lock_and_wait():
            with sqlite_test_connection(self.db) as con:
                con.execute('BEGIN IMMEDIATE')
                started.set()
                time.sleep(0.15)
        # Use a short lock to prove no false success even under contention.
        self.bridge.receive(self.first)
        thread=threading.Thread(target=lock_and_wait)
        thread.start();self.assertTrue(started.wait(1))
        e=self.first['event']
        next_event=fixture('INTENT',2,self.first['record_hash'],world_id=e['world_id'],actor_id=e['actor_id'])
        self.assertEqual(self.bridge.receive(next_event)['status'],'ACKED')
        thread.join()
        self.assertEqual(self.bridge.status(e['world_id'])['sequence'],2)

    def test_reject_unsafe_payloads(self):
        cases=[{'version':2}, {'event':{**self.first['event'],'epoch':True}},
               {'event':{**self.first['event'],'world_id':'not-uuid'}},
               {'event':{**self.first['event'],'evidence_id':'bad','stage':'FENCE'}},
               {'event':{**self.first['event'],'stage':'VERIFIED'}},
               {'event':{**self.first['event'],'stage':'REPLAY_EXECUTE'}}]
        for changes in cases:
            with self.subTest(changes=changes),self.assertRaises(BridgeConflict):
                self.bridge.receive({**self.first,**changes})

    def test_http_auth_ack_and_offline_boundary(self):
        with patch.dict(os.environ, {'NPCORTEX_CPLUS_F1_TOKEN':SECRET}):
            service=SimpleNamespace(model='test',_require_world_store=lambda:SimpleNamespace(db_path=self.db))
            httpd=make_http_server(service,'127.0.0.1',0)
            thread=threading.Thread(target=httpd.serve_forever,daemon=True)
            thread.start()
            def request(path, data, token=None):
                con=http.client.HTTPConnection('127.0.0.1',httpd.server_port,timeout=3)
                try:
                    headers={'Content-Type':'application/json'}
                    if token is not None: headers['X-NPCortex-CPlus-Token']=token
                    con.request('POST',path,json.dumps(data).encode(),headers)
                    result=con.getresponse()
                    return result.status,json.loads(result.read())
                finally:con.close()
            try:
                self.assertEqual(request('/v1/cplus/f1/events',self.first)[0],403)
                self.assertEqual(request('/v1/cplus/f1/events',self.first,'bad')[0],403)
                status,ack=request('/v1/cplus/f1/events',self.first,SECRET)
                self.assertEqual((status,ack['status']), (200,'ACKED'))
                self.assertEqual(request('/v1/cplus/f1/events',self.first,SECRET)[1]['duplicate'],True)
                status,body=request('/v1/cplus/f1/status',{'world_id':self.first['event']['world_id']},SECRET)
                self.assertEqual((status,body['sequence']), (200,1))
                self.assertFalse(body['world_replay_enabled'])
                self.assertEqual(request('/v1/cplus/f1/status',{'world_id':'oops'},SECRET)[0],400)
                world_id=self.first['event']['world_id']
                ack=request('/v1/cplus/f1/events',self.first,SECRET)[1]
                confirm={'world_id':world_id,'sequence':1,'event_id':self.first['event']['event_id'],'receipt_id':ack['receipt_id']}
                self.assertEqual(request('/v1/cplus/f1/confirm',confirm,SECRET)[0],200)
                code,recovery=request('/v1/cplus/f1/recovery',{'world_id':world_id},SECRET)
                self.assertEqual(code,200)
                self.assertEqual(recovery['classification']['CANCELLED'],0)
                self.assertEqual(recovery['receipt_confirmations_pending'],0)
                self.assertFalse(recovery['can_replay_world_actions'])
            finally:
                httpd.shutdown();httpd.server_close();thread.join(timeout=3)

if __name__=='__main__': unittest.main()
