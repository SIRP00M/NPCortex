# Operator-directed fork / rename of a world's native binding. See scripts\native_rebind.py. Nothing is deleted or edited; the move is audited.
#   scripts\native-rebind.ps1 -WorldId <world uuid> -FolderKey "NPCortex_Native_Test2" -Reason "copied the test world on purpose"
param(
    [Parameter(Mandatory)][string]$WorldId,
    [Parameter(Mandatory)][string]$FolderKey,
    [Parameter(Mandatory)][string]$Reason,
    [string]$Url = 'http://127.0.0.1:8765'
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& python (Join-Path $PSScriptRoot 'native_rebind.py') --world-id $WorldId --folder-key $FolderKey --reason $Reason --url $Url
exit $LASTEXITCODE
