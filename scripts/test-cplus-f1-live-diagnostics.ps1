# C-F1 D+ second phase: bounded worker, restart/fencing diagnostics and legacy contract regressions.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/cplus'
$tests = @(
  (Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusF1AsyncDiagnosticsSelfTest.java'),
  (Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusF1DPlusSelfTest.java'),
  (Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusF0ContractsSelfTest.java')
)
$version = (& javac -version 2>&1) -join ' '
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 17(?:[.\+\-]|$)') {
  throw "Eclipse Temurin/OpenJDK 17 required. Found: $version"
}
$out = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex_cf1_diag_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $out | Out-Null
try {
  $sources = @((Get-ChildItem -LiteralPath $main -Filter '*.java' | ForEach-Object { $_.FullName })) + $tests
  & javac --release 17 -encoding UTF-8 -d $out @sources
  if ($LASTEXITCODE -ne 0) { throw 'C-F1 diag pure Java compile failed' }
  foreach ($test in @('CPlusF1AsyncDiagnosticsSelfTest','CPlusF1DPlusSelfTest','CPlusF0ContractsSelfTest')) {
    & java -cp $out "io.github.sirp00m.npcortex.$test"
    if ($LASTEXITCODE -ne 0) { throw "$test failed" }
  }
  Push-Location $root
  try {
    $env:PYTHONPATH = Join-Path $root 'backend/src'
    foreach ($pattern in @('test_cplus_f1_dplus.py','test_cplus_f0_contracts.py','test_c2*.py')) {
      & python -m unittest discover -s backend/tests -p $pattern -q
      if ($LASTEXITCODE -ne 0) { throw "Backend regression failed: $pattern" }
    }
  } finally { Pop-Location }
  Write-Host 'PASS staged C-F1 diagnostic worker and backend focused regressions.'
  Write-Host 'Still PENDING: Forge compile, Windows live command proof, world-effect crash recovery, power loss, 1/3/5/10 NPC performance.'
} finally {
  Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
}
