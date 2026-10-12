# v0.8.4-alpha: unit/regression tests; ALSO run ForgeGradle build on Windows.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $root
try {
    & (Join-Path $PSScriptRoot 'smart-harvest-check.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Smart harvest regression failed.' }
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-scaffold-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        $src = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex'
        & javac --release 17 -d $tmp (Join-Path $src 'ScaffoldPolicy.java') `
            (Join-Path $root 'tests\java\ScaffoldPolicyTest.java')
        if ($LASTEXITCODE -ne 0) { throw 'ScaffoldPolicy compilation failed.' }
        & java -cp $tmp ScaffoldPolicyTest
        if ($LASTEXITCODE -ne 0) { throw 'ScaffoldPolicy assertions failed.' }
    } finally { Remove-Item -Force -Recurse $tmp -ErrorAction SilentlyContinue }
    Write-Host 'Tree scaffold unit tests passed. Next run: .\scripts\dev-forge.ps1 -Task build' -ForegroundColor Green
} finally { Pop-Location }
