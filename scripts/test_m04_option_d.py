"""Option D static harness regression, not a claim of Forge runtime PASS."""
from pathlib import Path
import json

repo = Path(__file__).resolve().parents[1]
root = repo / 'integrations/datapacks/NPCortex_M04_Acceptance'
java = (repo / 'forge-mod/src/main/java/io/github/sirp00m/npcortex/CPlusM04Runtime.java').read_text('utf-8')
store = (repo / 'backend/src/npcortex_backend/cplus_m04_store.py').read_text('utf-8')
assert json.loads((root / 'pack.mcmeta').read_text('utf-8'))['pack']['pack_format'] == 15
functions = sorted((root / 'data/npcortex_m04/functions').glob('*.mcfunction'))
assert len(functions) == 4
allowed = {'menu', 'preflight', 'inventory', 'after_rejoin'}
assert {p.stem for p in functions} == allowed
for f in functions:
    for line in f.read_text('utf-8').splitlines():
        low = line.lower()
        assert not any(x in low for x in ('setblock ', 'fill ', 'loot ', 'give ', 'clear ', 'kill ', 'summon ', 'tp ')), (f,line)
        if line.startswith('npcortex m04'):
            cmd = line.split()[2]
            assert f'Commands.literal("{cmd}")' in java, (f, cmd)
assert 'getWorldPath(LevelResource.ROOT)' in java
assert 'EVIDENCE_IO.execute' in java
assert 'mutation_permitted", false' in java
assert 'literal("break")' not in java and 'destroyBlock(' not in java
assert 'BEGIN IMMEDIATE' in store and 'cplus_m04_actor_fence' in store and 'cplus_m04_target_fence' in store
assert 'cplus_e3_g2_moves' in store and 'C2_ACCEPTED' in store and 'HOLD' in store
assert 'prepare_effect' in store and 'metadata_retry' in store
assert not any(s in store for s in ('world.setBlock', 'destroyBlock(', 'playerTouch(', '/v1/cplus/m04'))
print('Option D static test: 13 checks PASS (gameplay/native mining remain unverified)')
