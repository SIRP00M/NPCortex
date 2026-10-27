# Installs the NPCortex G5 TEST-ONLY data pack into a DISPOSABLE world. Refuses any world not named NPCortex_G5_*.
# Never overwrites an existing pack unless -Update is given (then ONLY datapacks/NPCortex_G5 is replaced); never touches other worlds/saves.
param([Parameter(Mandatory=$true)][string]$WorldName, [switch]$Remove, [switch]$Update)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($WorldName -notmatch '^NPCortex_G5_[A-Za-z0-9_\-]+$') { throw "Refusing '$WorldName': a disposable test world must be named NPCortex_G5_<something> (letters, digits, _ or -)." }
$saves = Join-Path $root 'forge-mod/run/saves'
$world = Join-Path $saves $WorldName
if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))) { throw "No level.dat in $world. Create the SUPERFLAT world '$WorldName' in Minecraft first, then quit to the title screen." }
foreach ($p in @($world, (Join-Path $world 'datapacks'))) {
    if ((Test-Path -LiteralPath $p) -and ((Get-Item -LiteralPath $p).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Refusing a reparse point' }
}
$dest = Join-Path $world 'datapacks/NPCortex_G5'
if ($Remove) {
    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force; "Removed $dest (only the G5 pack)." } else { 'Nothing to remove.' }
    return
}
$src = Join-Path $root 'integrations/datapacks/NPCortex_G5'
if (-not (Test-Path -LiteralPath (Join-Path $src 'pack.mcmeta'))) { throw 'Pack missing; run scripts/build-g5-datapack.ps1' }
if (Test-Path -LiteralPath $dest) {
    if (-not $Update) { throw "$dest already exists; not overwriting (use -Update to replace the G5 pack with the current build, or -Remove)." }
    Remove-Item -LiteralPath $dest -Recurse -Force; 'Replaced the previous G5 pack (only datapacks/NPCortex_G5).'
}
New-Item -ItemType Directory -Force -Path (Join-Path $world 'datapacks') | Out-Null
Copy-Item -LiteralPath $src -Destination $dest -Recurse
"Installed. Close the world's session first if it is open (or run /reload in it). In the world, as operator, in this order:"
"  /reload"
"  /function npcortex_g5:setup      (resets the pack state, stops any stale loop)"
"  /function npcortex_g5:probe      (p00 v2: ~8 s, prints PROBE <capability>=PASS|FAIL|NOT_ATTEMPTED lines and mode=ADAPTIVE or OWNER_GATED)"
"  /function npcortex_g5:start      (all scenarios; the Save&Quit checkpoint is announced in chat)"
"In OWNER_GATED mode (or after a timeout/stall) the pack stops at [G5] AWAIT: run scriptsg5-gate.ps1 -WorldName $WorldName -Watch in PowerShell; when it prints GO use the command it copied (/function npcortex_g5:gate/<id>)."
"Finally: scripts/collect-g5-evidence.ps1 -WorldName $WorldName"
