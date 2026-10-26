param(
    [Parameter(Mandatory=$true)][string]$WorldName
)
$ErrorActionPreference='Stop'
if($WorldName -match '[\\/]') { throw 'WorldName must be a save folder name, not a path' }
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pack=Join-Path $root 'integrations/datapacks/NPCortex_Test5_Automation.zip'
$save=Join-Path $root ('forge-mod/run/saves/'+$WorldName)
if(-not (Test-Path (Join-Path $save 'level.dat'))) {
    throw "World not found under forge-mod/run/saves: $WorldName. No files changed."
}
if(-not (Test-Path $pack)) { throw "Missing pack: $pack" }
$destination=Join-Path $save 'datapacks'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
Copy-Item -LiteralPath $pack -Destination (Join-Path $destination 'NPCortex_Test5_Automation.zip') -Force
Write-Host 'Installed TEMP Test5 datapack. In game: /reload then /function npcortex_test5:start'
Write-Host 'Run the function as an OP player. No coordinates needed.'
