# C-F1 D+ Fix #1: Windows-safe SQLite fixture connection lifecycle.
# Requires Python >=3.10 (target Windows Python 3.13); does not touch a live world/database.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previousPythonPath = $env:PYTHONPATH
try {
    $env:PYTHONPATH = Join-Path $root 'backend/src'
    Push-Location $root
    try {
        & python -c "import sys; print('Python:', sys.version); assert sys.version_info >= (3,10), 'Python >=3.10 required'"
        if ($LASTEXITCODE -ne 0) { throw 'Python version check failed' }
        foreach ($pattern in @(
            'test_cplus_f1_dplus.py',
            'test_cplus_f0_contracts.py',
            'test_c2*.py',
            'test_persistence_lifecycle.py'
        )) {
            Write-Host "Running $pattern with ResourceWarning escalated..."
            & python -W error::ResourceWarning -m unittest discover -s backend/tests -p $pattern -v
            if ($LASTEXITCODE -ne 0) { throw "Backend regression failed: $pattern" }
        }
        Write-Host 'PASS C-F1 D+ Fix #1 SQLite fixture lifecycle and focused Backend regressions.'
        Write-Host 'PENDING: Windows Python 3.13 execution, Forge build, gameplay and actual world-effect Crash Recovery.'
    } finally {
        Pop-Location
    }
} finally {
    $env:PYTHONPATH = $previousPythonPath
}
