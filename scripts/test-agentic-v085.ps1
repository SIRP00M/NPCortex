# Run Python backend tests AND pure Java 17 AgenticTaskPolicy self-test.
# Does not imply a Forge build or Minecraft in-game validation.
param([string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path $ProjectRoot).Path
$python = Get-Command 'python' -ErrorAction Stop
Push-Location $root
try {
    Write-Host 'Running all backend unit/API tests...'
    & $python.Source -m unittest discover -s backend/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Backend unit/API tests FAILED.' }
    Write-Host 'Backend unit/API tests PASSED.' -ForegroundColor Green

    $javac = Get-Command 'javac' -ErrorAction Stop
    $java = Get-Command 'java' -ErrorAction Stop
    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString())
    New-Item -Type Directory -Path $tempDir -Force | Out-Null
    try {
        $pkg = 'io.github.sirp00m.npcortex'
        $main = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/AgenticTaskPolicy.java'
        $test = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/AgenticTaskPolicySelfTest.java'
        & $javac.Source --release 17 -d $tempDir $main $test
        if ($LASTEXITCODE -ne 0) { throw 'Java 17 standalone policy compile FAILED.' }
        & $java.Source -cp $tempDir "$pkg.AgenticTaskPolicySelfTest"
        if ($LASTEXITCODE -ne 0) { throw 'Java policy tests FAILED.' }
        Write-Host 'Java 17 language-level AgenticTaskPolicy tests PASSED.' -ForegroundColor Green
    } finally {
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    Write-Host 'Forge Minecraft build: NOT RUN by this test script.' -ForegroundColor Yellow
    Write-Host 'In-game tests: NOT RUN by this test script.' -ForegroundColor Yellow
} finally { Pop-Location }
