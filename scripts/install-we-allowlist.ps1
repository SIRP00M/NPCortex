# Writes the Owner's world-action allow-list for the Stage 4 NO-OP milestone. Nothing is allowed without this file.
#   scripts\install-we-allowlist.ps1 -ConfigDir <game or server>\config -PlayerUuid <your player UUID> -Actors Reika[,Sakura]
#   scripts\install-we-allowlist.ps1 -ConfigDir ... -Remove        # deletes the file (everything is denied again)
# The player UUID is the one shown by /data get entity @s UUID  (convert the int array) or in <world>\playerdata\<uuid>.dat.
# This script never reads or writes a world, a database or a journal. An existing file is only replaced with -Force and
# the old one is first copied to npcortex-world-actions.json.bak-<timestamp>.
param(
    [Parameter(Mandatory)][string]$ConfigDir,
    [string]$PlayerUuid,
    [string[]]$Actors,
    [string]$Dimension = 'minecraft:overworld',
    [switch]$Force,
    [switch]$Remove
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ConfigDir -PathType Container)) { throw "ConfigDir does not exist: $ConfigDir" }
$target = Join-Path $ConfigDir 'npcortex-world-actions.json'
if ($Remove) {
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target; Write-Host "Removed $target - all world actions are denied." }
    else { Write-Host 'No allow-list file present - all world actions are denied.' }
    exit 0
}
if (-not $PlayerUuid -or -not $Actors) { throw 'Give -PlayerUuid and -Actors (use "*" to allow every NPC, deliberately).' }
$uuid = [guid]::Empty
if (-not [guid]::TryParse($PlayerUuid, [ref]$uuid) -or $uuid -eq [guid]::Empty) { throw 'PlayerUuid is not a valid non-nil UUID.' }
foreach ($a in $Actors) { if ($a -ne '*' -and $a -notmatch '^[A-Za-z0-9_]{1,16}$') { throw "Bad actor name: $a" } }
if ($Dimension -notmatch '^[a-z0-9_.-]+:[a-z0-9_./-]+$') { throw 'Bad dimension id.' }
$entry = [ordered]@{ id = 'owner-noop'; requester = $uuid.ToString('D').ToLowerInvariant(); actions = @('noop');
                     dimensions = @($Dimension); actors = @($Actors) }
$json = ([ordered]@{ schema_version = 1; entries = @($entry) } | ConvertTo-Json -Depth 6)
if (Test-Path -LiteralPath $target) {
    if (-not $Force) { throw "$target exists; pass -Force to replace it (a .bak copy is kept)." }
    Copy-Item -LiteralPath $target -Destination ($target + '.bak-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
[IO.File]::WriteAllText($target, $json + "`n", (New-Object Text.UTF8Encoding $false))
Write-Host "Wrote $target"
Write-Host $json
Write-Host 'Only the no-op action exists in this milestone; it changes nothing in the world. The file is re-read on every request.'
