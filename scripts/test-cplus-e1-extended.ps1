# E1 Gate-1 focused tests. Build/Run/Backend are separate actions by design.
param([switch]$IncludeFullBackend)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previous = $env:PYTHONPATH
try {
    Push-Location $root
    try {
        $env:PYTHONPATH = (Resolve-Path '.\backend\src').Path
        foreach ($pattern in @('test_cplus_e1_coordinator.py', 'test_cplus_e1_http.py',
                                'test_cplus_e_preview.py', 'test_cplus_f2_observation.py',
                                'test_cplus_f1_bridge.py', 'test_cplus_f0_contracts.py')) {
            Write-Host "[E1 Gate1] $pattern"
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -p $pattern -q
            if ($LASTEXITCODE -ne 0) { throw "E1 Gate1 failed: $pattern" }
        }
        if ($IncludeFullBackend) {
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -q
            if ($LASTEXITCODE -ne 0) { throw 'Full backend suite failed' }
        }
        Write-Host 'E1 focused tests PASS (Python only; Forge and Gameplay are separate gates).'
    } finally { Pop-Location }
} finally { $env:PYTHONPATH = $previous }
