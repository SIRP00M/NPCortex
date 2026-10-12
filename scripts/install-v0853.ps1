# NPCortex v0.8.5.3-alpha backend-only patch. Requires v0.8.5.2-alpha.
param(
    [Parameter(Mandatory = $true)][string]$UpdateZip,
    [string]$ProjectRoot = 'F:\AI\NPCortex'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "NPCortex project not found: $ProjectRoot"
}
$root = (Resolve-Path -LiteralPath $ProjectRoot).Path.TrimEnd('\','/')
$props = Join-Path $root 'forge-mod/gradle.properties'
$brain = Join-Path $root 'backend/src/npcortex_backend/brain.py'
if (-not (Test-Path -LiteralPath $props) -or -not (Test-Path -LiteralPath $brain)) {
    throw 'Not a valid existing NPCortex repository.'
}
if ((Get-Content -LiteralPath $props -Raw) -notmatch '(?m)^mod_version=0\.8\.5\.1-alpha\s*$') {
    throw 'Expected the v0.8.5.1-alpha Forge checkout used by v0.8.5.2. Nothing changed.'
}
$currentBrain = Get-Content -LiteralPath $brain -Raw
if ($currentBrain -notmatch 'Canonicalize this \*unused\* argument') {
    throw 'v0.8.5.2 backend hotfix not found. Install v0.8.5.2 first. Nothing changed.'
}
if ($currentBrain -match 'def schema_for_observation') {
    throw 'This patch appears already installed. Nothing changed.'
}
$allowed = @(
    'backend/src/npcortex_backend/brain.py',
    'backend/src/npcortex_backend/service.py',
    'backend/src/npcortex_backend/server.py',
    'backend/tests/test_agentic_brain_v085.py',
    'docs/agentic-brain-v0.8.5.3.md',
    'scripts/install-v0853.ps1',
    'scripts/diagnose-brain-v0853.ps1'
)
$archive = (Resolve-Path -LiteralPath $UpdateZip).Path
$reader = [System.IO.Compression.ZipFile]::OpenRead($archive)
try {
    $entries = @($reader.Entries | Where-Object { $_.Name -ne '' })
    if ($entries.Count -ne $allowed.Count) { throw 'Unexpected ZIP contents: file count mismatch.' }
    $seen = @{}
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\', '/')
        if ($rel -cnotin $allowed -or $entry.FullName.Contains('..') -or
            $seen.ContainsKey($rel) -or $entry.Length -gt 200000) {
            throw "Unexpected, duplicate or unsafe patch path: $rel"
        }
        $seen[$rel] = $true
    }
    foreach ($rel in $allowed) {
        if (-not $seen.ContainsKey($rel)) { throw "Missing required patch file: $rel" }
    }
    $backup = Join-Path $root ('backups\pre-v0853-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\', '/')
        $relativePath = $rel.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        $dest = Join-Path $root $relativePath
        New-Item -Path (Split-Path -Parent $dest) -ItemType Directory -Force | Out-Null
        if (Test-Path -LiteralPath $dest) {
            $copy = Join-Path $backup $relativePath
            New-Item -Path (Split-Path -Parent $copy) -ItemType Directory -Force | Out-Null
            Copy-Item -LiteralPath $dest -Destination $copy -Force
        }
        $from = $entry.Open()
        try {
            $to = [System.IO.File]::Create($dest)
            try { $from.CopyTo($to) } finally { $to.Dispose() }
        } finally { $from.Dispose() }
        Write-Host "Patched $rel"
    }
} finally { $reader.Dispose() }
Write-Host 'Installed v0.8.5.3-alpha Python Backend patch.' -ForegroundColor Green
Write-Host 'No Forge/JAR build required. Saves, SQLite, Character Packs and .env untouched.'
Write-Host 'Run: .\scripts\test-agentic-v085.ps1 ; .\scripts\dev-backend.ps1'
