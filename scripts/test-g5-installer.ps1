# TEST-ONLY: runs install-g5-datapack.ps1 inside a throw-away copy of the repository layout (never touches real saves).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("g5-install-test-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path (Join-Path $tmp 'scripts'), (Join-Path $tmp 'integrations/datapacks') | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'install-g5-datapack.ps1') (Join-Path $tmp 'scripts')
Copy-Item -Recurse (Join-Path $root 'integrations/datapacks/NPCortex_G5') (Join-Path $tmp 'integrations/datapacks')
$other = Join-Path $tmp 'forge-mod/run/saves/OtherWorld'; New-Item -ItemType Directory -Force -Path $other | Out-Null; Set-Content (Join-Path $other 'level.dat') 'x'
$w = Join-Path $tmp 'forge-mod/run/saves/NPCortex_G5_T'; New-Item -ItemType Directory -Force -Path $w | Out-Null; Set-Content (Join-Path $w 'level.dat') 'x'
$ins = Join-Path $tmp 'scripts/install-g5-datapack.ps1'
$fails = New-Object System.Collections.ArrayList; $checks = 0
function Check($c, $m) { $script:checks++; if (-not $c) { [void]$script:fails.Add($m) } }
function Throws($sb) { try { & $sb | Out-Null; return $false } catch { return $true } }
Check (Throws { & $ins -WorldName 'OtherWorld' }) 'refuses a world not named NPCortex_G5_*'
Check (Throws { & $ins -WorldName 'NPCortex_G5_Missing' }) 'refuses a world without level.dat'
$null = & $ins -WorldName 'NPCortex_G5_T'
$dest = Join-Path $w 'datapacks/NPCortex_G5'
Check (Test-Path (Join-Path $dest 'pack.mcmeta')) 'fresh install copies the pack'
Check (Throws { & $ins -WorldName 'NPCortex_G5_T' }) 'second install without -Update refuses to overwrite'
Set-Content (Join-Path $dest 'STALE.txt') 'old'
$null = & $ins -WorldName 'NPCortex_G5_T' -Update
Check ((Test-Path (Join-Path $dest 'pack.mcmeta')) -and -not (Test-Path (Join-Path $dest 'STALE.txt'))) '-Update replaces the pack with the current build (stale files gone)'
Check (-not (Test-Path (Join-Path $other 'datapacks'))) 'other worlds are never touched'
$null = & $ins -WorldName 'NPCortex_G5_T' -Remove
Check (-not (Test-Path $dest) -and (Test-Path (Join-Path $w 'level.dat'))) '-Remove deletes only the G5 pack'
Remove-Item -LiteralPath $tmp -Recurse -Force
[pscustomobject]@{ checks = $checks; failures = $fails.Count; status = $(if ($fails.Count) { 'FAIL' } else { 'PASS' }) } | ConvertTo-Json
$fails | ForEach-Object { "FAIL: $_" }
if ($fails.Count) { exit 1 }
