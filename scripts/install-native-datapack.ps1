# Installs the native break + pickup acceptance data pack into ONE test world. The pack builds and clears blocks around the player, so this
# script refuses a world whose folder name does not contain "Native" unless -AllowAnyName is given. It copies files only: it never reads or
# writes level.dat, region files, a database or a journal. An existing install is never overwritten.
#   scripts\install-native-datapack.ps1 -WorldName NPCortex_Native_Test
#   scripts\install-native-datapack.ps1 -WorldName NPCortex_Native_Test -Remove
param(
    [Parameter(Mandatory)][string]$WorldName,
    [string]$SavesDir,
    [switch]$AllowAnyName,
    [switch]$Remove
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $SavesDir) { $SavesDir = Join-Path $repo 'forge-mod\run\saves' }
if ($WorldName -notmatch '^[^\\/:*?"<>|]{1,64}$') { throw 'Bad world name.' }
if (-not $AllowAnyName -and $WorldName -notmatch 'Native') { throw 'The world name must contain "Native" (this pack clears blocks). Pass -AllowAnyName only for a throw-away world.' }
$world = Join-Path $SavesDir $WorldName
if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))) { throw "World not found (create it in-game first): $world" }
$destination = Join-Path (Join-Path $world 'datapacks') 'NPCortex_Native_Acceptance'
if ($Remove) {
    if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Recurse -Force; Write-Host "Removed $destination" } else { Write-Host 'Not installed.' }
    exit 0
}
if (Test-Path -LiteralPath $destination) { throw "Data pack already installed; preserve existing files: $destination" }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'integrations\datapacks\NPCortex_Native_Acceptance') -Destination $destination -Recurse
Write-Host "Installed the native acceptance data pack at $destination" -ForegroundColor Green
Write-Host 'In-game, standing where the arena may be built: /reload ; /function npcortex_native:menu' -ForegroundColor Cyan
