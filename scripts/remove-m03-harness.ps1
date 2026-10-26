param([string]$WorldName,[switch]$RemoveFromSource)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if($WorldName){
    if($WorldName -match '[\\/]' -or $WorldName -in @('.','..')){throw 'Specify a single world folder name'}
    $saves=(Resolve-Path (Join-Path $root 'forge-mod/run/saves')).Path
    $world=(Resolve-Path (Join-Path $saves $WorldName)).Path
    foreach($path in @($world,(Join-Path $world 'datapacks'))){
        if((Test-Path -LiteralPath $path) -and ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Refusing world/datapack reparse point'}
    }
    $pack=[IO.Path]::GetFullPath((Join-Path $world 'datapacks/NPCortex_M03'))
    if(-not $pack.StartsWith($saves+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Path outside saves'}
    if(Test-Path -LiteralPath $pack){
        $item=Get-Item -LiteralPath $pack
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Refusing reparse point'}
        if(Get-ChildItem -LiteralPath $pack -Recurse -Attributes ReparsePoint){throw 'Refusing nested reparse point'}
        Remove-Item -LiteralPath $pack -Recurse
    }
}
if($RemoveFromSource){
    & python (Join-Path $PSScriptRoot 'm03_uninstall.py')
    if($LASTEXITCODE -ne 0){throw 'Source uninstall failed; inspect diagnostic'}
}
Write-Host 'World data, SQLite, journal, outbox and evidence retained. Rebuild after source uninstall. Remove M03 before Test5.'
