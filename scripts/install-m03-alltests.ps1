param([Parameter(Mandatory=$true)][string]$WorldName)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($WorldName -match '[\\/]' -or $WorldName -in @('.','..')){throw 'Single copied-world directory name required'}
$saves=(Resolve-Path (Join-Path $root 'forge-mod/run/saves')).Path
$world=(Resolve-Path (Join-Path $saves $WorldName)).Path
if(-not $world.StartsWith($saves+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'World outside saves'}
if(-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))){throw 'No level.dat; expected a copied test world'}
foreach($p in @($world,(Join-Path $world 'datapacks'))){
    if((Test-Path -LiteralPath $p) -and ((Get-Item -LiteralPath $p).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Refusing reparse point'}
}
$base=Join-Path $world 'datapacks/NPCortex_M03'
if(-not (Test-Path -LiteralPath $base)){
    $standard=Join-Path $root 'integrations/datapacks/NPCortex_M03'
    Copy-Item -LiteralPath $standard -Destination $base -Recurse
    Write-Host 'Base M03 datapack also installed (was absent)'
}
$source=Join-Path $root 'integrations/datapacks/NPCortex_M03_AllTests'
$destination=Join-Path $world 'datapacks/NPCortex_M03_AllTests'
if(Test-Path -LiteralPath $destination){throw 'All-Tests pack already exists; no overwriting'}
Copy-Item -LiteralPath $source -Destination $destination -Recurse
Write-Host 'M03 All-Tests pack installed; /reload then /function npcortex_m03_suite:enable'
