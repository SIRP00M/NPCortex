# Writes the Owner's NATIVE allow-list (break_block / pickup_item). Nothing native is allowed without this file.
#   scripts\install-native-allowlist.ps1 -ConfigDir <game>\config -PlayerUuid <your player UUID> -Actors Reika[,Sakura] -Anchor 100,64,200
#   scripts\install-native-allowlist.ps1 -ConfigDir ... -PlayerUuid ... -Actors * -Region -10,60,-10,40,80,40 -Blocks minecraft:oak_log -Items minecraft:oak_log
#   scripts\install-native-allowlist.ps1 -ConfigDir ... -Remove        # deletes the file (everything native is denied again)
# -Anchor x,y,z is the marker printed by /function npcortex_native:info; the region is the acceptance arena box around it.
# A break needs the target's WHOLE 3x3x3 neighbourhood inside one region. The file is a SEPARATE file from the no-op allow-list
# (npcortex-world-actions.json is not touched). Re-read every second by a background poller. This script never reads or writes a
# world, a database or a journal. An existing file is only replaced with -Force, after a .bak-<timestamp> copy.
param(
    [Parameter(Mandatory)][string]$ConfigDir,
    [string]$PlayerUuid,
    [string[]]$Actors,
    [string]$Dimension = 'minecraft:overworld',
    [string[]]$Blocks = @('minecraft:oak_log', 'minecraft:gravel'),
    [string[]]$Items = @('minecraft:oak_log', 'minecraft:torch'),
    [string]$Anchor,
    [string]$Region,
    [switch]$Force,
    [switch]$Remove
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ConfigDir -PathType Container)) { throw "ConfigDir does not exist: $ConfigDir" }
$target = Join-Path $ConfigDir 'npcortex-native-actions.json'
if ($Remove) {
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target; Write-Host "Removed $target - all native actions are denied." }
    else { Write-Host 'No native allow-list file present - all native actions are denied.' }
    exit 0
}
if (-not $PlayerUuid -or -not $Actors) { throw 'Give -PlayerUuid and -Actors (use "*" to allow every NPC, deliberately).' }
$uuid = [guid]::Empty
if (-not [guid]::TryParse($PlayerUuid, [ref]$uuid) -or $uuid -eq [guid]::Empty) { throw 'PlayerUuid is not a valid non-nil UUID.' }
foreach ($a in $Actors) { if ($a -ne '*' -and $a -notmatch '^[A-Za-z0-9_]{1,16}$') { throw "Bad actor name: $a" } }
if ($Dimension -notmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$') { throw 'Bad dimension id.' }
foreach ($id in @($Blocks) + @($Items)) { if ($id -notmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$') { throw "Bad resource id: $id" } }
if ($Anchor -and $Region) { throw 'Give -Anchor or -Region, not both.' }
if ($Anchor) {
    $p = $Anchor.Split(',') | ForEach-Object { [int]$_.Trim() }
    if ($p.Count -ne 3) { throw '-Anchor needs x,y,z' }
    $min = @(($p[0] - 2), ($p[1] - 2), ($p[2] - 2)); $max = @(($p[0] + 40), ($p[1] + 8), ($p[2] + 16))
} elseif ($Region) {
    $p = $Region.Split(',') | ForEach-Object { [int]$_.Trim() }
    if ($p.Count -ne 6) { throw '-Region needs minX,minY,minZ,maxX,maxY,maxZ' }
    $min = @($p[0], $p[1], $p[2]); $max = @($p[3], $p[4], $p[5])
    for ($i = 0; $i -lt 3; $i++) { if ($min[$i] -gt $max[$i]) { throw 'Region min must not exceed max (it is never swapped silently).' } }
} else { throw 'Give -Anchor x,y,z (the arena marker) or -Region minX,minY,minZ,maxX,maxY,maxZ. There is no default region.' }
$entry = [ordered]@{ id = 'owner-native'; requester = $uuid.ToString('D').ToLowerInvariant(); actions = @('break_block', 'pickup_item');
                     dimensions = @($Dimension); actors = @($Actors); blocks = @($Blocks); items = @($Items);
                     regions = @([ordered]@{ min = @($min); max = @($max) }) }
$json = ([ordered]@{ schema_version = 1; entries = @($entry) } | ConvertTo-Json -Depth 8)
if (Test-Path -LiteralPath $target) {
    if (-not $Force) { throw "$target exists; pass -Force to replace it (a .bak copy is kept)." }
    Copy-Item -LiteralPath $target -Destination ($target + '.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
[IO.File]::WriteAllText($target, $json + "`n", (New-Object Text.UTF8Encoding $false))
Write-Host "Wrote $target"
Write-Host $json
Write-Host 'Only break_block and pickup_item exist. A break removes a block, damages the tool and drops items; a pickup moves a dropped stack into the NPC inventory. Use a throw-away test world.'
