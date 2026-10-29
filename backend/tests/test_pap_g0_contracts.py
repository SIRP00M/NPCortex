"""PAP-G0 static pins (test-only, reads source text). They fail if a PAP invariant is changed silently."""
import os
import unittest
from pathlib import Path

# PAP_ROOT lets the mutation probe point the pins at a temp COPY of the tree; production is never mutated.
ROOT = Path(os.environ.get('PAP_ROOT') or Path(__file__).resolve().parents[2])
JAVA = ROOT / 'forge-mod' / 'src' / 'main' / 'java' / 'io' / 'github' / 'sirp00m' / 'npcortex'
SRC = ROOT / 'backend' / 'src' / 'npcortex_backend'


def text(p):
    return p.read_text(encoding='utf-8')


class PapG0Pins(unittest.TestCase):
    def test_c1_journal_cap_and_fail_closed(self):
        j = text(JAVA / 'C1InventoryJournal.java')
        self.assertIn('static final int MAX_RECEIPTS = 512;', j)
        self.assertIn('return receipts.size() == MAX_RECEIPTS ? Lookup.FULL : Lookup.NEW;', j)
        self.assertLess(j.index('old != null'), j.index('Lookup.FULL'), 'REPLAY/CONFLICT must be answered before FULL')
        self.assertNotIn('receipts.remove', j, 'no eviction / auto-prune')
        self.assertNotIn('removeEldest', j)

    def test_outbox_frame_limit_is_separate_from_c1_cap(self):
        self.assertIn('MAX_FRAME = 2048', text(JAVA / 'cplus' / 'CPlusDurableLedger.java'))

    def test_native_runtime_blocks_when_journal_full(self):
        self.assertIn('record.journal.size() >= C1InventoryJournal.MAX_RECEIPTS', text(JAVA / 'NativeEffectRuntime.java'))

    def test_wn_claims_are_atomic_unique_indexes(self):
        s = text(SRC / 'cplus_wn_store.py')
        self.assertIn("kind TEXT NOT NULL CHECK (kind IN ('block','item'))", s)
        self.assertIn('CREATE UNIQUE INDEX IF NOT EXISTS cplus_wn_claim_block', s)
        self.assertIn("WHERE kind='block' AND state IN ('ACTIVE','HELD')", s)
        self.assertIn("WHERE kind='item' AND state IN ('ACTIVE','HELD')", s)
        self.assertIn('BEGIN IMMEDIATE', s)

    def test_durability_not_relaxed(self):
        self.assertIn('PRAGMA synchronous=FULL', text(SRC / 'cplus_e_graph.py'))
        self.assertIn('PRAGMA synchronous=FULL', text(SRC / 'cplus_m04_store.py'))

    def test_unapproved_world_actions_still_have_no_executor(self):
        rt = text(JAVA / 'NativeEffectRuntime.java') + text(JAVA / 'NativeActionPolicy.java') + text(JAVA / 'PlayerActionRuntime.java')
        for kind in ('use_item', 'select_tool', 'equip_item', 'craft', 'open_container'):
            self.assertNotIn(f'"{kind}"', rt, f'{kind} stays unimplemented: it is research-gated')

    def test_place_and_use_exist_only_behind_the_built_in_ceiling(self):
        pol = text(JAVA / 'NativeActionPolicy.java')
        self.assertIn('static boolean placeCeilingDenies(', pol)
        self.assertIn('static boolean useCeilingAllows(', pol)
        # the ceiling check must come BEFORE the allow-list walk in both decisions
        place = pol[pol.index('Decision decidePlace('):pol.index('Decision decideUse(')]
        self.assertLess(place.index('placeCeilingDenies'), place.index('decideBlock('))
        use = pol[pol.index('Decision decideUse('):pol.index('private Decision decideBlock(')]
        self.assertLess(use.index('useCeilingAllows'), use.index('decideBlock('))
        for tnt in ('minecraft:tnt', 'minecraft:fire', 'minecraft:lava', 'minecraft:bedrock', 'minecraft:command_block'):
            self.assertIn(f'"{tnt}"', pol, f'{tnt} must stay on the place deny list')
        # use is limited to a lever and buttons
        self.assertIn('blockId.equals("minecraft:lever")', pol)
        self.assertNotIn('_door', pol[pol.index('static boolean useCeilingAllows('):pol.index('static boolean useCeilingAllows(') + 200])

    def test_player_actions_never_run_without_the_durable_start_marker(self):
        flow = text(JAVA / 'PlayerActionFlow.java')
        self.assertIn('static Started startAck(', flow)
        self.assertIn('private Started(UUID task)', flow)                   # the token cannot be built elsewhere
        self.assertIn('if (marker == null) throw new IllegalStateException("no durable EXECUTION_STARTED marker")', text(JAVA / 'PlayerActionRuntime.java'))
        self.assertIn('Outcome execute(Started marker', flow)

    def test_reserve_64_is_not_in_production(self):
        for p in JAVA.rglob('*.java'):
            self.assertNotIn('journal_reserve', text(p).lower(), str(p))

    def test_g0_doc_carries_the_invariants(self):
        d = text(ROOT / 'docs' / 'pap' / 'PAP_G0_CONTRACT.md')
        for token in ('I1', 'I8', 'HOLD_IN_DOUBT', 'NOT PRE-APPROVED', 'Approach before native reserve'):
            self.assertIn(token, d)


if __name__ == '__main__':
    unittest.main()
