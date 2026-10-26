param([string]$WorldName = 'New World (2)')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$world = Join-Path $repo ('forge-mod\run\saves\' + $WorldName)
if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))) { throw "World not found: $world" }
$destination = Join-Path (Join-Path $world 'datapacks') 'NPCortex_M04_Acceptance'
if (-not (Test-Path -LiteralPath $destination)) { Write-Host 'No M04 acceptance datapack installed'; exit 0 }
if (-not (Test-Path -LiteralPath (Join-Path $destination 'pack.mcmeta'))) { throw 'Unexpected datapack folder; refusing deletion' }
Remove-Item -LiteralPath $destination -Recurse -Force
Write-Host "Removed ONLY the temporary M04 acceptance datapack; world/SQLite/evidence untouched" -ForegroundColor Yellow
