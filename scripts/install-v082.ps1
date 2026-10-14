# NPCortex public v0.8.2-alpha incremental installer.
# Use with UpdateOnly ZIP; preserves all unrelated files (incl. saves, .env, SQLite).
param(
    [Parameter(Mandatory=$true)][string]$UpdateZip,
    [string]$ProjectRoot = 'F:\AI\NPCortex'
)
$ErrorActionPreference = 'Stop'
$zip = (Resolve-Path -LiteralPath $UpdateZip -ErrorAction Stop).Path
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
if (-not (Test-Path -LiteralPath (Join-Path $root 'forge-mod/gradle.properties'))) {
    throw "Not an NPCortex source checkout: $root"
}
$original = Get-Content -LiteralPath (Join-Path $root 'forge-mod/gradle.properties') -Raw
if ($original -notmatch 'mod_id=npcortex') { throw 'Project root is not NPCortex.' }
if ($original -notmatch 'mod_version=0\.8\.1-alpha|mod_version=0\.8\.2-alpha|mod_version=0\.8\.5\.5-alpha') {
    throw 'Installer expects public v0.8.1-alpha or its known v0.8.5.5 development snapshot. Inspect source before updating.'
}
$staging = Join-Path ([IO.Path]::GetTempPath()) ('npcortex-v082-' + [guid]::NewGuid().ToString('N'))
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$backup = Join-Path $root ('.npcortex-backups/v0.8.2-' + $stamp)
New-Item -ItemType Directory -Path $staging -Force | Out-Null
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$applied = New-Object System.Collections.Generic.List[object]
try {
    Expand-Archive -LiteralPath $zip -DestinationPath $staging -Force
    $sourceFiles = @(Get-ChildItem -LiteralPath $staging -Recurse -File)
    if ($sourceFiles.Count -lt 5 -or -not (Test-Path (Join-Path $staging 'UPDATE_NOTES.md'))) {
        throw 'ZIP does not look like an NPCortex v0.8.2 Update Only package.'
    }
    foreach ($file in $sourceFiles) {
        $rel = $file.FullName.Substring($staging.Length).TrimStart('\','/')
        $rel = $rel -replace '\\', '/'
        # This package must never overwrite private configuration or saves.
        if ($rel -match '(^|/)(\.env|.*\.db|.*\.sqlite|saves|world|config)(/|$)' -or
            $rel -match '(^|/)\.git(/|$)' -or
            $rel -match '(^|/)character-packs(/|$)') {
            throw "Refusing to overwrite private data in update: $rel"
        }
        $target = [IO.Path]::GetFullPath((Join-Path $root $rel))
        $rootWithSeparator = $root.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar
        if (-not $target.StartsWith($rootWithSeparator, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Update contains an unsafe path: $rel"
        }
        $existing = Test-Path -LiteralPath $target -PathType Leaf
        if ($existing) {
            $oldCopy = Join-Path $backup $rel
            New-Item -ItemType Directory -Path (Split-Path $oldCopy -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $target -Destination $oldCopy -Force
        }
        $applied.Add([pscustomobject]@{ Relative=$rel; Existed=$existing })
        New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force | Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        Write-Host "Applied: $rel"
    }
    Write-Host "NPCortex v0.8.2-alpha source updated. Backup: $backup" -ForegroundColor Green
    Write-Host 'Run: .\scripts\test-v082.ps1  then  .\scripts\build-agentic-v085.ps1 -SkipTests'
    Write-Host 'Full Forge build and Minecraft in-game test must still be run on your PC.' -ForegroundColor Yellow
} catch {
    Write-Warning ('Install failed: ' + $_.Exception.Message + '. Restoring files that were changed...')
    for ($index = $applied.Count - 1; $index -ge 0; $index--) {
        $entry = $applied[$index]
        $dest = Join-Path $root $entry.Relative
        if ($entry.Existed) {
            Copy-Item -LiteralPath (Join-Path $backup $entry.Relative) -Destination $dest -Force
        } elseif (Test-Path -LiteralPath $dest) {
            Remove-Item -LiteralPath $dest -Force
        }
    }
    throw
} finally {
    Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
}
