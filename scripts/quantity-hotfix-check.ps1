# NPCortex v0.8.2-alpha quantity/full-stack checks. Run Forge build afterwards.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    & python -m unittest discover -s backend/tests -q
    if ($LASTEXITCODE -ne 0) { throw 'Backend regression tests failed' }
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-quantity-' + [guid]::NewGuid().ToString('N'))
    New-Item -Path $tmp -ItemType Directory -Force | Out-Null
    try {
        $javaDir = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex'
        & javac --release 17 -d $tmp `
            (Join-Path $javaDir 'TreeSpecies.java') `
            (Join-Path $javaDir 'GatherTaskPolicy.java') `
            (Join-Path $javaDir 'GatherTaskIntent.java') `
            (Join-Path $javaDir 'BulkTransferIntent.java') `
            (Join-Path $root 'tests\java\GatherTaskPolicyTest.java') `
            (Join-Path $root 'tests\java\QuantityIntentTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Java compile failed' }
        & java -cp $tmp GatherTaskPolicyTest
        if ($LASTEXITCODE -ne 0) { throw 'GatherTaskPolicyTest failed' }
        & java -cp $tmp QuantityIntentTest
        if ($LASTEXITCODE -ne 0) { throw 'QuantityIntentTest failed' }
    } finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host 'Local quantity tests passed. Now run: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Green
} finally { Pop-Location }
