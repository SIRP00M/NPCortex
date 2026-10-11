# Install a Character Pack's PNG into the target Minecraft client instance.
# Usage: .\scripts\install-character.ps1 -Id yui
# For CurseForge specify -GameDir 'F:\CurseForge\Instances\Your Modpack'
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z][a-z0-9_-]{0,39}$')]
    [string]$Id,
    [string]$SourceFolder = '',
    [string]$GameDir = ''
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $GameDir) { $GameDir = Join-Path $repo 'forge-mod\run' }
if (-not $SourceFolder) { $SourceFolder = Join-Path (Join-Path $repo 'character-packs') $Id }
$source = (Resolve-Path $SourceFolder -ErrorAction Stop).Path
$manifest = Join-Path $source 'character.json'
$png = Join-Path $source 'skin.png'
if (-not (Test-Path $manifest -PathType Leaf)) { throw "Missing: $manifest" }
$data = Get-Content $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($data.id -cne $Id) { throw "character.json id must be '$Id'" }
$skinId = [string]$data.appearance.skin
if ($skinId -cnotmatch '^[a-z][a-z0-9_-]{0,39}$') { throw 'Invalid appearance.skin value' }
if ($data.appearance.model -cnotin @('wide', 'slim')) { throw 'appearance.model must be wide or slim' }
if (-not (Test-Path $png -PathType Leaf)) { throw "Missing: $png (64x64 Minecraft Java PNG)" }
$bytes = [System.IO.File]::ReadAllBytes($png)
if ($bytes.Length -gt 1048576 -or $bytes.Length -lt 33) { throw 'PNG must be between 33 bytes and 1MB' }
$signature = [byte[]](137,80,78,71,13,10,26,10)
for ($i=0; $i -lt 8; $i++) { if ($bytes[$i] -ne $signature[$i]) { throw 'Invalid PNG signature' } }
# IHDR width and height are unsigned big-endian 32-bit integers at offsets 16 and 20.
$width = [int]($bytes[16] * 16777216 + $bytes[17] * 65536 + $bytes[18] * 256 + $bytes[19])
$height = [int]($bytes[20] * 16777216 + $bytes[21] * 65536 + $bytes[22] * 256 + $bytes[23])
if ($width -ne 64 -or $height -ne 64) { throw "Invalid PNG dimensions: ${width}x${height}; expected 64x64" }

$packs = Join-Path $repo 'character-packs'
$destinationPack = Join-Path $packs $Id
if ([System.IO.Path]::GetFullPath($source).TrimEnd('\') -ne [System.IO.Path]::GetFullPath($destinationPack).TrimEnd('\')) {
    New-Item -ItemType Directory -Force -Path $destinationPack | Out-Null
    Copy-Item -LiteralPath $manifest -Destination (Join-Path $destinationPack 'character.json') -Force
    Copy-Item -LiteralPath $png -Destination (Join-Path $destinationPack 'skin.png') -Force
}
$skinsDir = Join-Path $GameDir 'config\npcortex\skins'
New-Item -ItemType Directory -Force -Path $skinsDir | Out-Null
Copy-Item -LiteralPath $png -Destination (Join-Path $skinsDir "$skinId.png") -Force
Write-Host "NPCortex: Installed '$Id' skin '$skinId' to $skinsDir" -ForegroundColor Green
Write-Host "Character metadata: $destinationPack\character.json"
Write-Host 'If Minecraft is already open, restart it to reload the skin PNG.'
Write-Host 'To update existing NPC names/model from an edited pack, use /npcortex sync near the NPC.'
