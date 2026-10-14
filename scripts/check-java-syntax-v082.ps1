# Standalone syntax parser; deliberately does not claim a successful Forge build.
param([string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = (Get-Command java -ErrorAction Stop).Source
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ('npcortex-syntax-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp -Force | Out-Null
try {
    & $javac --release 17 -d $temp (Join-Path $root 'tests/java/JavaSyntaxSmokeCheck.java')
    if ($LASTEXITCODE -ne 0) { throw 'Syntax checker failed to compile.' }
    & $java -cp $temp JavaSyntaxSmokeCheck (Join-Path $root 'forge-mod/src/main/java')
    if ($LASTEXITCODE -ne 0) { throw 'Forge Java syntax check FAILED.' }
    Write-Host 'Java source syntax OK; full Forge build NOT RUN.' -ForegroundColor Green
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}
