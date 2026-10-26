param([string]$WorldName = 'New World (2)')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$world = Join-Path $repo ('forge-mod\run\saves\' + $WorldName)
if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))) { throw "World not found: $world" }
$source = Join-Path $repo 'integrations\datapacks\NPCortex_M04_Acceptance'
$destination = Join-Path (Join-Path $world 'datapacks') 'NPCortex_M04_Acceptance'
if (Test-Path -LiteralPath $destination) { throw "Datapack already installed; preserve existing files: $destination" }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
Copy-Item -LiteralPath $source -Destination $destination -Recurse
Write-Host "Installed read-only M04 acceptance datapack at $destination" -ForegroundColor Green
Write-Host 'In-game: /reload ; /function npcortex_m04:menu' -ForegroundColor Cyan
