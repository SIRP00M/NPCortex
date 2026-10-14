param([ValidateRange(1,10)][int]$Repeat=3)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    $env:PYTHONPATH = Join-Path $root 'backend\src'
    for ($i = 1; $i -le $Repeat; $i++) {
        Write-Host "=== B3 HTTP Lifecycle Windows test $i/$Repeat ===" -ForegroundColor Cyan
        python -W error::ResourceWarning -m unittest discover -s backend/tests -p test_http_request_lifecycle.py -v
        if ($LASTEXITCODE -ne 0) {
            throw "FAIL: HTTP Lifecycle test run $i (exit code $LASTEXITCODE)"
        }
        Write-Host "PASS: HTTP Lifecycle run $i" -ForegroundColor Green
    }
    Write-Host "PASS: all $Repeat HTTP Lifecycle runs." -ForegroundColor Green
} finally {
    Pop-Location
}
