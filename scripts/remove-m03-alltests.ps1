param([Parameter(Mandatory=$true)][string]$WorldName)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($WorldName -match '[\\/]' -or $WorldName -in @('.','..')){throw 'Single world directory name required'}
$saves=(Resolve-Path (Join-Path $root 'forge-mod/run/saves')).Path
$world=(Resolve-Path (Join-Path $saves $WorldName)).Path
if(-not $world.StartsWith($saves+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'World outside saves'}
if(-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))){throw 'World not found'}
$dest=Join-Path $world 'datapacks/NPCortex_M03_AllTests'
if(Test-Path -LiteralPath $dest){
  $item=Get-Item -LiteralPath $dest
  if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing reparse point'}
  if(Get-ChildItem -LiteralPath $dest -Recurse -Attributes ReparsePoint){throw 'Refusing nested reparse point'}
  Remove-Item -LiteralPath $dest -Recurse
}
Write-Host 'All-Tests datapack removed. World, SQLite, outbox, journals, HOLD and evidence untouched.'
