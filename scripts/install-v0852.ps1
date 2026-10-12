# v0.8.5.2-alpha Backend-only response-validation hotfix.
# Apply only to an existing NPCortex v0.8.5.1-alpha checkout.
param(
    [Parameter(Mandatory = $true)][string]$UpdateZip,
    [string]$ProjectRoot = 'F:\AI\NPCortex'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Existing NPCortex repository not found: $ProjectRoot"
}
$root = (Resolve-Path -LiteralPath $ProjectRoot).Path.TrimEnd('\','/')
$props = Join-Path $root 'forge-mod/gradle.properties'
$brain = Join-Path $root 'backend/src/npcortex_backend/brain.py'
if (-not (Test-Path -LiteralPath $props) -or -not (Test-Path -LiteralPath $brain)) {
    throw 'Repository sanity check failed: missing NPCortex source files.'
}
if ((Get-Content -LiteralPath $props -Raw) -notmatch '(?m)^mod_version=0\.8\.5\.1-alpha\s*$') {
    throw 'This hotfix requires the v0.8.5.1-alpha Forge/source checkout; no files changed.'
}
if ((Get-Content -LiteralPath $brain -Raw) -notmatch 'Tool does not accept target') {
    throw 'Expected original v0.8.5.1 tool validator not found; refusing to overwrite a different/updated brain.py.'
}
$allowed = @(
    'backend/src/npcortex_backend/brain.py',
    'backend/tests/test_agentic_brain_v085.py',
    'docs/agentic-brain-v0.8.5.2.md',
    'scripts/install-v0852.ps1'
)
$archive = (Resolve-Path -LiteralPath $UpdateZip).Path
$reader = [System.IO.Compression.ZipFile]::OpenRead($archive)
try {
    $entries = @($reader.Entries | Where-Object { $_.Name -ne '' })
    if ($entries.Count -ne $allowed.Count) { throw 'Unexpected update ZIP file count.' }
    $seen = @{}
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\','/')
        if ($rel -cnotin $allowed -or $entry.FullName.Contains('..') -or $seen.ContainsKey($rel) -or $entry.Length -gt 200000) {
            throw "Unknown, duplicate or unsafe update file: $rel"
        }
        $seen[$rel] = $true
    }
    foreach ($rel in $allowed) {
        if (-not $seen.ContainsKey($rel)) { throw "Missing patch file: $rel" }
    }
    $backup = Join-Path $root ('backups\pre-v0852-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -Path $backup -ItemType Directory -Force | Out-Null
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\','/')
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
Write-Host 'Installed v0.8.5.2-alpha Backend-only hotfix.' -ForegroundColor Green
Write-Host 'No Forge JAR rebuild, SQLite, world saves, .env or character packs affected.'
Write-Host 'Next: .\scripts\test-agentic-v085.ps1 ; restart .\scripts\dev-backend.ps1'
