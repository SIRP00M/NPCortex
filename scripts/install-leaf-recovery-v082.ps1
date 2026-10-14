# v0.8.2-alpha Agent Recovery Fix 2. Run from the EXTRACTED Update Only ZIP.
param([string]$ProjectRoot = 'F:\AI\NPCortex')
$ErrorActionPreference = 'Stop'
$bundle = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..') -ErrorAction Stop).Path
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$manifestPath = Join-Path $PSScriptRoot 'leaf-recovery-v082-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'Missing leaf recovery manifest.' }
$version = Join-Path $root 'forge-mod\gradle.properties'
if (-not (Test-Path -LiteralPath $version -PathType Leaf) -or
    (Get-Content -LiteralPath $version -Raw) -notmatch '(?m)^mod_version=0\.8\.2-alpha\s*$') {
    throw 'Requires NPCortex v0.8.2-alpha. Nothing changed.'
}
$entries = @(Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json)
$pending = @()
foreach ($entry in $entries) {
    if ($entry.path -notmatch '^[a-zA-Z0-9_./-]+$' -or $entry.path.Contains('..')) {
        throw 'Unsafe patch path.'
    }
    $rel = $entry.path -replace '/', '\'
    $src = Join-Path $bundle $rel
    $dst = Join-Path $root $rel
    if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { throw "Missing patch file: $rel" }
    $srcHash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($srcHash -ne $entry.new_sha256) { throw "Invalid patch SHA-256: $rel" }
    $exists = Test-Path -LiteralPath $dst -PathType Leaf
    $original = if ($exists) { (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
    if ($original -eq $entry.new_sha256) { continue }
    if (($null -eq $entry.old_sha256 -and $exists) -or
        ($null -ne $entry.old_sha256 -and $original -ne $entry.old_sha256)) {
        throw "Unexpected original at $rel. No files changed. Reconcile your local edits or use the exact Fix1 base."
    }
    $pending += [pscustomobject]@{Rel=$rel;Src=$src;Dst=$dst;Exists=$exists;NewHash=$entry.new_sha256}
}
if ($pending.Count -eq 0) { Write-Host 'Agent Recovery Fix 2 already applied.' -ForegroundColor Green; return }
$backup = Join-Path $root ('.npcortex-backups\v082-leaf-recovery-fix2-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$applied = @()
try {
    foreach ($file in $pending) {
        if ($file.Exists) {
            $originalBackup = Join-Path $backup $file.Rel
            New-Item -ItemType Directory -Path (Split-Path -Parent $originalBackup) -Force | Out-Null
            Copy-Item -LiteralPath $file.Dst -Destination $originalBackup -Force
        }
        New-Item -ItemType Directory -Path (Split-Path -Parent $file.Dst) -Force | Out-Null
        $applied += $file
        Copy-Item -LiteralPath $file.Src -Destination $file.Dst -Force
        if ((Get-FileHash -LiteralPath $file.Dst -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.NewHash) {
            throw "Copied patch changed unexpectedly: $($file.Rel)"
        }
    }
} catch {
    foreach ($file in $applied) {
        if ($file.Exists) { Copy-Item -LiteralPath (Join-Path $backup $file.Rel) -Destination $file.Dst -Force }
        elseif (Test-Path -LiteralPath $file.Dst) { Remove-Item -LiteralPath $file.Dst -Force }
    }
    throw
}
Write-Host "Installed Leaf Recovery Fix 2 ($($pending.Count) files). Backup: $backup" -ForegroundColor Green
Write-Host 'Run: .\scripts\test-v082.ps1 ; .\scripts\build-agentic-v085.ps1 -SkipTests'
