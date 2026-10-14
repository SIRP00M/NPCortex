# NPCortex v0.8.2-alpha Vanilla Trees Foundation 1 — Build Fix 1.
# One-file SHA-256 checked installer; never touches saves, SQLite or character packs.
param([string]$ProjectRoot = 'F:\AI\NPCortex')
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'vanilla-trees-buildfix1-v082-manifest.json') -Raw | ConvertFrom-Json
$versionFile = Join-Path $root 'forge-mod\gradle.properties'
if (!(Test-Path -LiteralPath $versionFile) -or
    (Get-Content -LiteralPath $versionFile -Raw) -notmatch '(?m)^mod_version=0\.8\.2-alpha\s*$') {
    throw 'Expected NPCortex mod_version=0.8.2-alpha. No changes made.'
}
if ($manifest.files.Count -ne 1) { throw 'Unexpected manifest structure. No changes made.' }
$entry = $manifest.files[0]
$relative = [string]$entry.path
if ($relative -ne 'forge-mod/src/main/java/io/github/sirp00m/npcortex/WholeTreeTracker.java') {
    throw 'Unexpected target. No changes made.'
}
$winpath = $relative.Replace('/', '\')
$src = Join-Path $sourceRoot $winpath
$dst = Join-Path $root $winpath
if (!(Test-Path -LiteralPath $src -PathType Leaf) -or !(Test-Path -LiteralPath $dst -PathType Leaf)) {
    throw 'WholeTreeTracker.java missing; expected existing VanillaTreesFoundation1 project.'
}
$sourceHash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant()
$destHash = (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sourceHash -ne [string]$entry.new_sha256) { throw 'Update file hash mismatch. No changes made.' }
if ($destHash -eq $sourceHash) { Write-Host 'Build Fix 1 already installed.' -ForegroundColor Green; return }
if ($destHash -ne [string]$entry.old_sha256) {
    throw 'Local WholeTreeTracker.java differs from VanillaTreesFoundation1. No changes made; inspect local edits.'
}
$backupDir = Join-Path $root ('.npcortex-backups\v082-tree-buildfix1-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$backupPath = Join-Path $backupDir $winpath
New-Item -ItemType Directory -Path (Split-Path -Parent $backupPath) -Force | Out-Null
Copy-Item -LiteralPath $dst -Destination $backupPath -Force
try {
    Copy-Item -LiteralPath $src -Destination $dst -Force
    $installed = (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($installed -ne $sourceHash) { throw 'Installed file hash mismatch.' }
} catch {
    Copy-Item -LiteralPath $backupPath -Destination $dst -Force
    throw
}
Write-Host "Installed Vanilla Trees Build Fix 1. Backup: $backupDir" -ForegroundColor Green
Write-Host 'Next: .\scripts\build-agentic-v085.ps1 -SkipTests'
Write-Host 'This installer does not verify Forge build or Minecraft behavior.' -ForegroundColor Yellow
