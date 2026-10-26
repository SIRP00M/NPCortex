param([Parameter(Mandatory=$true)][string]$EvidenceDirectory,[Parameter(Mandatory=$true)][string]$Output)
$ErrorActionPreference='Stop'
& python (Join-Path $PSScriptRoot 'm03_performance.py') $EvidenceDirectory --output $Output
if($LASTEXITCODE -ne 0){throw 'Performance summary failed'}
