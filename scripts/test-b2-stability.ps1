param(
    [ValidateRange(1, 10)]
    [int]$Repeat = 3
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repoRoot
try {
    $env:PYTHONPATH = Join-Path $repoRoot 'backend\src'
    Write-Host "NPCortex B2-C Windows regression: $Repeat run(s), strict ResourceWarning" -ForegroundColor Cyan
    for ($i = 1; $i -le $Repeat; $i++) {
        Write-Host "=== RUN $i / $Repeat ===" -ForegroundColor Cyan
        python -W error::ResourceWarning -m unittest discover -s backend/tests -v
        if ($LASTEXITCODE -ne 0) {
            Write-Host "FAIL: run $i ended with exit code $LASTEXITCODE" -ForegroundColor Red
            exit $LASTEXITCODE
        }
        Write-Host "PASS: run $i" -ForegroundColor Green
    }
    Write-Host "PASS: all $Repeat test runs succeeded." -ForegroundColor Green
} finally {
    Pop-Location
}
