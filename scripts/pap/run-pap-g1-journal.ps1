# PAP-G1 TEST-ONLY: C1 inventory journal benchmark on a disposable temp dir. Production sources are READ, never edited.
# 512 = production-verified (unmodified copy of the production sources). 2048/4096 = SIMULATED (MAX_RECEIPTS rewritten in a temp copy).
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$src = Join-Path $root 'forge-mod\src\main\java\io\github\sirp00m\npcortex'
$cpFile = Join-Path $root 'forge-mod\build\classpath\runGameTestServer_minecraftClasspath.txt'
if (-not (Test-Path $cpFile)) { throw 'BLOCKED: forge-mod build classpath file missing (run a Gradle build once)' }
$cp = ((Get-Content -Raw $cpFile).Trim() -replace "\r?\n", ';')
$work = Join-Path ([IO.Path]::GetTempPath()) ('pap_g1_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
try {
    foreach ($cap in 512, 2048, 4096) {
        $label = if ($cap -eq 512) { 'PRODUCTION-512' } else { "SIMULATED-$cap" }
        $s = Join-Path $work "src-$cap"; $o = Join-Path $work "out-$cap"
        New-Item -ItemType Directory -Force $s, $o | Out-Null
        foreach ($f in 'C1InventoryJournal.java', 'C1InventoryData.java') { Copy-Item (Join-Path $src $f) $s }
        if ($cap -ne 512) {
            $j = Join-Path $s 'C1InventoryJournal.java'
            $t = (Get-Content -Raw $j) -replace 'MAX_RECEIPTS = 512', "MAX_RECEIPTS = $cap"
            if ($t -notmatch "MAX_RECEIPTS = $cap") { throw 'instrumentation did not apply' }
            [IO.File]::WriteAllText($j, $t, (New-Object Text.UTF8Encoding($false)))
        }
        Copy-Item (Join-Path $PSScriptRoot 'PapG1JournalBench.java') $s
        & javac --release 17 -cp $cp -d $o (Get-ChildItem $s -Filter *.java | ForEach-Object FullName)
        if ($LASTEXITCODE -ne 0) { throw "javac failed for $cap" }
        & java -cp "$o;$cp" io.github.sirp00m.npcortex.PapG1JournalBench $label (Join-Path $work "data-$cap")
        if ($LASTEXITCODE -ne 0) { Write-Host "RESULT $label : FAIL"; exit 1 } else { Write-Host "RESULT $label : checks passed" }
    }
} finally { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
