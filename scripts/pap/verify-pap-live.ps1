# READ-ONLY report of the PAP ledger of YOUR backend database after a gameplay session (opens it with mode=ro).
#   scripts\pap\verify-pap-live.ps1 -Db <path to the backend sqlite file>
# PASS means: no place/use attempt is left RESERVED, EXECUTING or HOLD_IN_DOUBT and no claim is left ACTIVE/HELD. A HOLD that you have not
# reconciled (Rec. in the Manager) is reported as a problem on purpose.
param([Parameter(Mandatory)][string]$Db)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Db -PathType Leaf)) { throw "No such file: $Db" }
& python (Join-Path $PSScriptRoot 'verify_pap_db.py') $Db --live
exit $LASTEXITCODE
