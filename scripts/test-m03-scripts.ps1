$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
foreach($file in Get-ChildItem -LiteralPath (Join-Path $root 'scripts') -Filter '*m03*.ps1'){
    $tokens=$null;$parseErrors=$null
    $null=[System.Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$parseErrors)
    if($parseErrors.Count){throw ($parseErrors | Out-String)}
}
Write-Host 'PASS M03 PowerShell syntax'
