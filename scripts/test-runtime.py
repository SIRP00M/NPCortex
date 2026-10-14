"""Run backend tests, executable Java core tests, and Forge syntax parsing.
Uses the Java 17 compiler module; no Gradle, Minecraft or Ollama required.
This does not replace a Forge build or the in-game acceptance suite.
"""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
def run(*args):
    subprocess.run(args, cwd=ROOT, check=True)

run(sys.executable, '-m', 'unittest', 'discover', '-s', 'backend/tests')
with tempfile.TemporaryDirectory(prefix='npcortex-runtime-') as temp:
    package = ROOT/'forge-mod/src/main/java/io/github/sirp00m/npcortex'
    sources = list((package/'agent').glob('*.java')) + [ROOT/'tests/runtime/RuntimeCoreTest.java',ROOT/'tests/runtime/GoalCompletionLedgerTest.java',ROOT/'tests/runtime/UniversalAccessTest.java',ROOT/'tests/runtime/MaterialApproachTest.java',ROOT/'tests/java/JavaSyntaxSmokeCheck.java',package/'AgenticTaskPolicy.java',package/'ScaffoldPolicy.java',package/'TreeSpecies.java',ROOT/'tests/java/ScaffoldPolicyTest.java']
    run('java','-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-d',temp,*map(str,sources))
    run('java','-cp',temp,'io.github.sirp00m.npcortex.agent.RuntimeCoreTest')
    run('java','-cp',temp,'io.github.sirp00m.npcortex.agent.GoalCompletionLedgerTest')
    run('java','-cp',temp,'io.github.sirp00m.npcortex.agent.UniversalAccessTest')
    run('java','-cp',temp,'io.github.sirp00m.npcortex.agent.MaterialApproachTest')
    run('java','-cp',temp,'ScaffoldPolicyTest')
    run('java','-cp',temp,'JavaSyntaxSmokeCheck',str(ROOT/'forge-mod/src/main/java'))
print('Backend/core/syntax checks passed. Forge build and real Minecraft acceptance NOT run by this script.')
