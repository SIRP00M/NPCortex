param([string]$EvidenceDirectory)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$arguments=@((Join-Path $PSScriptRoot 'm03_final.py'),'run')
if($EvidenceDirectory){$arguments+=@('--evidence',$EvidenceDirectory)}
& python @arguments
if($LASTEXITCODE -ne 0){throw 'M03 automated gate failed/HOLD. Inspect results.json and gate logs; no gameplay PASS implied.'}
