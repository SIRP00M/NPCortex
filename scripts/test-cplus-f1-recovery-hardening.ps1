# NPCortex GWI C+ C-F1 D+ Recovery Hardening H1-H5, operator/manual-only diagnostic scope.
# Requires Windows Temurin JDK17, Python >=3.10. Does not launch or mutate a Minecraft world.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$oldPython = $env:PYTHONPATH
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('npcortex_cf1_harden_' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    $javac = (& javac -version 2>&1) -join ' '
    if ($LASTEXITCODE -ne 0 -or $javac -notmatch '^javac 17(?:[.\+\-]|$)') {throw "Temurin Java 17 JDK required: $javac"}
    $javaDir = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/cplus'
    $testDir = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
    $classes = @('CPlusF1RecoveryHardeningSelfTest','CPlusF1TransportSelfTest',
                 'CPlusF1AsyncDiagnosticsSelfTest','CPlusF1DPlusSelfTest','CPlusF0ContractsSelfTest')
    $sources = @((Get-ChildItem -LiteralPath $javaDir -Filter '*.java' | ForEach-Object {$_.FullName}))
    $sources += @($classes | ForEach-Object {Join-Path $testDir ($_.ToString()+'.java')})
    & javac --release 17 -encoding UTF-8 -d $tmp @sources
    if ($LASTEXITCODE -ne 0) {throw 'C-F1 Java compilation failed'}
    foreach ($test in $classes) {
        & java -cp $tmp "io.github.sirp00m.npcortex.$test"
        if ($LASTEXITCODE -ne 0) {throw "Java regression failed: $test"}
    }
    $env:PYTHONPATH = (Join-Path $root 'backend/src')
    Push-Location $root
    try {
        foreach ($pattern in @('test_cplus_f1_recovery_hardening.py','test_cplus_local_auth.py',
                'test_cplus_f1_bridge.py','test_cplus_f1_dplus.py',
                'test_cplus_f0_contracts.py','test_c2*.py','test_persistence_lifecycle.py')) {
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -p $pattern -q
            if ($LASTEXITCODE -ne 0) {throw "Backend regression failed: $pattern"}
        }
    } finally {Pop-Location}
    Write-Host 'PASS C-F1 H1-H5 focused synthetic/backend/Java regression.'
    Write-Host 'PENDING separately: Forge build, in-game Windows verification, real MSPT, physical power loss/world effects.'
} finally {
    $env:PYTHONPATH = $oldPython
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
