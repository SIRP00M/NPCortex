# v0.8.2-alpha Agent Recovery Fix 1: safe update from BuildFix3.
param([string]$ProjectRoot = 'F:\AI\NPCortex')
$ErrorActionPreference = 'Stop'
$bundle = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..') -ErrorAction Stop).Path
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$manifestPath = Join-Path $PSScriptRoot 'agent-recovery-v082-manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'Missing recovery manifest.' }
$verFile = Join-Path $root 'forge-mod\gradle.properties'
if (-not (Test-Path -LiteralPath $verFile -PathType Leaf) -or
    (Get-Content -LiteralPath $verFile -Raw) -notmatch '(?m)^mod_version=0\.8\.2-alpha\s*$') {
    throw 'This patch supports only NPCortex v0.8.2-alpha. Nothing changed.'
}
$items = @(Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json)
$work = @()
foreach ($entry in $items) {
    if ($entry.path -notmatch '^[a-zA-Z0-9_./-]+$' -or $entry.path.Contains('..')) { throw 'Unsafe patch manifest path.' }
    $rel = $entry.path -replace '/', '\'
    $src = Join-Path $bundle $rel
    $dst = Join-Path $root $rel
    if (-not (Test-Path -LiteralPath $src -PathType Leaf)) { throw "Missing patch file: $rel" }
    $srcHash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($srcHash -ne $entry.new_sha256) { throw "Patch SHA-256 mismatch: $rel" }
    $existing = Test-Path -LiteralPath $dst -PathType Leaf
    $dstHash = if ($existing) { (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
    if ($dstHash -eq $entry.new_sha256) { continue }
    if (($null -eq $entry.old_sha256 -and $existing) -or
        ($null -ne $entry.old_sha256 -and $dstHash -ne $entry.old_sha256)) {
        throw "Unexpected local change at $rel. No files changed. Back it up and reconcile manually."
    }
    $work += [pscustomobject]@{Rel=$rel; Src=$src; Dst=$dst; Existed=$existing; Expected=$entry.new_sha256}
}
if ($work.Count -eq 0) { Write-Host 'Recovery Fix 1 already installed; no changes.' -ForegroundColor Green; return }
$backup = Join-Path $root ('.npcortex-backups\v082-recovery-fix1-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$applied = @()
try {
    foreach ($f in $work) {
        if ($f.Existed) {
            $bak = Join-Path $backup $f.Rel
            New-Item -ItemType Directory -Path (Split-Path -Parent $bak) -Force | Out-Null
            Copy-Item -LiteralPath $f.Dst -Destination $bak -Force
        }
        New-Item -ItemType Directory -Path (Split-Path -Parent $f.Dst) -Force | Out-Null
        $applied += $f
        Copy-Item -LiteralPath $f.Src -Destination $f.Dst -Force
        if ((Get-FileHash -LiteralPath $f.Dst -Algorithm SHA256).Hash.ToLowerInvariant() -ne $f.Expected) {
            throw "Post-copy checksum mismatch: $($f.Rel)"
        }
    }
} catch {
    foreach ($f in $applied) {
        if ($f.Existed) { Copy-Item -LiteralPath (Join-Path $backup $f.Rel) -Destination $f.Dst -Force }
        elseif (Test-Path -LiteralPath $f.Dst) { Remove-Item -LiteralPath $f.Dst -Force }
    }
    throw
}
Write-Host "Installed Recovery Fix 1 ($($work.Count) files); backup at $backup" -ForegroundColor Green
Write-Host 'Next: .\scripts\test-v082.ps1 ; .\scripts\build-agentic-v085.ps1 -SkipTests'
