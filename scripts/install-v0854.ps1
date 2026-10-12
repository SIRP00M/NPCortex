# NPCortex v0.8.5.4-alpha server no-progress watchdog patch.
# Apply ONLY to the v0.8.5.3-alpha checkout. BACK UP YOUR WORLD FIRST.
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
$controller = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/AgenticTaskController.java'
if (-not (Test-Path -LiteralPath $props) -or -not (Test-Path -LiteralPath $brain) -or
    -not (Test-Path -LiteralPath $controller)) { throw 'Existing NPCortex checkout not found; no files changed.' }
if ((Get-Content -LiteralPath $props -Raw) -notmatch '(?m)^mod_version=0\.8\.5\.1-alpha\s*$') {
    throw 'Expected Forge source at v0.8.5.1-alpha (backend patched through v0.8.5.3). No files changed.'
}
if ((Get-Content -LiteralPath $brain -Raw) -notmatch 'def schema_for_observation') {
    throw 'Expected v0.8.5.3 backend with observation-scoped schema. No files changed.'
}
if ((Get-Content -LiteralPath $brain -Raw) -match 'MAX_STALLED = 5' -or
    (Get-Content -LiteralPath $controller -Raw) -match 'nextStalledSteps') {
    throw 'v0.8.5.4 patch appears installed already. No files changed.'
}
$allowed = @(
    'forge-mod/gradle.properties',
    'forge-mod/src/main/resources/META-INF/mods.toml',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/AgenticTaskPolicy.java',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/AgenticTaskController.java',
    'forge-mod/src/main/java/io/github/sirp00m/npcortex/BrainSkills.java',
    'forge-mod/src/test/java/io/github/sirp00m/npcortex/AgenticTaskPolicySelfTest.java',
    'backend/src/npcortex_backend/brain.py',
    'backend/tests/test_agentic_brain_v085.py',
    'scripts/diagnose-brain-v0853.ps1',
    'scripts/diagnose-brain-v0854.ps1',
    'scripts/install-v0854.ps1',
    'docs/agentic-brain-v0.8.5.4.md',
    'UPDATE_NOTES.md'
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
            $seen.ContainsKey($rel) -or $entry.Length -gt 250000) {
            throw "Unexpected, duplicate or unsafe patch file: $rel"
        }
        $seen[$rel] = $true
    }
    foreach ($rel in $allowed) {
        if (-not $seen.ContainsKey($rel)) { throw "Missing patch file: $rel" }
    }
    $backup = Join-Path $root ('backups\pre-v0854-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    # Stage copies + backups before replacing any existing project file.
    $staging = Join-Path $backup '_staging'
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\','/')
        $path = $rel.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        $dest = Join-Path $root $path
        if (Test-Path -LiteralPath $dest) {
            $copy = Join-Path $backup $path
            New-Item (Split-Path -Parent $copy) -Type Directory -Force | Out-Null
            Copy-Item -LiteralPath $dest -Destination $copy -Force
        }
        $staged = Join-Path $staging $path
        New-Item (Split-Path -Parent $staged) -Type Directory -Force | Out-Null
        $inputStream = $entry.Open()
        try {
            $outputStream = [System.IO.File]::Create($staged)
            try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
        } finally { $inputStream.Dispose() }
    }
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\','/')
        $path = $rel.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
        $dest = Join-Path $root $path
        New-Item (Split-Path -Parent $dest) -Type Directory -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $staging $path) -Destination $dest -Force
        Write-Host "Patched $rel"
    }
    Remove-Item -LiteralPath $staging -Recurse -Force
} finally { $reader.Dispose() }
Write-Host 'NPCortex v0.8.5.4-alpha installed; build Forge because Java changed.' -ForegroundColor Green
Write-Host "Backups stored in $backup"
Write-Host 'Close game/backend before install. No Minecraft saves, player data, memory DB or character packs changed.'
Write-Host 'Then: .\scripts\test-agentic-v085.ps1 ; .\scripts\build-agentic-v085.ps1'
