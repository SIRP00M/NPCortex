# Fix stale $LASTEXITCODE with -SkipTests in v0.8.2-alpha Build Wrapper.
param([string]$ProjectRoot = 'F:\AI\NPCortex')
$ErrorActionPreference = 'Stop'
$patchRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..') -ErrorAction Stop).Path
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$rel = 'scripts/build-agentic-v085.ps1'
$dest = Join-Path $root $rel
$src = Join-Path $patchRoot $rel
if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { throw 'Missing corrected build script in extracted patch.' }
if (-not (Test-Path -LiteralPath $dest -PathType Leaf)) { throw 'NPCortex build script not found.' }
$version = Join-Path $root 'forge-mod/gradle.properties'
if (-not (Test-Path -LiteralPath $version -PathType Leaf) -or
    (Get-Content -LiteralPath $version -Raw) -notmatch '(?m)^mod_version=0\.8\.2-alpha\s*$') {
    throw 'Patch is intended for NPCortex v0.8.2-alpha only; no changes made.'
}
$originalHash = '8e8e3b9930be541ffe7d5c8d47128e4c1f6873ae325dac4c37ccd88df8bce492'
$fixedHash = '35f8a6efc90218bba85f759bea2ca41aedaf80cb9be6d2a11fc4848647d1254f'
$targetHash = (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceHash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sourceHash -ne $fixedHash) { throw 'Hotfix file hash mismatch; refusing to install.' }
if ($targetHash -eq $fixedHash) { Write-Host 'Build wrapper already patched; nothing changed.' -ForegroundColor Green; return }
if ($targetHash -ne $originalHash) { throw 'Your build script has local changes; refusing to overwrite automatically.' }
$backup = Join-Path $root ('.npcortex-backups/v0.8.2-buildfix3-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
Copy-Item -LiteralPath $dest -Destination (Join-Path $backup 'build-agentic-v085.ps1') -Force
Copy-Item -LiteralPath $src -Destination $dest -Force
if ((Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLowerInvariant() -ne $fixedHash) {
    Copy-Item -LiteralPath (Join-Path $backup 'build-agentic-v085.ps1') -Destination $dest -Force
    throw 'Hotfix verification failed; original restored.'
}
Write-Host 'Build wrapper patched. Backup:' $backup -ForegroundColor Green
Write-Host 'Next: .\scripts\build-agentic-v085.ps1 -SkipTests'
