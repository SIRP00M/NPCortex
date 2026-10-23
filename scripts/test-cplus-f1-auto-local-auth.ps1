# No manual token creation required. Tests isolated credentials and protocol compatibility.
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$oldPython = $env:PYTHONPATH
$oldToken = $env:NPCORTEX_CPLUS_F1_TOKEN
$oldFile = $env:NPCORTEX_CPLUS_F1_SECRET_FILE
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('npcortex_auto_auth_' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
try {
    $env:PYTHONPATH = Join-Path $root 'backend/src'
    $env:NPCORTEX_CPLUS_F1_TOKEN = ''
    $env:NPCORTEX_CPLUS_F1_SECRET_FILE = Join-Path $tmp 'bridge.secret'
    # Use a real Python file. Passing quoted Python source via `python -c` loses
    # its inner quotes on some Windows PowerShell / Python 3.13 combinations.
    & python (Join-Path $PSScriptRoot 'cplus-f1-auth-bootstrap-smoke.py')
    if ($LASTEXITCODE -ne 0) {throw 'Backend credential setup failed'}
    $javaSrc = Join-Path $root 'forge-mod/src/main/java/io/github/sirp00m/npcortex/cplus/CPlusLocalAuth.java'
    $javaTest = Join-Path $root 'forge-mod/src/test/java/io/github/sirp00m/npcortex/CPlusLocalAuthSelfTest.java'
    & javac --release 17 -encoding UTF-8 -d $tmp $javaSrc $javaTest
    if ($LASTEXITCODE -ne 0) {throw 'Java auth contract compile failed'}
    & java -cp $tmp io.github.sirp00m.npcortex.CPlusLocalAuthSelfTest $env:NPCORTEX_CPLUS_F1_SECRET_FILE
    if ($LASTEXITCODE -ne 0) {throw 'Java-Python secret parity failed'}
    foreach ($pattern in @('test_cplus_local_auth.py', 'test_cplus_f1_bridge.py', 'test_cplus_f1_dplus.py')) {
        & python -W error::ResourceWarning -m unittest discover -s (Join-Path $root 'backend/tests') -p $pattern -q
        if ($LASTEXITCODE -ne 0) {throw "Backend regression failed: $pattern"}
    }
    Write-Host 'PASS C-F1 automatic local authentication regression; Windows Minecraft/Forge build still separate.'
} finally {
    $env:PYTHONPATH = $oldPython
    $env:NPCORTEX_CPLUS_F1_TOKEN = $oldToken
    $env:NPCORTEX_CPLUS_F1_SECRET_FILE = $oldFile
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
