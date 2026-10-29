# Writes the Owner's allow-list for PLACE and USE (and break/pickup) - the same file the native break/pickup uses.
#   scripts\pap\install-pap-allowlist.ps1 -ConfigDir <game>\config -PlayerUuid <your player UUID> -Actors Reika -Anchor 100,64,200
#   scripts\pap\install-pap-allowlist.ps1 -ConfigDir ... -Remove
# Nothing is allowed without this file. A place needs the placed cell's WHOLE 3x3x3 neighbourhood inside the region; a use needs the
# 5x5x5 neighbourhood (redstone reaches two blocks). Independent of this file, the code refuses TNT, fire, fluids, gravity blocks,
# portals, command blocks, containers' automation and every use except a lever or a button. An existing file is replaced only with
# -Force (a .bak copy is kept). This script never reads or writes a world, a database or a journal.
param(
    [Parameter(Mandatory)][string]$ConfigDir,
    [string]$PlayerUuid,
    [string[]]$Actors,
    [string]$Dimension = 'minecraft:overworld',
    [string[]]$Blocks = @('minecraft:cobblestone', 'minecraft:lever', 'minecraft:stone_button', 'minecraft:oak_log'),
    [string[]]$Items = @('minecraft:oak_log'),
    [string]$Anchor,
    [switch]$Force,
    [switch]$Remove
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ConfigDir -PathType Container)) { throw "ConfigDir does not exist: $ConfigDir" }
$target = Join-Path $ConfigDir 'npcortex-native-actions.json'
if ($Remove) {
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target; Write-Host "Removed $target - every native and place/use action is denied." }
    else { Write-Host 'No allow-list file present - every native and place/use action is denied.' }
    exit 0
}
if (-not $PlayerUuid -or -not $Actors -or -not $Anchor) { throw 'Give -PlayerUuid, -Actors and -Anchor x,y,z (the arena marker from /function npcortex_pap:info). There is no default region.' }
$uuid = [guid]::Empty
if (-not [guid]::TryParse($PlayerUuid, [ref]$uuid) -or $uuid -eq [guid]::Empty) { throw 'PlayerUuid is not a valid non-nil UUID.' }
foreach ($a in $Actors) { if ($a -ne '*' -and $a -notmatch '^[A-Za-z0-9_]{1,16}$') { throw "Bad actor name: $a" } }
foreach ($id in @($Blocks) + @($Items)) { if ($id -notmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$') { throw "Bad resource id: $id" } }
$p = $Anchor.Split(',') | ForEach-Object { [int]$_.Trim() }
if ($p.Count -ne 3) { throw '-Anchor needs x,y,z' }
$min = @(($p[0] - 2), ($p[1] - 2), ($p[2] - 2)); $max = @(($p[0] + 14), ($p[1] + 8), ($p[2] + 14))
$entry = [ordered]@{ id = 'owner-pap'; requester = $uuid.ToString('D').ToLowerInvariant();
                     actions = @('break_block', 'pickup_item', 'place_block', 'use_block'); dimensions = @($Dimension); actors = @($Actors);
                     blocks = @($Blocks); items = @($Items); regions = @([ordered]@{ min = @($min); max = @($max) }) }
$json = ([ordered]@{ schema_version = 1; entries = @($entry) } | ConvertTo-Json -Depth 8)
if (Test-Path -LiteralPath $target) {
    if (-not $Force) { throw "$target exists; pass -Force to replace it (a .bak copy is kept)." }
    Copy-Item -LiteralPath $target -Destination ($target + '.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
[IO.File]::WriteAllText($target, $json + "`n", (New-Object Text.UTF8Encoding $false))
Write-Host "Wrote $target"
Write-Host $json
Write-Host 'Place consumes one item from the NPC hotbar; use toggles a lever or button; break removes a block; pickup moves a drop into the NPC inventory. Use a throw-away test world.'
