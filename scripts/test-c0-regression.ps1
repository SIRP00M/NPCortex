param([ValidateRange(1,10)][int]$Repeat=3)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    for ($i=1; $i -le $Repeat; $i++) {
        Write-Host "=== NPCortex C0 Windows strict tests $i/$Repeat ===" -ForegroundColor Cyan
        python -W error::ResourceWarning -m unittest discover -s backend/tests -v
        if ($LASTEXITCODE -ne 0) { throw "FAIL: C0 regression run $i exit code $LASTEXITCODE" }
        Write-Host "PASS: C0 regression run $i" -ForegroundColor Green
    }
    Write-Host "PASS: all $Repeat C0 regression runs." -ForegroundColor Green
} finally { Pop-Location }
