# Apply Build Fix 2 after Build Fix 1 (or on an exactly matching v0.8.2 project).
# Extract the Update Only ZIP, then run this script from the extracted folder.
param([string]$ProjectRoot = 'F:\AI\NPCortex')
$ErrorActionPreference = 'Stop'
$patchRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..') -ErrorAction Stop).Path
$root = (Resolve-Path -LiteralPath $ProjectRoot -ErrorAction Stop).Path
$versionFile = Join-Path $root 'forge-mod/gradle.properties'
if (-not (Test-Path -LiteralPath $versionFile -PathType Leaf)) { throw 'NPCortex project root not found.' }
if ((Get-Content -LiteralPath $versionFile -Raw) -notmatch '(?m)^mod_version=0\.8\.2-alpha\s*$') {
    throw 'Build Fix 2 requires public v0.8.2-alpha. No files changed.'
}
$javaRel = 'forge-mod/src/main/java/io/github/sirp00m/npcortex/GatherTaskController.java'
$javaPath = Join-Path $root $javaRel
if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) { throw 'GatherTaskController.java not found.' }
$originalHash = '5397d153f9284860e6178a6e2cdc167e6d54257120e804b60b2d0b378df617fc'
$fixedHash = 'ee63e31ecf648d4c76868e78c4eb900963104e7c5fbff5a87224726e967152e5'
$actualHash = (Get-FileHash -LiteralPath $javaPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $originalHash -and $actualHash -ne $fixedHash) {
    throw 'GatherTaskController.java contains local changes or an unexpected version; refusing to overwrite.'
}
$files = @(
  $javaRel,
  'backend/tests/test_java_leaf_api_v082.py',
  'docs/releases/v0.8.2-alpha-buildfix-2.md'
)
foreach ($rel in $files) {
    if (-not (Test-Path -LiteralPath (Join-Path $patchRoot $rel) -PathType Leaf)) {
        throw "Missing patch file: $rel. No changes applied."
    }
}
if ((Get-FileHash -LiteralPath (Join-Path $patchRoot $javaRel) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $fixedHash) {
    throw 'Patch contents failed SHA256 verification. No changes applied.'
}
$backup = Join-Path $root ('.npcortex-backups/v0.8.2-buildfix2-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$applied = New-Object System.Collections.Generic.List[object]
try {
    foreach ($rel in $files) {
        $dest = Join-Path $root $rel
        $exists = Test-Path -LiteralPath $dest -PathType Leaf
        if ($exists) {
            $saved = Join-Path $backup $rel
            New-Item -ItemType Directory -Path (Split-Path $saved -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $dest -Destination $saved -Force
        }
        $applied.Add([pscustomobject]@{Relative=$rel;Existed=$exists})
        New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $patchRoot $rel) -Destination $dest -Force
        Write-Host "Applied $rel"
    }
    $installedHash = (Get-FileHash -LiteralPath $javaPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($installedHash -ne $fixedHash) { throw 'Installed Java file did not match expected SHA256.' }
    Write-Host 'NPCortex v0.8.2-alpha Build Fix 2 installed.' -ForegroundColor Green
    Write-Host "Backups: $backup"
    Write-Host 'Next: .\scripts\test-v082.ps1; then .\scripts\build-agentic-v085.ps1 -SkipTests'
} catch {
    Write-Warning ('Install failed; restoring backups: ' + $_.Exception.Message)
    for ($i = $applied.Count - 1; $i -ge 0; $i--) {
        $entry = $applied[$i]
        $dest = Join-Path $root $entry.Relative
        if ($entry.Existed) { Copy-Item -LiteralPath (Join-Path $backup $entry.Relative) -Destination $dest -Force }
        elseif (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Force }
    }
    throw
}
