# C-F3 E1-E Gate-2: pure backend focused tests. No Gradle, Minecraft or Backend launch.
param([switch]$IncludeFullBackend)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previous = $env:PYTHONPATH
try {
    Push-Location $root
    try {
        $env:PYTHONPATH = (Resolve-Path '.\backend\src').Path
        foreach ($pattern in @('test_cplus_e2_dispatcher.py','test_cplus_e2_http.py',
                                'test_cplus_e1_coordinator.py','test_cplus_e1_http.py',
                                'test_cplus_e_preview.py','test_cplus_f2_observation.py',
                                'test_cplus_f1_bridge.py','test_cplus_f0_contracts.py')) {
            Write-Host "[C-F3 E2 focused] $pattern"
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -p $pattern -q
            if ($LASTEXITCODE -ne 0) { throw "C-F3 E2 failed: $pattern" }
        }
        if ($IncludeFullBackend) {
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -q
            if ($LASTEXITCODE -ne 0) { throw 'Full backend suite failed' }
        }
        Write-Host 'C-F3 E2 focused Python tests passed. Windows Forge/gameplay are separate gates.'
    } finally { Pop-Location }
} finally { $env:PYTHONPATH = $previous }
