"""M03 all-tests datapack static contract check; no Minecraft or network claim."""
import json
import re
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
PACK=ROOT/'integrations/datapacks/NPCortex_M03_AllTests'
FUNC=PACK/'data/npcortex_m03_suite/functions'
JAVA=ROOT/'forge-mod/src/main/java/io/github/sirp00m/npcortex/CPlusM03Runtime.java'

class AllTestsDatapackStatic(unittest.TestCase):
    def test_pack_metadata(self):
        meta=json.loads((PACK/'pack.mcmeta').read_text(encoding='utf-8'))
        self.assertEqual(meta['pack']['pack_format'],15)

    def test_expected_entrypoints(self):
        for path in ['enable','menu','test6a/start','test6a/check','test6b/online',
                     'test6b/offline','test6b/restart','test7/reserve_start','test7/reserve_recover',
                     'test7/finalize_loss','test7/cancel_loss','test7/terminal_recover',
                     'test8/before','test8/after','test8/recover','test9/follow_start',
                     'test9/follow_conflict','test9/inventory','test10/start1','test10/start3',
                     'test10/start5','test10/start10','test10/workload10','disable']:
            with self.subTest(path=path):
                self.assertTrue((FUNC/(path+'.mcfunction')).is_file())

    def test_function_syntax_no_unsupported_macro(self):
        for f in FUNC.rglob('*.mcfunction'):
            text=f.read_text(encoding='utf-8')
            self.assertTrue(text.endswith('\n'))
            for line in text.splitlines():
                if not line or line.startswith('#'):continue
                self.assertFalse(line.startswith('/'),f'{f}: leading /')
                self.assertFalse(line.startswith('$'),f'{f}: 1.20.2 function macro')
                self.assertFalse('TASK_UUID' in line,f'{f}: task placeholder')
                self.assertTrue(line.startswith(('tellraw @s ','function npcortex_m03_suite:',
                    'npcortex debug cplus ','npcortex c1 ','npcortex c2 ')),f'{f}: unknown syntax {line}')
                if line.startswith('tellraw @s '):json.loads(line[len('tellraw @s '):])
                if line.startswith('function '):
                    name=line.split(' ',1)[1].split(':',1)[1]
                    self.assertTrue((FUNC/(name+'.mcfunction')).exists(),f'unknown link {name}')

    def test_no_destructive_commands_or_auto_ticks(self):
        self.assertFalse((PACK/'data/minecraft').exists())
        for f in FUNC.rglob('*.mcfunction'):
            for line in f.read_text(encoding='utf-8').splitlines():
                if line.startswith('#') or line.startswith('tellraw '):continue
                self.assertNotRegex(line,r'^(kill|setblock|fill|clone|teleport|tp|give|clear|summon|schedule|data modify|execute store)\b')

    def test_function_parser_can_see_operator_bridge_without_relaxing_execution_auth(self):
        # Datapack load-time source has level-2 permission but no Player.  A
        # source.getPlayer() check in the Brigadier .requires() hides M03 from
        # .mcfunction parser and breaks all non-menu entries on /reload.
        code=JAVA.read_text(encoding='utf-8')
        self.assertIn('Commands.literal("m03").requires(s->s.hasPermission(2))',code)
        self.assertNotIn('Commands.literal("m03").requires(s->s.hasPermission(2)&&s.getPlayer()!=null)',code)
        self.assertIn('if(c.getSource().getPlayer()==null || !c.getSource().hasPermission(2))',code)
        self.assertIn('!s.operator.equals(src.getPlayer().getUUID())',code)

    def test_companion_bridge_command_handlers(self):
        text=JAVA.read_text(encoding='utf-8')
        for phrase in ['loss-terminal-auto','fault-clear','measure-auto','follow-self','unfollow','move-all',
                       'static int terminalAuto','static int measureAuto','static int moveAll']:
            with self.subTest(phrase=phrase):self.assertIn(phrase,text)

    def test_count_workloads_are_separate_and_bounded(self):
        for n in (1,3,5,10):
            self.assertIn(f'm03 measure-auto {n} 120', (FUNC/f'test10/start{n}.mcfunction').read_text())
            self.assertIn(f'm03 move-all {n}', (FUNC/f'test10/workload{n}.mcfunction').read_text())

    def test_real_lifecycle_not_faked(self):
        for name in ['test6b/offline','test6b/restart','test8/after']:
            body=(FUNC/(name+'.mcfunction')).read_text()
            self.assertNotIn('Test6B PASS',body)
            self.assertNotIn('Save/Rejoin PASS',body)
            self.assertIn('snapshot',body)

    def test_terminal_loss_uses_real_task_bridge(self):
        for path,route in [('test7/finalize_loss','finalize'),('test7/cancel_loss','cancel-ack')]:
            self.assertIn('m03 loss-terminal-auto yui '+route,(FUNC/(path+'.mcfunction')).read_text())

if __name__=='__main__':unittest.main(verbosity=2)
