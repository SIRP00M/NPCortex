# C-F1 D+ D1-D5 bounded diagnostic transaction bridge (Windows, Java 17 + Python >= 3.10).
# Does NOT start Minecraft, execute world actions, or contact GitHub.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaDir = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/cplus'
$testDir = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex'
$oldPython = $env:PYTHONPATH
$out = Join-Path ([IO.Path]::GetTempPath()) ('npcortex_cf1_bridge_' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
    $version = (& javac -version 2>&1) -join ' '
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[.\+\-]|$)') { throw "JDK 17 required: $version" }
    $classes = @('CPlusF1TransportSelfTest','CPlusF1AsyncDiagnosticsSelfTest','CPlusF1DPlusSelfTest','CPlusF0ContractsSelfTest')
    $sources = @((Get-ChildItem -LiteralPath $javaDir -Filter '*.java' | ForEach-Object {$_.FullName}))
    $sources += @($classes | ForEach-Object { Join-Path $testDir ($_.ToString() + '.java') })
    & javac --release 17 -encoding UTF-8 -d $out @sources
    if ($LASTEXITCODE -ne 0) {throw 'Java contract compile failed'}
    foreach ($test in $classes) {
        if ($test -eq 'CPlusF1TransportSelfTest') {
            & java -cp $out "io.github.sirp00m.npcortex.$test" (Join-Path $out 'wire.jsonl')
        } else {
            & java -cp $out "io.github.sirp00m.npcortex.$test"
        }
        if ($LASTEXITCODE -ne 0) { throw "Java regression failed: $test" }
    }
    $env:PYTHONPATH = Join-Path $root 'backend/src'
    Push-Location $root
    try {
        & python -c "import sys; assert sys.version_info >= (3,10), sys.version"
        if ($LASTEXITCODE -ne 0) {throw 'Python >=3.10 required'}
        & python -c "import json,sys;from npcortex_backend.cplus_f1_bridge import validate_wire;rows=[validate_wire(json.loads(s)) for s in open(sys.argv[1],encoding='utf8')];assert [x['sequence'] for x in rows]==[1,2,3];print('PASS Java-to-Python binary hash/epoch/sequence parity, events=3')" (Join-Path $out 'wire.jsonl')
        if ($LASTEXITCODE -ne 0) {throw 'Cross-language parity failed'}
        foreach ($pattern in @('test_cplus_f1_bridge.py','test_cplus_f1_dplus.py','test_cplus_f0_contracts.py','test_c2*.py','test_persistence_lifecycle.py')) {
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -p $pattern -q
            if ($LASTEXITCODE -ne 0) {throw "Backend regression failed: $pattern"}
        }
    } finally { Pop-Location }
    Write-Host 'PASS C-F1 D+ diagnostic transaction bridge focused tests.'
    Write-Host 'PENDING separately: Forge build, real Windows gameplay, process-kill, actual world mutation, 1/3/5/10 NPC MSPT.'
} finally {
    $env:PYTHONPATH = $oldPython
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
