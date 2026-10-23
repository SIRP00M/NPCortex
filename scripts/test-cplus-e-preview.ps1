# E-P0 guarded read-only DAG preview: TEST only by default.
# Build and RunGame stay separate via existing dev-forge.ps1 script.
param([switch]$IncludeFullBackend, [switch]$IncludeForgeBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previous = $env:PYTHONPATH
try {
    Push-Location $root
    try {
        $env:PYTHONPATH=(Resolve-Path '.\backend\src').Path
        foreach ($pattern in @('test_cplus_e_preview.py', 'test_cplus_f2_observation.py',
                                'test_cplus_f1_bridge.py', 'test_cplus_f0_contracts.py')) {
            Write-Host "[E-P0] Python unit regression: $pattern"
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -p $pattern -q
            if ($LASTEXITCODE -ne 0) {throw "Failed: $pattern"}
        }
        if ($IncludeFullBackend) {
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -q
            if ($LASTEXITCODE -ne 0) {throw 'Full Backend suite failed'}
        }
        if ($IncludeForgeBuild) {
            & (Join-Path $PSScriptRoot 'dev-forge.ps1') -Task build
            if ($LASTEXITCODE -ne 0) {throw 'Forge build failed'}
        }
        Write-Host 'PASS E-P0 read-only authenticated DAG preview. Minecraft gameplay unverified.'
    } finally { Pop-Location }
} finally { $env:PYTHONPATH=$previous }
