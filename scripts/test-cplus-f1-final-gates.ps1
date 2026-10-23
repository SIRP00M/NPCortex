# NPCortex C+ C-F1 D+ final diagnostic foundation regression (NO gameplay mutation).
# JDK 17 + Python 3.13 target on Windows. Run from any folder.
# Never creates or repairs any live Minecraft journal, world, SQLite DB, or credential.
param([switch]$IncludeForgeBuild)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pythonWas = $env:PYTHONPATH
try {
    Push-Location $root
    try {
        Write-Host '[C-F1 Gate 1/5] PowerShell auto-auth bootstrap and cross-language Java parity'
        & (Join-Path $PSScriptRoot 'test-cplus-f1-auto-local-auth.ps1')
        if ($LASTEXITCODE -ne 0) {throw 'Auto Local Auth runner failed'}
        Write-Host '[C-F1 Gate 2/5] Journal, Cursor, Network and Python focused fault regressions'
        & (Join-Path $PSScriptRoot 'test-cplus-f1-recovery-hardening.ps1')
        if ($LASTEXITCODE -ne 0) {throw 'C-F1 recovery hardening runner failed'}
        Write-Host '[C-F1 Gate 3/5] Windows runner quotation regression'
        $env:PYTHONPATH = Join-Path $root 'backend/src'
        & python -W error::ResourceWarning -m unittest discover -s backend/tests -p test_cplus_f1_windows_runner.py -q
        if ($LASTEXITCODE -ne 0) {throw 'Windows runner regression failed'}
        Write-Host '[C-F1 Gate 4/5] Full Backend suite'
        & python -W error::ResourceWarning -m unittest discover -s backend/tests -q
        if ($LASTEXITCODE -ne 0) {throw 'Full Backend suite failed'}
        Write-Host '[C-F1 Gate 5/5] Follow / Navigation pure Java regression'
        & (Join-Path $PSScriptRoot 'test-c2-airborne-target-projection.ps1')
        if ($LASTEXITCODE -ne 0) {throw 'C1/C2 pure Java regression failed'}
        if ($IncludeForgeBuild) {
            Write-Host '[C-F1 optional gate] Full Forge build'
            & (Join-Path $PSScriptRoot 'dev-forge.ps1') -Task build
            if ($LASTEXITCODE -ne 0) {throw 'Forge build failed'}
        }
        Write-Host 'PASS C-F1 automated diagnostic foundation regression commands.'
        Write-Host 'PENDING SEPARATELY: Windows in-game cursor injection & genuine 1/3/5/10 NPC MSPT, destructive crash test optional on disposable copy.'
    } finally { Pop-Location }
} finally { $env:PYTHONPATH = $pythonWas }
