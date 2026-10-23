"""C-F2 contract tests: synthetic fixtures only; no Forge world change."""
import copy
import unittest
from uuid import uuid4

from npcortex_backend.cplus_f2_observation import (
    validate_v2, decide, evidence_fresh, legacy_v1_view, MAX_BLOCKS)
from npcortex_backend.identity import IdentityError

W = '00000000-0000-0000-0000-000000000001'
A = '00000000-0000-0000-0000-000000000002'
S = '00000000-0000-0000-0000-000000000003'
O = '00000000-0000-0000-0000-000000000004'
CENTER = [100, 64, 200]


def sample(status='OBSERVED'):
    cells = []
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                p = [CENTER[0]+dx,CENTER[1]+dy,CENTER[2]+dz]
                b = {'position':p, 'status':status}
                if status == 'OBSERVED':
                    b.update(block_id='minecraft:air' if dx or dy or dz else 'minecraft:oak_log',
                             properties={'axis':'y'} if not (dx or dy or dz) else {})
                cells.append(b)
    decision, reason = decide(status,'break_block')
    return {'schema_version':2,'world_id':W,'dimension_id':'minecraft:overworld',
            'actor_id':A,'session_id':S,'observation_id':O,'capture_tick':1234,
            'source':'SERVER_WORLD','read_only':True,'center':CENTER,'radius':1,
            'blocks':cells,
            'coverage':{'requested':27,'recorded':27,'unknown':0 if status=='OBSERVED' else 27,
                        'complete':status=='OBSERVED','truncated':False},
            'nearby_drops':[],'drops_truncated':False,
            'nearby_entities':[],'entities_truncated':False,
            'permission':{'operation':'break_block','position':CENTER,'decision':decision,
                          'reason':reason,'checked_tick':1234,'evidence_id':O}}


class CPlusF2Tests(unittest.TestCase):
    def test_round_trip_observed(self):
        self.assertEqual(validate_v2(sample(),W,'minecraft:overworld',A)['schema_version'],2)

    def test_unknown_chunk_cannot_be_air(self):
        s=sample('UNKNOWN_CHUNK')
        self.assertEqual(validate_v2(s)['permission']['decision'],'UNKNOWN')
        s['blocks'][0]['block_id']='minecraft:air'
        with self.assertRaises(IdentityError): validate_v2(s)

    def test_world_bounds_deny(self):
        for status in ('OUT_OF_BORDER','OUT_OF_HEIGHT'):
            s=sample(status)
            self.assertEqual(validate_v2(s)['permission']['decision'],'DENY')

    def test_read_allowed_but_never_world_change(self):
        self.assertEqual(decide('OBSERVED','observe'),('ALLOW','server_read_only_observation'))
        for op in ('break_block','place_block','use_block'):
            self.assertEqual(decide('OBSERVED',op)[0],'UNKNOWN')

    def test_identity_session_epoch_freshness(self):
        s=sample()
        self.assertTrue(evidence_fresh(s,world_id=W,dimension='minecraft:overworld',
                                       actor_id=A,session_id=S,current_tick=1254))
        self.assertFalse(evidence_fresh(s,world_id=W,dimension='minecraft:overworld',
                                        actor_id=A,session_id=str(uuid4()),current_tick=1254))
        self.assertFalse(evidence_fresh(s,world_id=W,dimension='minecraft:overworld',
                                        actor_id=A,session_id=S,current_tick=1255))
        self.assertFalse(evidence_fresh(s,world_id=W,dimension='minecraft:overworld',
                                        actor_id=A,session_id=S,current_tick=1233))
        self.assertFalse(evidence_fresh(s,world_id=str(uuid4()),dimension='minecraft:overworld',
                                        actor_id=A,session_id=S,current_tick=1235))
        self.assertFalse(evidence_fresh(s,world_id=W,dimension='minecraft:the_nether',
                                        actor_id=A,session_id=S,current_tick=1235))

    def test_bad_coverage_fail_closed(self):
        s=sample();s['coverage']['unknown']=1
        with self.assertRaises(IdentityError):validate_v2(s)

    def test_missing_cell_fail_closed(self):
        s=sample();s['blocks'].pop()
        with self.assertRaises(IdentityError):validate_v2(s)

    def test_duplicate_cell_fail_closed(self):
        s=sample();s['blocks'][0]['position']=s['blocks'][1]['position']
        with self.assertRaises(IdentityError):validate_v2(s)

    def test_forged_allow_fail_closed(self):
        s=sample();s['permission']['decision']='ALLOW'
        with self.assertRaises(IdentityError):validate_v2(s)

    def test_cross_actor_fail_closed(self):
        with self.assertRaises(IdentityError):validate_v2(sample(),actor_id=str(uuid4()))

    def test_conflicting_evidence_id(self):
        s=sample();s['permission']['evidence_id']=str(uuid4())
        with self.assertRaises(IdentityError):validate_v2(s)

    def test_large_entity_lists_fail(self):
        s=sample()
        for i in range(9):
            s['nearby_entities'].append({'entity_id':str(uuid4()),'type_id':'minecraft:pig',
                                         'position':[100,64,200]})
        with self.assertRaises(IdentityError):validate_v2(s)

    def test_legacy_view_does_not_advertise_skills(self):
        v1={'world_id':W,'dimension_id':'minecraft:overworld','actor_id':A,
            'position':[100,64,200],'inventory':{},'blocks':{},'available_skills':['move_to']}
        normalized=legacy_v1_view(sample(),v1)
        self.assertEqual(normalized['available_skills'],['move_to'])
        self.assertEqual(normalized['blocks'],{})

    def test_multi_actor_1_3_5_10_synthetic_isolation(self):
        # Pure contract validation is not real game MSPT.
        for count in (1, 3, 5, 10):
            ids = [str(uuid4()) for _ in range(count)]
            for actor in ids:
                s = sample()
                s["actor_id"] = actor
                self.assertIs(validate_v2(s, actor_id=actor), s)
                wrong = next((x for x in ids if x != actor), None)
                if wrong:
                    with self.assertRaises(IdentityError):
                        validate_v2(s, actor_id=wrong)

    def test_malformed_values_fail_closed(self):
        for key, value in [('read_only',False),('source','CLIENT'),('schema_version',1),
                           ('capture_tick',-1),('radius',2)]:
            s=sample();s[key]=value
            with self.subTest(key=key),self.assertRaises(IdentityError):validate_v2(s)

    def test_explicit_oversized_properties_rejected(self):
        s=sample();s['blocks'][0]['properties']={str(i):'x' for i in range(17)}
        with self.assertRaises(IdentityError):validate_v2(s)


if __name__=='__main__': unittest.main()
