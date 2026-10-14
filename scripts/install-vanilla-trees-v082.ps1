# NPCortex v0.8.2-alpha Vanilla Trees Foundation 1 — from Agent Recovery Fix 2.
# Safe, all-or-nothing SHA-checked source patch. PowerShell 5+.
param([string]$ProjectRoot = 'F:\AI\NPCortex')
$ErrorActionPreference = 'Stop'
$sourceRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$manifestFile = Join-Path $PSScriptRoot 'vanilla-trees-v082-manifest.json'
$manifest = @(Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json)
$version = Join-Path $root 'forge-mod\gradle.properties'
if (!(Test-Path -LiteralPath $version) -or
    (Get-Content -LiteralPath $version -Raw) -notmatch '(?m)^mod_version=0\.8\.2-alpha\s*$') {
    throw 'Expected NPCortex 0.8.2-alpha from Recovery Fix 2. Nothing changed.'
}
$pending = @()
foreach ($entry in $manifest) {
    if ([string]$entry.path -notmatch '^[A-Za-z0-9_./-]+$' -or
        [string]$entry.path -match '\.\.') { throw 'Unsafe patch path.' }
    $relative = ([string]$entry.path).Replace('/', '\')
    $src = Join-Path $sourceRoot $relative
    $dst = Join-Path $root $relative
    if (!(Test-Path -LiteralPath $src -PathType Leaf)) { throw "Missing patch file: $relative" }
    $newHash = (Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($newHash -ne $entry.new_sha256) { throw "Patch corrupted: $relative" }
    $exists = Test-Path -LiteralPath $dst -PathType Leaf
    $oldHash = if ($exists) { (Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }
    if ($oldHash -eq $entry.new_sha256) { continue }
    if (($null -eq $entry.old_sha256 -and $exists) -or
        ($null -ne $entry.old_sha256 -and $oldHash -ne $entry.old_sha256)) {
        throw "Unexpected local source: $relative. Nothing changed. Requires exact Recovery Fix 2 base."
    }
    $pending += [pscustomobject]@{Rel=$relative;Src=$src;Dst=$dst;Exists=$exists;NewHash=$newHash}
}
if ($pending.Count -eq 0) { Write-Host 'Already applied.' -ForegroundColor Green; return }
$backup = Join-Path $root ('.npcortex-backups\v082-vanilla-trees-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$applied = @()
try {
    foreach ($file in $pending) {
        if ($file.Exists) {
            $save = Join-Path $backup $file.Rel
            New-Item -ItemType Directory -Path (Split-Path -Parent $save) -Force | Out-Null
            Copy-Item -LiteralPath $file.Dst -Destination $save -Force
        }
        New-Item -ItemType Directory -Path (Split-Path -Parent $file.Dst) -Force | Out-Null
        $applied += $file
        Copy-Item -LiteralPath $file.Src -Destination $file.Dst -Force
        $installed = (Get-FileHash -LiteralPath $file.Dst -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($installed -ne $file.NewHash) { throw "Integrity mismatch: $($file.Rel)" }
    }
} catch {
    foreach ($file in $applied) {
        if ($file.Exists) { Copy-Item -LiteralPath (Join-Path $backup $file.Rel) -Destination $file.Dst -Force }
        elseif (Test-Path -LiteralPath $file.Dst) { Remove-Item -LiteralPath $file.Dst -Force }
    }
    throw
}
Write-Host "Installed Vanilla Trees Foundation 1 ($($pending.Count) files). Backup: $backup" -ForegroundColor Green
Write-Host 'Run: .\scripts\test-v082.ps1 ; .\scripts\build-agentic-v085.ps1 -SkipTests'
Write-Host 'Forge Build and Minecraft in-game tests must be run separately.' -ForegroundColor Yellow
