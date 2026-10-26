param([string]$WorldName='New World (2)',[Parameter(Mandatory=$true)][string]$Database)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$world=Join-Path $root ('forge-mod\run\saves\' + $WorldName)
if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))) { throw 'World not found' }
if (-not (Test-Path -LiteralPath $Database)) { throw 'Backend DB not found: confirm NPCORTEX_DB_PATH' }
$out=Join-Path $root ('M04-Review-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.json')
& python (Join-Path $PSScriptRoot 'm04_acceptance_review.py') --world $world --database (Resolve-Path -LiteralPath $Database).Path --output $out
if ($LASTEXITCODE -ne 0) { throw 'Read-only M04 evidence review failed' }
Write-Host "Send review JSON and relevant m04-evidence files: $out" -ForegroundColor Cyan
