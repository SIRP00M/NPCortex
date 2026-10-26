param([Parameter(Mandatory=$true)][string]$Database,[Parameter(Mandatory=$true)][string]$EvidenceDirectory,[int]$Port=18765)
$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'm03_final.py') owned-backend --database $Database --evidence $EvidenceDirectory --port $Port
if($LASTEXITCODE -ne 0){throw 'Owned backend controller HOLD; existing processes untouched'}
