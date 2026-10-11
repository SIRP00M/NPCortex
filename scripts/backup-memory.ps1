# Safe SQLite online backup; works while NPCortex backend is active.
param([string]$Destination = "")
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourceDir = if ($env:NPCORTEX_DATA_DIR) { $env:NPCORTEX_DATA_DIR } else { Join-Path $root 'backend\data' }
$source = Join-Path $sourceDir 'npcortex.sqlite3'
if (-not (Test-Path $source)) { throw "Memory database not found: $source" }
if (-not $Destination) {
    $backupDir = Join-Path $root 'backend\data\backups'
    New-Item -ItemType Directory -Force $backupDir | Out-Null
    $Destination = Join-Path $backupDir ("npcortex-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.sqlite3')
}
$target = [System.IO.Path]::GetFullPath($Destination)
@'
import sqlite3, sys
source, target = sys.argv[1:3]
with sqlite3.connect(source) as src, sqlite3.connect(target) as dst:
    src.backup(dst)
print(f"NPCortex memory backup written to: {target}")
'@ | python - $source $target
if ($LASTEXITCODE -ne 0) { throw 'SQLite backup failed.' }
