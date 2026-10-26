# M04 Gate 0-2 checks: pure-contract tests + static boundaries + Forge compile.
param([switch]$SkipForge)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:PYTHONPATH = Join-Path $root "backend\src"
function Require-Exit($label) {
  if ($LASTEXITCODE -ne 0) { throw "$label failed with exit $LASTEXITCODE" }
}
python -B .\scripts\test_m04_boundaries.py
Require-Exit "M04 static boundaries"
python -B -m unittest discover -s backend/tests -p test_cplus_m04_contracts.py -v
Require-Exit "M04 backend contracts"
$classes = Join-Path ([System.IO.Path]::GetTempPath()) ("npcortex-m04-contract-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $classes -Force | Out-Null
try {
  javac --release 17 -d $classes `
    .\forge-mod\src\main\java\io\github\sirp00m\npcortex\cplus\CPlusM04Contract.java `
    .\forge-mod\src\test\java\io\github\sirp00m\npcortex\CPlusM04ContractSelfTest.java
  Require-Exit "M04 javac17"
  java -cp $classes io.github.sirp00m.npcortex.CPlusM04ContractSelfTest
  Require-Exit "M04 Java self-test"
} finally {
  Remove-Item -Path $classes -Recurse -Force -ErrorAction SilentlyContinue
}
if (-not $SkipForge) {
  & .\scripts\dev-forge.ps1 -Task build
  Require-Exit "Forge compile/build"
}
Write-Host "M04 Gate 0-2 checks completed. Native mining and gameplay NOT tested." -ForegroundColor Green
