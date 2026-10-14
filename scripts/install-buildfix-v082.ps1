# Apply Build Hotfix 1 on public NPCortex v0.8.2-alpha without touching private game data.
# Unzip UpdateOnly and run this script from the extracted folder.
param(
    [string]$ProjectRoot = 'F:\AI\NPCortex'
)
$ErrorActionPreference = 'Stop'
$patchRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..') -ErrorAction Stop).Path
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$projectMeta = Join-Path $root 'forge-mod/gradle.properties'
if (-not (Test-Path -LiteralPath $projectMeta -PathType Leaf)) { throw 'NPCortex project root not found.' }
if ((Get-Content -LiteralPath $projectMeta -Raw) -notmatch 'mod_version=0\.8\.2-alpha') {
    throw 'Build Hotfix 1 requires public v0.8.2-alpha. No files changed.'
}
$javaRel = 'forge-mod/src/main/java/io/github/sirp00m/npcortex/AgenticTaskController.java'
$expectedBad = 'cd522cc69f6f94f0c80ee6420e7f1c4d2af89fb7bd1a0499b849708e3ea8d438'
$expectedFixed = 'e225319be34604a8941f1c34178efa62c075888f206e46b71b18313a213030d7'
$javaTarget = Join-Path $root $javaRel
if (-not (Test-Path -LiteralPath $javaTarget -PathType Leaf)) { throw 'AgenticTaskController.java not found.' }
$currentHash = (Get-FileHash -LiteralPath $javaTarget -Algorithm SHA256).Hash.ToLowerInvariant()
if ($currentHash -ne $expectedBad -and $currentHash -ne $expectedFixed) {
    throw 'AgenticTaskController.java differs from the supplied v0.8.2 ZIP. Refusing to overwrite local edits. Manually remove the extra closing brace before clear_leaf around line 263.'
}
$paths = @(
    $javaRel,
    'scripts/check-java-syntax-v082.ps1',
    'scripts/test-v082.ps1',
    'tests/java/JavaSyntaxSmokeCheck.java',
    'docs/releases/v0.8.2-alpha-buildfix-1.md'
)
foreach ($rel in $paths) {
    if (-not (Test-Path -LiteralPath (Join-Path $patchRoot $rel) -PathType Leaf)) {
        throw "Missing patch file $rel. No files changed."
    }
}
$backup = Join-Path $root ('.npcortex-backups/v0.8.2-buildfix1-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$applied = New-Object System.Collections.Generic.List[object]
try {
    foreach ($rel in $paths) {
        $dest = Join-Path $root $rel
        $exists = Test-Path -LiteralPath $dest -PathType Leaf
        if ($exists) {
            $backupDest = Join-Path $backup $rel
            New-Item -ItemType Directory -Path (Split-Path $backupDest -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $dest -Destination $backupDest -Force
        }
        $applied.Add([pscustomobject]@{Relative=$rel; Existed=$exists})
        New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $patchRoot $rel) -Destination $dest -Force
        Write-Host "Applied $rel"
    }
    $installed = (Get-FileHash -LiteralPath $javaTarget -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($installed -ne $expectedFixed) { throw 'Java hotfix hash mismatch after copying.' }
    Write-Host "Build hotfix installed; previous files backed up at $backup" -ForegroundColor Green
    Write-Host 'Next: .\scripts\test-v082.ps1  then  .\scripts\build-agentic-v085.ps1 -SkipTests'
    Write-Host 'Java parser passing does not mean Forge Build passed.' -ForegroundColor Yellow
} catch {
    Write-Warning ('Install failed, restoring backups: ' + $_.Exception.Message)
    for ($i = $applied.Count - 1; $i -ge 0; $i--) {
        $entry = $applied[$i]
        $dest = Join-Path $root $entry.Relative
        if ($entry.Existed) { Copy-Item -LiteralPath (Join-Path $backup $entry.Relative) -Destination $dest -Force }
        elseif (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Force }
    }
    throw
}
