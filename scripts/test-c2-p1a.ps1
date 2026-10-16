# P1-A only: dependency-free Navigation Grid plus P0 geometry regressions.
# Does not prove Forge compile, C1 collision integration, MSPT or gameplay.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex'
$tests = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$javacText = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $javacText -notmatch '^javac 17(?:[\.\+\-]|$)') {
    throw "Eclipse Temurin Java 17 required on PATH; found $javacText"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_p1a_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    & javac --release 17 -encoding UTF-8 -d $out `
        (Join-Path $main 'C2NavGrid.java') `
        (Join-Path $main 'C2MoveGeometry.java') `
        (Join-Path $main 'C2Events.java') `
        (Join-Path $main 'C2Scheduler.java') `
        (Join-Path $main 'C1P1MotionPlan.java') `
        (Join-Path $main 'C1P0SyncState.java') `
        (Join-Path $main 'C1InventoryJournal.java') `
        (Join-Path $main 'C1VanillaMotionOwnership.java') `
        (Join-Path $main 'C1PlayerMotionPhase.java') `
        (Join-Path $main 'C1CombatTickPolicy.java') `
        (Join-Path $main 'C1DeathPresentationPolicy.java') `
        (Join-Path $main 'C1P1VanillaTickPolicy.java') `
        (Join-Path $main 'C1VanillaLifecyclePolicy.java') `
        (Join-Path $tests 'C2NavGridSelfTest.java') `
        (Join-Path $tests 'C2FoundationSelfTest.java') `
        (Join-Path $tests 'C1P1MotionPlanSelfTest.java') `
        (Join-Path $tests 'C1P0SyncStateSelfTest.java') `
        (Join-Path $tests 'C1InventoryJournalSelfTest.java') `
        (Join-Path $tests 'C1VanillaMotionOwnershipSelfTest.java') `
        (Join-Path $tests 'C1PlayerMotionPhaseSelfTest.java') `
        (Join-Path $tests 'C1CombatTickPolicySelfTest.java') `
        (Join-Path $tests 'C1DeathPresentationPolicySelfTest.java') `
        (Join-Path $tests 'C1P1VanillaTickPolicySelfTest.java') `
        (Join-Path $tests 'C1VanillaLifecyclePolicySelfTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'C2 P1-A isolated javac failed' }
    foreach ($test in 'C2NavGridSelfTest', 'C2FoundationSelfTest', 'C1P1MotionPlanSelfTest',
            'C1P0SyncStateSelfTest', 'C1InventoryJournalSelfTest',
            'C1VanillaMotionOwnershipSelfTest', 'C1PlayerMotionPhaseSelfTest',
            'C1CombatTickPolicySelfTest', 'C1DeathPresentationPolicySelfTest',
            'C1P1VanillaTickPolicySelfTest', 'C1VanillaLifecyclePolicySelfTest') {
        & java -cp $out ("io.github.sirp00m.npcortex." + $test)
        if ($LASTEXITCODE -ne 0) { throw "P1-A isolated JVM test failed: $test" }
    }
    $env:PYTHONPATH = (Join-Path $root 'backend/src') + [IO.Path]::PathSeparator + (Join-Path $root 'backend/tests')
    Push-Location $root
    try {
        & python -m unittest discover -s backend/tests -q
        if ($LASTEXITCODE -ne 0) { throw 'Full Backend regression failed' }
    } finally { Pop-Location }
    Write-Host 'P1-A isolated tests PASS; NEXT: .\scripts\dev-forge.ps1 -Task build'
} finally {
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
