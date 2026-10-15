# C1-P1-A pure Java motion-plan acceptance (does NOT compile Forge or test Minecraft physics).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/C1P1MotionPlan.java'
$test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/C1P1MotionPlanSelfTest.java'
$javacVersion = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacVersion -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Please set JDK 17 on PATH before running this test. Found $javacVersion"
}
$buildDir = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_c1_p1_' + [Guid]::NewGuid().ToString('N'))
New-Item -Type Directory -Force $buildDir | Out-Null
try {
    & javac --release 17 -d $buildDir $main $test
    if ($LASTEXITCODE -ne 0) { throw 'C1-P1 javac failed' }
    & java -cp $buildDir io.github.sirp00m.npcortex.C1P1MotionPlanSelfTest
    if ($LASTEXITCODE -ne 0) { throw 'C1-P1 unit test failed' }
} finally {
    Remove-Item -LiteralPath $buildDir -Recurse -Force -ErrorAction SilentlyContinue
}
