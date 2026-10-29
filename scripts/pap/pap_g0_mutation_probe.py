"""PAP-G0 TEST-ONLY mutation probe. Copies the files the pins read into a temp tree, applies ONE deliberate safety violation (every occurrence of the guarded text) at a
time to the COPY, runs the pins against it (PAP_ROOT) and requires a failure. Production files are never written.
Usage: python scripts/pap/pap_g0_mutation_probe.py"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
J = 'forge-mod/src/main/java/io/github/sirp00m/npcortex/'
B = 'backend/src/npcortex_backend/'
# (name, file, old, new)  new==None means append the text in `old`
MUTATIONS = [
    ('journal cap raised silently', J + 'C1InventoryJournal.java', 'MAX_RECEIPTS = 512;', 'MAX_RECEIPTS = 1024;'),
    ('FULL answered before REPLAY/CONFLICT', J + 'C1InventoryJournal.java', 'Receipt old = receipts.get(id);',
     'if (receipts.size() == MAX_RECEIPTS) return Lookup.FULL;\n        Receipt old = receipts.get(id);'),
    ('auto-prune on commit', J + 'C1InventoryJournal.java', 'receipts.put(id, receipt);',
     'if (receipts.size() >= MAX_RECEIPTS) receipts.remove(receipts.keySet().iterator().next());\n        receipts.put(id, receipt);'),
    ('FULL check lost in lookup', J + 'C1InventoryJournal.java', 'receipts.size() == MAX_RECEIPTS ? Lookup.FULL : Lookup.NEW', 'Lookup.NEW'),
    ('native precheck ignores the cap', J + 'NativeEffectRuntime.java', 'C1InventoryJournal.MAX_RECEIPTS', '999999'),
    ('craft executor appears', J + 'NativeEffectRuntime.java', 'class NativeEffectRuntime', 'class NativeEffectRuntime /* "craft" */'),
    ('place ceiling check removed', J + 'NativeActionPolicy.java', 'if (blockId != null && placeCeilingDenies(blockId))', 'if (false)'),
    ('use opened to doors', J + 'NativeActionPolicy.java', 'blockId.equals("minecraft:lever")', 'blockId.endsWith("_door")'),
    ('TNT no longer denied', J + 'NativeActionPolicy.java', '"minecraft:tnt", ', ''),
    ('start marker no longer required', J + 'PlayerActionRuntime.java', 'if (marker == null) throw new IllegalStateException("no durable EXECUTION_STARTED marker");', ''),
    ('use_item executor appears', J + 'NativeActionPolicy.java', 'class NativeActionPolicy', 'class NativeActionPolicy /* "use_item" */'),
    ('outbox frame limit changed', J + 'cplus/CPlusDurableLedger.java', 'MAX_FRAME = 2048', 'MAX_FRAME = 4096'),
    ('claim kind check dropped', B + 'cplus_wn_store.py', "CHECK (kind IN ('block','item'))", ''),
    ('block claim unique index dropped', B + 'cplus_wn_store.py', 'cplus_wn_claim_block', 'cplus_wn_claim_blk'),
    ('HELD claims no longer exclusive', B + 'cplus_wn_store.py', "state IN ('ACTIVE','HELD')", "state IN ('ACTIVE')"),
    ('write transaction no longer BEGIN IMMEDIATE', B + 'cplus_wn_store.py', 'BEGIN IMMEDIATE', 'BEGIN'),
    ('durability relaxed (E3 store)', B + 'cplus_e_graph.py', 'PRAGMA synchronous=FULL', 'PRAGMA synchronous=NORMAL'),
    ('durability relaxed (M04 store)', B + 'cplus_m04_store.py', 'PRAGMA synchronous=FULL', 'PRAGMA synchronous=OFF'),
    ('G0 doc loses HOLD_IN_DOUBT', 'docs/pap/PAP_G0_CONTRACT.md', 'HOLD_IN_DOUBT', 'HOLD'),
    ('G0 doc loses allow-list rule', 'docs/pap/PAP_G0_CONTRACT.md', 'NOT PRE-APPROVED', 'approved'),
]


def main():
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        tree = Path(tmp) / 'tree'
        for rel in ('forge-mod/src/main/java', 'backend/src', 'docs/pap'):
            shutil.copytree(ROOT / rel, tree / rel)

        def run():
            env = dict(os.environ, PAP_ROOT=str(tree), PYTHONPATH=str(ROOT / 'backend' / 'tests'))
            r = subprocess.run([sys.executable, '-m', 'unittest', 'test_pap_g0_contracts'], cwd=ROOT / 'backend' / 'tests', env=env,
                               capture_output=True, text=True)
            return r.returncode, r.stderr

        code, _ = run()
        print(f'control (unmutated copy): {"PASS" if code == 0 else "FAIL"}')
        ok &= code == 0
        for name, rel, old, new in MUTATIONS:
            path = tree / rel
            original = path.read_text(encoding='utf-8')
            if old not in original:
                print(f'PROBE-ERROR  {name}: target text not found'); ok = False; continue
            path.write_text(original.replace(old, new), encoding='utf-8')
            code, err = run()
            path.write_text(original, encoding='utf-8')
            caught = code != 0
            ok &= caught
            first = next((l for l in err.splitlines() if l.startswith('FAIL:') or l.startswith('ERROR:')), '')
            print(f'{"DETECTED " if caught else "MISSED   "} {name:46} {first[:70]}')
    print('MUTATION PROBE:', 'PASS (every violation detected)' if ok else 'FAIL')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
