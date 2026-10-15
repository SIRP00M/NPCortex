param([ValidateRange(1,10)][int]$Repeat=3)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $env:PYTHONPATH=Join-Path $root 'backend\src'
    for ($i=1; $i -le $Repeat; $i++) {
        Write-Host "=== C0 Planner Reliability Test $i/$Repeat ===" -ForegroundColor Cyan
        python -W error::ResourceWarning -m unittest discover -s backend/tests -p test_c0_planner_reliability.py -v
        if ($LASTEXITCODE -ne 0) { throw "FAIL: planner tests $i/$Repeat exit code $LASTEXITCODE" }
        Write-Host "PASS: C0 planner reliability $i/$Repeat" -ForegroundColor Green
    }
    Write-Host "PASS: all $Repeat planner test runs." -ForegroundColor Green
} finally { Pop-Location }
