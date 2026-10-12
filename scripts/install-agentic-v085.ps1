# Safe incremental overlay installer for NPCortex 0.8.4 -> 0.8.5.
# Example: .\scripts\install-agentic-v085.ps1 -UpdateZip 'C:\Downloads\NPCortex_v0.8.5-alpha_UpdateOnly.zip'
param(
    [Parameter(Mandatory = $true)][string]$UpdateZip,
    [string]$ProjectRoot = 'F:\AI\NPCortex'
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$archive = (Resolve-Path -LiteralPath $UpdateZip).Path
if (-not (Test-Path -LiteralPath $ProjectRoot -PathType Container)) {
    throw "ProjectRoot not found: $ProjectRoot (installer will NOT create a new repository)."
}
$root = (Resolve-Path -LiteralPath $ProjectRoot).Path.TrimEnd('\', '/')
if (-not (Test-Path (Join-Path $root 'forge-mod/build.gradle')) -or
    -not (Test-Path (Join-Path $root 'backend/src/npcortex_backend/service.py'))) {
    throw 'ProjectRoot is not an existing NPCortex repository; refusing installation.'
}
$gradleProps = Get-Content -LiteralPath (Join-Path $root 'forge-mod/gradle.properties') -Raw
if ($gradleProps -notmatch '(?m)^mod_version=0\.8\.4-alpha\s*$') {
    throw 'This update requires the NPCortex 0.8.4-alpha source. Refusing to patch an unexpected version.'
}
$backup = Join-Path $root ('backups\pre-v085-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$reader = [System.IO.Compression.ZipFile]::OpenRead($archive)
try {
    $entries = @($reader.Entries | Where-Object { $_.Name -ne '' })
    if ($entries.Count -lt 8 -or $entries.Count -gt 50) { throw 'Unexpected number of update files.' }
    foreach ($entry in $entries) {
        $name = $entry.FullName.Replace('\', '/')
        if ($name.StartsWith('/') -or $name.Contains('..') -or $name.Contains(':') -or
            $name -notmatch '^(backend/|forge-mod/|docs/|scripts/|README\.md$|UPDATE_NOTES\.md$)') {
            throw "Blocked unsafe/unexpected update entry: $name"
        }
    }
    foreach ($entry in $entries) {
        $rel = $entry.FullName.Replace('\', '/')
        $dest = Join-Path $root ($rel.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
        $dir = Split-Path -Parent $dest
        New-Item -Type Directory -Path $dir -Force | Out-Null
        if (Test-Path -LiteralPath $dest) {
            $save = Join-Path $backup ($rel.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
            New-Item -Type Directory -Path (Split-Path -Parent $save) -Force | Out-Null
            Copy-Item -LiteralPath $dest -Destination $save -Force
        }
        $stream = $entry.Open()
        try {
            $file = [System.IO.File]::Create($dest)
            try { $stream.CopyTo($file) } finally { $file.Dispose() }
        } finally { $stream.Dispose() }
        Write-Host "Updated: $rel"
    }
} finally { $reader.Dispose() }
Write-Host "Update applied. Previous versions of replaced files: $backup" -ForegroundColor Green
Write-Host 'Saved worlds, SQLite data, .env, and custom character packs are untouched.'
Write-Host 'Now run .\scripts\test-agentic-v085.ps1 and then .\scripts\build-agentic-v085.ps1.'
