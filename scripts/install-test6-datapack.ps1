param([Parameter(Mandatory=$true)][string]$WorldName)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'install-m03-harness.ps1') -WorldName $WorldName
Write-Host 'Legacy Test6 now uses /function npcortex_m03:enable then /function npcortex_m03:test6a'
