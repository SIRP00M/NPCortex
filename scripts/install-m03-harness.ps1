param([Parameter(Mandatory=$true)][string]$WorldName)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($WorldName -match '[\\/]' -or $WorldName -in @('.','..')){throw 'Specify a single copied-world folder name'}
$saves=(Resolve-Path (Join-Path $root 'forge-mod/run/saves')).Path
$world=(Resolve-Path (Join-Path $saves $WorldName)).Path
if(-not $world.StartsWith($saves+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'World outside saves'}
if(-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))){throw 'World not found'}
foreach($path in @($world,(Join-Path $world 'datapacks'))){
    if((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Refusing world/datapack reparse point'}
}
$destination=Join-Path $world 'datapacks/NPCortex_M03'
if(Test-Path -LiteralPath $destination){throw 'M03 datapack already exists; preserve it or uninstall explicitly'}
Copy-Item -LiteralPath (Join-Path $root 'integrations/datapacks/NPCortex_M03') -Destination $destination -Recurse
Write-Host 'Installed datapack. After build/relaunch: /reload then /function npcortex_m03:enable'
