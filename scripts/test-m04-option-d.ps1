param([switch]$SkipForge)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repo
$env:PYTHONPATH = Join-Path $repo 'backend\src'
python -B .\scripts\test_m04_boundaries.py
if ($LASTEXITCODE -ne 0) { throw 'M04 boundary checks FAILED' }
python -B .\scripts\test_m04_option_d.py
if ($LASTEXITCODE -ne 0) { throw 'M04 Option D harness checks FAILED' }
python -B -m unittest discover -s .\backend\tests -p 'test_cplus_m04*.py' -v
if ($LASTEXITCODE -ne 0) { throw 'M04 Python tests FAILED' }
python -B -m unittest discover -s .\backend\tests -p 'test_m04_acceptance_review.py' -v
if ($LASTEXITCODE -ne 0) { throw 'M04 reviewer fixture tests FAILED' }
$javaClasses=Join-Path $env:TEMP ('npcortex-m04-java-' + [guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $javaClasses | Out-Null
try {
  javac --release 17 -d $javaClasses .\forge-mod\src\main\java\io\github\sirp00m\npcortex\cplus\CPlusM04Contract.java .\forge-mod\src\test\java\io\github\sirp00m\npcortex\CPlusM04ContractSelfTest.java
  if ($LASTEXITCODE -ne 0) { throw 'Java17 M04 compile FAILED' }
  java -cp $javaClasses io.github.sirp00m.npcortex.CPlusM04ContractSelfTest
  if ($LASTEXITCODE -ne 0) { throw 'Java M04 contracts FAILED' }
} finally { Remove-Item -LiteralPath $javaClasses -Recurse -Force -ErrorAction SilentlyContinue }
if (-not $SkipForge) {
  & .\scripts\dev-forge.ps1 -Task build
  if ($LASTEXITCODE -ne 0) { throw 'Forge Build FAILED' }
}
Write-Host 'M04 Option D automated foundation checks passed. Gates 3-6 Gameplay remain HOLD.' -ForegroundColor Yellow
