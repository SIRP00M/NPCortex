# v0.8.5.5 incremental patch, apply to v0.8.5.4-alpha source only.
param(
    [Parameter(Mandatory=$true)][string]$UpdateZip,
    [string]$ProjectRoot = 'F:\AI\NPCortex'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "Project missing: $ProjectRoot"
}
$root = (Resolve-Path -LiteralPath $ProjectRoot).Path.TrimEnd('\','/')
$props = Join-Path $root 'forge-mod/gradle.properties'
$brain = Join-Path $root 'backend/src/npcortex_backend/brain.py'
$controller = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/AgenticTaskController.java'
foreach ($source in @($props, $brain, $controller)) {
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw 'Original NPCortex v0.8.5.4-alpha source not found; no changes made.'
    }
}
if ((Get-Content -LiteralPath $props -Raw) -notmatch '(?m)^mod_version=0\.8\.5\.4-alpha\s*$') {
    throw 'Expected v0.8.5.4-alpha sources. No files changed.'
}
if ((Get-Content -LiteralPath $brain -Raw) -match 'def available_tools\(') {
    throw 'Affordance patch is already installed; no changes made.'
}
$allowed = @(
    'forge-mod/gradle.properties',
    'forge-mod/src/main/resources/META-INF/mods.toml',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/AgenticTaskPolicy.java',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/AgenticTaskController.java',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/BrainSkills.java',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/WorldActions.java',
    'forge-mod/src/test/java/io/github/sirp00m/npcortex/AgenticTaskPolicySelfTest.java',
    'backend/src/npcortex_backend/brain.py',
    'backend/tests/test_agentic_brain_v085.py',
    'scripts/install-v0855.ps1',
    'docs/agentic-brain-v0.8.5.5.md',
    'UPDATE_NOTES.md'
)
$zip = (Resolve-Path -LiteralPath $UpdateZip).Path
$archive = [System.IO.Compression.ZipFile]::OpenRead($zip)
try {
    $entries = @($archive.Entries | Where-Object { $_.Name -ne '' })
    if ($entries.Count -ne $allowed.Count) { throw 'Update ZIP has unexpected file count.' }
    $seen = @{}
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\','/')
        if ($rel -cnotin $allowed -or $rel.Contains('..') -or $rel.StartsWith('/') -or
            $seen.ContainsKey($rel) -or $entry.Length -gt 250000) {
            throw "Unsafe, duplicate or unexpected update entry: $rel"
        }
        $seen[$rel] = $true
    }
    foreach ($rel in $allowed) {
        if (-not $seen.ContainsKey($rel)) { throw "Update missing file $rel" }
    }
    $backup = Join-Path $root ('backups/pre-v0855-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
    $stage = Join-Path $backup '_stage'
    New-Item -ItemType Directory -Path $stage -Force | Out-Null
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\','/')
        $name = $rel.Replace('/', [IO.Path]::DirectorySeparatorChar)
        $destination = Join-Path $root $name
        $target = Join-Path $stage $name
        New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
        if (Test-Path -LiteralPath $destination) {
            $back = Join-Path $backup $name
            New-Item -ItemType Directory -Path (Split-Path -Parent $back) -Force | Out-Null
            Copy-Item -LiteralPath $destination -Destination $back -Force
        }
        $inputStream = $entry.Open()
        try {
            $outputStream = [IO.File]::Create($target)
            try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
        } finally { $inputStream.Dispose() }
    }
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\','/')
        $name = $rel.Replace('/', [IO.Path]::DirectorySeparatorChar)
        $destination = Join-Path $root $name
        New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $stage $name) -Destination $destination -Force
        Write-Host "Updated $rel"
    }
    Remove-Item -LiteralPath $stage -Recurse -Force
    Write-Host "NPCortex v0.8.5.5-alpha applied. Backup at $backup" -ForegroundColor Green
    Write-Host 'Run tests and BUILD FORGE (Java sources changed) before game launch.'
} finally { $archive.Dispose() }
