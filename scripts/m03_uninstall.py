"""Remove exact temporary hooks only, with validated source backup first."""
from pathlib import Path
import datetime
import shutil
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'forge-mod/src/main/java/io/github/sirp00m/npcortex'
hooks={JAVA/'NPCortex.java':[
    '        MinecraftForge.EVENT_BUS.addListener(CPlusM03Runtime::tick); // TEMP M03\n',
    '        MinecraftForge.EVENT_BUS.addListener(CPlusM03Runtime::stopping); // TEMP M03\n'],
    JAVA/'CPlusF1DiagnosticRuntime.java':['        cplus.then(CPlusM03Runtime.commands()); // TEMP M03\n'],
    JAVA/'CPlusOutbox.java':[
    '            CPlusM03Fault.check(dir,route,body,false); // TEMP M03, inert unless explicitly armed\n',
    '            CPlusM03Fault.check(dir,route,body,true); // TEMP M03, before runtime ACK/handoff\n'],
    ROOT/'forge-mod/build.gradle':['''// TEMP M03 acceptance tests; explicit invocation, no production scheduler.
tasks.register('verifyM03', JavaExec) {
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'io.github.sirp00m.npcortex.CPlusM03SelfTest'
}
''']}
remove=[JAVA/'CPlusM03Runtime.java',JAVA/'CPlusM03Fault.java',
        ROOT/'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusM03SelfTest.java']
changes={}
for file,needles in hooks.items():
    text=file.read_text(encoding='utf-8')
    for needle in needles:
        if text.count(needle)!=1:raise SystemExit('Hook changed/missing; no changes: '+str(file))
        text=text.replace(needle,'')
    changes[file]=text
for file in remove:
    if not file.is_file() or file.is_symlink():raise SystemExit('Unexpected source; no changes')
backup=ROOT/'docs/m03-final/uninstall-backup'/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
backup.mkdir(parents=True,exist_ok=False)
for file in [*changes,*remove]:
    dest=backup/file.relative_to(ROOT);dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,dest)
for file,text in changes.items():file.write_text(text,encoding='utf-8')
for file in remove:file.unlink()
print('Temporary hooks removed. Production recovery validation retained. Backup:',backup)
