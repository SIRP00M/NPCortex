# NPCortex v0.8.5.1-alpha: targeted update on top of v0.8.5-alpha only.
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
if (-not (Test-Path $props) -or -not (Test-Path (Join-Path $root 'backend/src/npcortex_backend/brain.py'))) {
    throw 'Repository sanity check failed: no NPCortex v0.8.5 layout.'
}
if ((Get-Content -LiteralPath $props -Raw) -notmatch '(?m)^mod_version=0\.8\.5-alpha\s*$') {
    throw 'Patch only applies to v0.8.5-alpha; no files changed.'
}
$allowed = @(
    'forge-mod/gradle.properties',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/BrainSkills.java',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/WorldActions.java',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/BrainObservation.java',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/AgenticTaskController.java',
    'backend/src/npcortex_backend/brain.py',
    'backend/tests/test_agentic_brain_v085.py',
    'docs/agentic-brain-v0.8.5.1.md',
    'scripts/install-v0851.ps1'
)
$archive = (Resolve-Path -LiteralPath $UpdateZip).Path
$reader = [System.IO.Compression.ZipFile]::OpenRead($archive)
try {
    $entries = @($reader.Entries | Where-Object { $_.Name -ne '' })
    if ($entries.Count -ne $allowed.Count) { throw 'Unexpected update ZIP file count.' }
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\','/')
        if ($rel -notin $allowed -or $entry.FullName.Contains('..')) {
            throw "Unknown or unsafe update file: $rel"
        }
    }
    $backup = Join-Path $root ('backups\pre-v0851-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -Path $backup -ItemType Directory -Force | Out-Null
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\','/')
        $dest = Join-Path $root ($rel.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
        New-Item -Path (Split-Path -Parent $dest) -ItemType Directory -Force | Out-Null
        if (Test-Path -LiteralPath $dest) {
            $copy = Join-Path $backup ($rel.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
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
Write-Host 'Installed v0.8.5.1-alpha source patch. Backups saved.' -ForegroundColor Green
Write-Host 'No Minecraft world, SQLite, .env, or character pack files are modified.'
Write-Host 'Next: .\scripts\test-agentic-v085.ps1 ; .\scripts\build-agentic-v085.ps1'
