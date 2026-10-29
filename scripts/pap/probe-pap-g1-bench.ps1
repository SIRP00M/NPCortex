# PAP-G1 TEST-ONLY: proves the journal benchmark's behaviour checks catch broken journal semantics. Mutates a TEMP COPY only.
$ErrorActionPreference = 'Continue'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$src = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex'
$cp = ((Get-Content -Raw (Join-Path $root 'forge-mod\build\classpath\runGameTestServer_minecraftClasspath.txt')).Trim() -replace "\r?\n", ';')
$utf8 = New-Object Text.UTF8Encoding($false)
$mutations = @(
    @('control (no mutation)', '', ''),
    @('FULL never reported (lookup returns NEW)', 'receipts.size() == MAX_RECEIPTS ? Lookup.FULL : Lookup.NEW', 'Lookup.NEW'),
    @('REPLAY reported as CONFLICT', 'Lookup.REPLAY : Lookup.CONFLICT', 'Lookup.CONFLICT : Lookup.CONFLICT'),
    @('commit evicts the oldest instead of failing at the cap', 'if (lookup(id, fingerprint) != Lookup.NEW', 'if (receipts.size() >= MAX_RECEIPTS) receipts.remove(receipts.keySet().iterator().next()); if (lookup(id, fingerprint) != Lookup.NEW')
)
$bad = 0
foreach ($m in $mutations) {
    $work = Join-Path ([IO.Path]::GetTempPath()) ('pap_probe_' + [Guid]::NewGuid().ToString('N'))
    $s = Join-Path $work 's'; $o = Join-Path $work 'o'
    New-Item -ItemType Directory -Force $s, $o | Out-Null
    try {
        foreach ($f in 'C1InventoryJournal.java', 'C1InventoryData.java') { Copy-Item (Join-Path $src $f) $s }
        Copy-Item (Join-Path $PSScriptRoot 'PapG1JournalBench.java') $s
        if ($m[1]) {
            $j = Join-Path $s 'C1InventoryJournal.java'; $t = [IO.File]::ReadAllText($j)
            if (-not $t.Contains($m[1])) { throw "mutation target not found: $($m[0])" }
            [IO.File]::WriteAllText($j, $t.Replace($m[1], $m[2]), $utf8)
        }
        & javac --release 17 -cp $cp -d $o (Get-ChildItem $s -Filter *.java | ForEach-Object FullName) 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "javac failed: $($m[0])" }
        & java -cp "$o;$cp" io.github.sirp00m.npcortex.PapG1JournalBench PROBE (Join-Path $work 'd') 2>&1 | Out-Null
        $detected = $LASTEXITCODE -ne 0
        $expect = [bool]$m[1]
        $ok = ($detected -eq $expect); if (-not $ok) { $bad++ }
        Write-Host ("{0,-8} {1}" -f $(if (-not $expect) { if ($detected) { 'BAD' } else { 'PASS' } } elseif ($detected) { 'DETECTED' } else { 'MISSED' }), $m[0])
    } finally { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}
if ($bad) { Write-Host 'BENCH PROBE: FAIL'; exit 1 } else { Write-Host 'BENCH PROBE: PASS' }
