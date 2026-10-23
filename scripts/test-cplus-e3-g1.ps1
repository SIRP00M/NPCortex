# E3-D G1 only: backend contract regression. Forge/gameplay separate.
param([switch]$IncludeFullBackend)
$ErrorActionPreference = 'Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previous=$env:PYTHONPATH
try {
    Push-Location $root
    try {
        $env:PYTHONPATH=(Resolve-Path '.\backend\src').Path
        foreach($p in @('test_cplus_e3_preview.py','test_cplus_e3_http.py','test_cplus_e2_dispatcher.py',
          'test_cplus_e2_http.py','test_cplus_e1_coordinator.py',
          'test_cplus_e1_http.py','test_cplus_e_preview.py',
          'test_cplus_f2_observation.py','test_cplus_f1_bridge.py',
          'test_cplus_f0_contracts.py')) {
            Write-Host "[E3-D G1 focused] $p"
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -p $p -q
            if ($LASTEXITCODE -ne 0) { throw "E3 G1 tests failed: $p" }
        }
        if($IncludeFullBackend) {
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -q
            if ($LASTEXITCODE -ne 0) { throw 'E3 G1 full backend failed' }
        }
    } finally { Pop-Location }
} finally { $env:PYTHONPATH=$previous }
