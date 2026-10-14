# NPCortex v0.8.3-alpha: backend + pure Java tests (not a substitute for Forge build).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    & python -m unittest discover -s backend/tests -q
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed' }
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-smart-harvest-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp -Force | Out-Null
    try {
        $javaDir = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex'
        & javac --release 17 -d $tmp `
            (Join-Path $javaDir 'TreeSpecies.java') `
            (Join-Path $javaDir 'GatherTaskPolicy.java') `
            (Join-Path $javaDir 'GatherTaskIntent.java') `
            (Join-Path $javaDir 'TreeHarvestPolicy.java') `
            (Join-Path $javaDir 'BulkTransferIntent.java') `
            (Join-Path $root 'tests\java\GatherTaskPolicyTest.java') `
            (Join-Path $root 'tests\java\QuantityIntentTest.java') `
            (Join-Path $root 'tests\java\TreeHarvestPolicyTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
        foreach ($test in @('GatherTaskPolicyTest', 'QuantityIntentTest', 'TreeHarvestPolicyTest')) {
            & java -cp $tmp $test
            if ($LASTEXITCODE -ne 0) { throw "$test failed" }
        }
    } finally {
        Remove-Item $tmp -Force -Recurse -ErrorAction SilentlyContinue
    }
    Write-Host 'Smart harvest tests passed. Run Forge build next.' -ForegroundColor Green
} finally {
    Pop-Location
}
