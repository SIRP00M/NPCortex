# Backend-only; verifies project base SHA-256 before any mutation.
param([string]$ProjectRoot = 'F:\AI\NPCortex')
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..') -ErrorAction Stop).Path
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$manifest = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'v082-atomic-brain-choices-fix1-manifest.json') -Raw | ConvertFrom-Json
$version = Join-Path $root 'forge-mod\gradle.properties'
if (!(Test-Path -LiteralPath $version) -or (Get-Content -LiteralPath $version -Raw) -notmatch '(?m)^mod_version=0\.8\.2-alpha\s*$') {
    throw 'Expected NPCortex v0.8.2-alpha. No changes made.'
}
$allowed = @('backend/src/npcortex_backend/', 'backend/tests/', 'docs/releases/')
$operations = @()
foreach ($e in $manifest.files) {
    $rel = [string]$e.path
    if (!$rel -or $rel.Contains('..') -or $rel.StartsWith('/') -or !(($allowed | Where-Object { $rel.StartsWith($_) }).Count -gt 0)) {
        throw "Invalid patch path '$rel'. No changes made."
    }
    $win = $rel.Replace('/', '\')
    $src = Join-Path $sourceRoot $win
    $dst = Join-Path $root $win
    if (!(Test-Path -LiteralPath $src -PathType Leaf)) { throw "Patch file missing: $rel" }
    $got = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($got -ne [string]$e.new_sha256) { throw "Corrupt patch: $rel" }
    $exists = Test-Path -LiteralPath $dst -PathType Leaf
    $existing = $null
    if ($exists) {
        $existing = (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($existing -ne [string]$e.old_sha256 -and $existing -ne [string]$e.new_sha256) {
            throw "Locally modified or wrong base: $rel. No files were changed."
        }
    } elseif ($null -ne $e.old_sha256) {
        throw "Expected original file not present: $rel. No files were changed."
    }
    $operations += [pscustomobject]@{ Path=$rel; Source=$src; Destination=$dst; Exists=$exists;
        Already=($exists -and $existing -eq [string]$e.new_sha256); Hash=[string]$e.new_sha256 }
}
$backup = Join-Path $root ('.npcortex-backups\v082-atomic-brain-choices-fix1-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$applied = @()
try {
    foreach ($o in $operations) {
        if ($o.Already) { continue }
        if ($o.Exists) {
            $backupFile = Join-Path $backup ($o.Path.Replace('/', '\'))
            New-Item -Path (Split-Path -Parent $backupFile) -ItemType Directory -Force | Out-Null
            Copy-Item -LiteralPath $o.Destination -Destination $backupFile -Force
        }
        New-Item -Path (Split-Path -Parent $o.Destination) -ItemType Directory -Force | Out-Null
        $applied += $o
        Copy-Item -LiteralPath $o.Source -Destination $o.Destination -Force
        if ((Get-FileHash -LiteralPath $o.Destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $o.Hash) {
            throw "Post-copy hash mismatch: $($o.Path)"
        }
    }
} catch {
    foreach ($o in $applied) {
        $backupFile = Join-Path $backup ($o.Path.Replace('/', '\'))
        if (Test-Path -LiteralPath $backupFile) { Copy-Item -LiteralPath $backupFile -Destination $o.Destination -Force }
        elseif (-not $o.Exists) { Remove-Item -LiteralPath $o.Destination -Force -ErrorAction SilentlyContinue }
    }
    throw
}
Write-Host "Installed Atomic Brain Choice Fix 1. Changed: $($applied.Count). Backup: $backup" -ForegroundColor Green
Write-Host 'Restart Python Backend: .\scripts\dev-backend.ps1 ; no Forge rebuild required.' -ForegroundColor Yellow
Write-Host 'Backend tests / live Qwen / in-game outcomes must be verified separately.' -ForegroundColor Yellow
